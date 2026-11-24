package app.shuttle.assistant.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import app.shuttle.assistant.MainActivity
import app.shuttle.assistant.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Downloads the model in the background, as a foreground service with a progress
 * notification, so it carries on with Shuttle's screen closed.
 *
 * How it behaves:
 * - **Resumes.** Bytes already on disk are kept; after a dropped connection or a restart it asks
 *   the server for the rest (an HTTP Range request).
 * - **Follows "Wi-Fi only" live.** Before each chunk it checks the current connection; on mobile
 *   data with Wi-Fi only on, it pauses and says "Waiting for Wi-Fi", and continues as soon as
 *   Wi-Fi is back or the switch is turned off.
 * - **Waits out bad connections**, retrying with a growing pause, instead of failing.
 * - **Spots a blocked network.** With the phone online but every connection refused or every
 *   name unresolvable, Shuttle's Network permission is almost certainly off (GrapheneOS asks
 *   for it only when an app is first installed, and an update that adds it starts with it off).
 *   It stops and says so, with a button in Settings to App info.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val CHANNEL = "model_download"
        private const val NOTIFICATION = 1
        private const val EXTRA_SPEC = "spec"

        fun start(c: Context, spec: ModelSpec) {
            val intent = Intent(c, ModelDownloadService::class.java).putExtra(EXTRA_SPEC, ModelSpec.encode(spec))
            c.startForegroundService(intent)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ModelDownloadService::class.java))
        }
    }

    @Volatile private var stopped = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val spec = intent?.getStringExtra(EXTRA_SPEC)?.let(ModelSpec::parse)
            ?: ModelSpec.parse(ModelManager.prefs(this).getString("download_spec", "") ?: "")
        if (spec == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        channel()
        val first = notification(spec, "Starting…", 0, indeterminate = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION, first)
        }
        if (worker?.isAlive != true) {
            stopped = false
            worker = Thread({ run(spec) }, "shuttle-model-download").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        worker?.interrupt()
        super.onDestroy()
    }

    private fun publish(state: ModelManager.State) = ModelManager.main.post { ModelManager.state = state }

    private fun run(spec: ModelSpec) {
        val partial = File(ModelManager.dir(this), spec.fileName + ".download")
        var backoff = 5_000L
        var blockedSignals = 0
        var lastShown = -1
        while (!stopped) {
            val have = if (partial.exists()) partial.length() else 0L
            if (have >= spec.size) break
            when (connection()) {
                Link.NONE -> {
                    publish(ModelManager.State.WaitingForNetwork(have, spec.size))
                    show(spec, "Waiting for a connection", percent(have, spec))
                    sleep(3_000)
                    continue
                }
                Link.METERED -> if (ModelManager.prefs(this).getBoolean("wifi_only", true)) {
                    publish(ModelManager.State.WaitingForWifi(have, spec.size))
                    show(spec, "Waiting for Wi-Fi", percent(have, spec))
                    sleep(3_000)
                    continue
                }
                Link.UNMETERED -> Unit
            }
            try {
                fetch(spec, partial, have) { done ->
                    val p = percent(done, spec)
                    publish(ModelManager.State.Downloading(done, spec.size))
                    if (p != lastShown) {
                        lastShown = p
                        show(spec, "$p% · ${spec.sizeLabel}", p)
                    }
                }
                backoff = 5_000L
                blockedSignals = 0
            } catch (e: Pause) {
                // Wi-Fi was lost with "Wi-Fi only" on; the loop shows "Waiting for Wi-Fi".
            } catch (e: IOException) {
                if (stopped) return
                // Online but nothing resolves or connects: the app's network access is off.
                val blocked = (e is UnknownHostException || (e is SocketException && e.message?.contains("EACCES") == true) ||
                    e.message?.contains("Permission denied") == true) && connection() != Link.NONE
                if (blocked && ++blockedSignals >= 2) {
                    publish(
                        ModelManager.State.Failed(
                            "Shuttle isn't allowed to use the network. Turn on its Network permission in App info, then tap Try again.",
                            networkBlocked = true,
                        ),
                    )
                    finish()
                    return
                }
                if (e is HttpError && e.code in 400..499 && e.code != 416 && e.code != 429) {
                    publish(ModelManager.State.Failed("The download server refused the request (HTTP ${e.code}). Try again later."))
                    finish()
                    return
                }
                if (e is HttpError && e.code == 416) partial.delete() // our partial file doesn't fit; start over
                publish(ModelManager.State.WaitingForNetwork(if (partial.exists()) partial.length() else 0, spec.size))
                show(spec, "Connection lost, retrying", percent(have, spec))
                sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
        if (stopped) return
        publish(ModelManager.State.Checking)
        show(spec, "Checking the download…", 100, indeterminate = true)
        publish(ModelManager.install(this, spec, partial))
        finish()
    }

    private class Pause : IOException()
    private class HttpError(val code: Int) : IOException("HTTP $code")

    /** Appends the rest of the file to [partial], starting at byte [from]. */
    private fun fetch(spec: ModelSpec, partial: File, from: Long, progress: (Long) -> Unit) {
        val conn = URL(spec.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
        try {
            val code = conn.responseCode
            val resumed = code == 206
            if (code != 200 && code != 206) throw HttpError(code)
            // A plain 200 means the server sent the whole file again: start the file over.
            FileOutputStream(partial, resumed).use { out ->
                var done = if (resumed) from else 0L
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var sinceCheck = 0L
                    while (!stopped) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        sinceCheck += n
                        if (sinceCheck >= 4L * 1024 * 1024) {
                            sinceCheck = 0
                            progress(done)
                            if (connection() == Link.METERED && ModelManager.prefs(this).getBoolean("wifi_only", true)) throw Pause()
                        }
                    }
                }
                progress(done)
            }
        } finally {
            conn.disconnect()
        }
    }

    private enum class Link { NONE, METERED, UNMETERED }

    private fun connection(): Link {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Link.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Link.NONE
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) Link.UNMETERED else Link.METERED
    }

    private fun percent(done: Long, spec: ModelSpec) = if (spec.size > 0) (done * 100 / spec.size).toInt() else 0

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun finish() {
        ModelManager.main.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // --- Notification ----------------------------------------------------------------------

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(spec: ModelSpec, text: String, percent: Int, indeterminate: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Downloading ${spec.name}")
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun show(spec: ModelSpec, text: String, percent: Int, indeterminate: Boolean = false) {
        if (stopped) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(spec, text, percent, indeterminate))
    }
}

/** SHA-256 of a file, as lowercase hex. */
internal object Checksum {
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
