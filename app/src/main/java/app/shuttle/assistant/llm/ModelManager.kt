package app.shuttle.assistant.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Getting the model onto the phone: download, check, install, update, delete.
 *
 * The download goes through Android's own DownloadManager, so it carries on with the screen
 * off or Shuttle closed, resumes after a dropped connection, shows progress in the
 * notification shade, and waits for Wi-Fi when "Wi-Fi only" is on (the default; the model is
 * 2.2 GB). Nothing downloads until the user taps Download. When it finishes, the file's
 * SHA-256 is checked against [ModelSpec.sha256] before it's used; a mismatch is deleted.
 *
 * The model lives in the app's own external files folder, which only Shuttle can read on
 * Android 11+ and which is removed with the app. [state] is Compose state, so Settings and
 * the chat update as the download moves.
 */
object ModelManager {

    sealed interface State {
        data object Missing : State
        data object WaitingForWifi : State
        data class Downloading(val done: Long, val total: Long) : State {
            val percent: Int get() = if (total > 0) (done * 100 / total).toInt() else 0
        }
        data object Checking : State
        data class Ready(val spec: ModelSpec, val file: File) : State
        data class Failed(val message: String) : State
    }

    var state: State by mutableStateOf(State.Missing)
        private set

    /** The model to download (from the manifest, else [ModelSpec.BUILT_IN]). */
    var current: ModelSpec by mutableStateOf(ModelSpec.BUILT_IN)
        private set

    /** Set when the manifest names a different model than the one installed. */
    var update: ModelSpec? by mutableStateOf(null)
        private set

    val ready: State.Ready? get() = state as? State.Ready

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "shuttle-model-files") }
    private var app: Context? = null
    private var started = false

    private fun prefs(c: Context) = c.getSharedPreferences("model", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "models").apply { mkdirs() }

    /** Download only on Wi-Fi (the default). Applies to the next download. */
    fun wifiOnly(c: Context): Boolean = prefs(c).getBoolean("wifi_only", true)
    fun setWifiOnly(c: Context, value: Boolean) = prefs(c).edit().putBoolean("wifi_only", value).apply()

    /** Reads what's installed and resumes watching a running download. Safe to call often. */
    fun start(context: Context) {
        val c = context.applicationContext
        app = c
        if (started) return
        started = true
        val p = prefs(c)
        val installedId = p.getString("installed_id", null)
        val installed = installedId?.let { id ->
            ModelSpec.parse(p.getString("installed_spec", "") ?: "")?.takeIf { it.id == id }
        }
        val file = installed?.let { File(dir(c), it.fileName) }
        state = if (installed != null && file != null && file.exists()) State.Ready(installed, file) else State.Missing
        if (p.getLong("download_id", -1) >= 0) watch()
        // Check the manifest for a newer choice, in the background.
        io.execute {
            val spec = ModelSpec.fetchManifest() ?: return@execute
            main.post {
                val installedNow = ready?.spec
                if (installedNow == null) {
                    current = spec
                } else if (spec.id != installedNow.id || spec.sha256 != installedNow.sha256) {
                    current = spec
                    update = spec
                }
            }
        }
    }

    fun download(context: Context, spec: ModelSpec = current) {
        val c = context.applicationContext
        val dm = c.getSystemService(DownloadManager::class.java)
        val p = prefs(c)
        p.getLong("download_id", -1).takeIf { it >= 0 }?.let { dm.remove(it) }
        File(dir(c), spec.fileName + ".download").delete()
        val request = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("Shuttle model")
            .setDescription("${spec.name} · ${spec.sizeLabel}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(c, "models", spec.fileName + ".download")
            .setAllowedOverMetered(!wifiOnly(c))
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(request)
        p.edit().putLong("download_id", id).putString("download_spec", encode(spec)).apply()
        state = State.Downloading(0, spec.size)
        watch()
    }

    fun cancel(context: Context) {
        val c = context.applicationContext
        val p = prefs(c)
        p.getLong("download_id", -1).takeIf { it >= 0 }?.let { c.getSystemService(DownloadManager::class.java).remove(it) }
        p.edit().remove("download_id").remove("download_spec").apply()
        main.removeCallbacks(poll)
        state = installedState(c)
    }

    fun delete(context: Context) {
        val c = context.applicationContext
        LocalModel.unloadNow()
        val p = prefs(c)
        io.execute { dir(c).listFiles { f -> f.name.endsWith(".gguf") }?.forEach { it.delete() } }
        p.edit().remove("installed_id").remove("installed_spec").apply()
        update = null
        state = if (p.getLong("download_id", -1) >= 0) state else State.Missing
    }

    // --- Watching the download -------------------------------------------------------------

    private val poll = object : Runnable {
        override fun run() {
            val c = app ?: return
            if (check(c)) main.postDelayed(this, 1000)
        }
    }

    private fun watch() {
        main.removeCallbacks(poll)
        main.post(poll)
    }

    /** Updates [state] from DownloadManager; true while there's still something to watch. */
    private fun check(c: Context): Boolean {
        val p = prefs(c)
        val id = p.getLong("download_id", -1)
        if (id < 0) return false
        val spec = ModelSpec.parse(p.getString("download_spec", "") ?: "") ?: current
        val dm = c.getSystemService(DownloadManager::class.java)
        return dm.query(DownloadManager.Query().setFilterById(id)).use { cur ->
            if (!cur.moveToFirst()) {
                p.edit().remove("download_id").apply()
                state = installedState(c)
                return@use false
            }
            val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: spec.size
            val reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    p.edit().remove("download_id").apply()
                    finish(c, spec)
                    false
                }
                DownloadManager.STATUS_FAILED -> {
                    p.edit().remove("download_id").apply()
                    dm.remove(id)
                    state = State.Failed("The download failed (${reasonText(reason)}). Try again.")
                    false
                }
                DownloadManager.STATUS_PAUSED -> {
                    state = if (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) State.WaitingForWifi else State.Downloading(done, total)
                    true
                }
                else -> {
                    state = State.Downloading(done, total)
                    true
                }
            }
        }
    }

    /** Checks the downloaded file and installs it in place of any older model. */
    private fun finish(c: Context, spec: ModelSpec) {
        state = State.Checking
        io.execute {
            val folder = dir(c)
            val partial = File(folder, spec.fileName + ".download")
            val ok = partial.exists() && partial.length() == spec.size && sha256(partial) == spec.sha256
            val result: State = if (ok) {
                LocalModel.unloadNow()
                folder.listFiles { f -> f.name.endsWith(".gguf") }?.forEach { it.delete() }
                val target = File(folder, spec.fileName)
                if (partial.renameTo(target)) {
                    prefs(c).edit().putString("installed_id", spec.id).putString("installed_spec", encode(spec)).apply()
                    State.Ready(spec, target)
                } else {
                    State.Failed("The model couldn't be saved. Check free storage and try again.")
                }
            } else {
                partial.delete()
                State.Failed("The download was incomplete or damaged, so it was deleted. Try again.")
            }
            main.post {
                state = result
                if (result is State.Ready) update = null
            }
        }
    }

    private fun installedState(c: Context): State {
        val p = prefs(c)
        val spec = ModelSpec.parse(p.getString("installed_spec", "") ?: "") ?: return State.Missing
        val file = File(dir(c), spec.fileName)
        return if (file.exists()) State.Ready(spec, file) else State.Missing
    }

    private fun sha256(file: File): String {
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

    private fun reasonText(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough storage"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "server error"
        DownloadManager.ERROR_CANNOT_RESUME -> "couldn't resume"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage unavailable"
        else -> "code $reason"
    }

    private fun encode(spec: ModelSpec): String = org.json.JSONObject().put(
        "model",
        org.json.JSONObject()
            .put("id", spec.id).put("name", spec.name).put("format", spec.format).put("url", spec.url)
            .put("sha256", spec.sha256).put("size", spec.size).put("license", spec.license),
    ).toString()
}
