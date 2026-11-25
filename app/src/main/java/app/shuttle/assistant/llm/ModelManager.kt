package app.shuttle.assistant.llm

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors

/**
 * Getting the model onto the phone: download, check, install, update, delete.
 *
 * Shuttle downloads the model itself ([ModelDownloadService], a foreground service with a
 * progress notification), not through Android's DownloadManager. The system downloader runs
 * outside the app, so on GrapheneOS it can't see Shuttle's Network permission being off and
 * just sits at 0%, and it fixes the Wi-Fi rule when the download starts. Doing it in-app means
 * the "Wi-Fi only" switch applies at once, a dropped connection resumes from where it stopped,
 * and a blocked network is reported as such with a way to fix it.
 *
 * Nothing downloads until the user taps Download. The finished file's SHA-256 must match
 * [ModelSpec.sha256] or it's deleted. The model lives in the app's own external files folder,
 * which only Shuttle can read and which is removed with the app. [state] is Compose state, so
 * Settings and the chat follow along. While an update downloads, [installed] keeps the current
 * model answering; it's swapped only once the new file has passed its checksum.
 */
object ModelManager {

    sealed interface State {
        data object Missing : State
        data class WaitingForWifi(val done: Long, val total: Long) : State
        data class WaitingForNetwork(val done: Long, val total: Long) : State
        data class Downloading(val done: Long, val total: Long) : State {
            val percent: Int get() = if (total > 0) (done * 100 / total).toInt() else 0
        }
        data object Checking : State
        data class Ready(val spec: ModelSpec, val file: File) : State
        /** [networkBlocked]: Shuttle isn't allowed to use the network (GrapheneOS's Network permission). */
        data class Failed(val message: String, val networkBlocked: Boolean = false) : State
    }

    var state: State by mutableStateOf(State.Missing)
        internal set

    /** The model to download (from the manifest, else [ModelSpec.BUILT_IN]). */
    var current: ModelSpec by mutableStateOf(ModelSpec.BUILT_IN)
        private set

    /** Set when the manifest names a different model than the one installed. */
    var update: ModelSpec? by mutableStateOf(null)
        private set

    /** Mirrors the "Wi-Fi only" setting so the switch redraws as soon as it's flipped. */
    var wifiOnly: Boolean by mutableStateOf(true)
        private set

    /** The model on the phone, if any. Stays usable while an update downloads. */
    var installed: State.Ready? by mutableStateOf(null)
        private set

    /** The model to answer with: the installed one, even mid-update. */
    val ready: State.Ready? get() = (state as? State.Ready) ?: installed

    internal val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "shuttle-model-files") }
    private var started = false

    internal fun prefs(c: Context) = c.getSharedPreferences("model", Context.MODE_PRIVATE)
    internal fun dir(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "models").apply { mkdirs() }

    /** A running download picks this up on its next check, within a few seconds. */
    fun setWifiOnly(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("wifi_only", value).apply()
        wifiOnly = value
    }

    /** Reads what's installed, resumes an unfinished download, checks for updates. Safe to call often. */
    fun start(context: Context) {
        val c = context.applicationContext
        if (started) return
        started = true
        val p = prefs(c)
        wifiOnly = p.getBoolean("wifi_only", true)
        // Earlier builds handed the download to Android's DownloadManager; drop that request.
        if (p.contains("download_id")) {
            p.getLong("download_id", -1).takeIf { it >= 0 }?.let {
                runCatching { c.getSystemService(android.app.DownloadManager::class.java).remove(it) }
            }
            p.edit().remove("download_id").apply()
        }
        state = installedState(c)
        installed = state as? State.Ready
        val pending = ModelSpec.parse(p.getString("download_spec", "") ?: "")
        if (pending != null) {
            // The download was interrupted (phone restarted, app updated); carry on.
            current = pending
            state = State.Downloading(0, pending.size)
            ModelDownloadService.start(c, pending)
        }
        io.execute {
            val spec = ModelSpec.fetchManifest() ?: return@execute
            main.post {
                val installedNow = ready?.spec
                if (installedNow == null) {
                    if (ModelSpec.parse(prefs(c).getString("download_spec", "") ?: "") == null) current = spec
                } else if (spec.id != installedNow.id || spec.sha256 != installedNow.sha256) {
                    current = spec
                    update = spec
                }
            }
        }
    }

    fun download(context: Context, spec: ModelSpec = current) {
        val c = context.applicationContext
        current = spec
        prefs(c).edit().putString("download_spec", ModelSpec.encode(spec)).apply()
        state = State.Downloading(0, spec.size)
        ModelDownloadService.start(c, spec)
    }

    fun cancel(context: Context) {
        val c = context.applicationContext
        val spec = ModelSpec.parse(prefs(c).getString("download_spec", "") ?: "")
        prefs(c).edit().remove("download_spec").apply()
        ModelDownloadService.stop(c)
        if (spec != null) io.execute { File(dir(c), spec.fileName + ".download").delete() }
        state = installedState(c)
    }

    fun delete(context: Context) {
        val c = context.applicationContext
        LocalModel.unloadNow()
        io.execute { dir(c).listFiles { f -> f.name.endsWith(".gguf") }?.forEach { it.delete() } }
        prefs(c).edit().remove("installed_id").remove("installed_spec").apply()
        update = null
        installed = null
        if (state is State.Ready) state = State.Missing
    }

    /** Shuttle's App info page, where GrapheneOS keeps the Network permission switch. */
    fun appInfoIntent(c: Context): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${c.packageName}"))

    /** Called by the download service once the whole file is on disk (on its thread). */
    internal fun install(c: Context, spec: ModelSpec, partial: File): State {
        val folder = dir(c)
        val ok = partial.exists() && partial.length() == spec.size && Checksum.sha256(partial) == spec.sha256
        if (!ok) {
            partial.delete()
            prefs(c).edit().remove("download_spec").apply()
            return State.Failed("The download was damaged, so it was deleted. Try again.")
        }
        LocalModel.unloadNow()
        main.post { installed = null }
        folder.listFiles { f -> f.name.endsWith(".gguf") }?.forEach { it.delete() }
        val target = File(folder, spec.fileName)
        if (!partial.renameTo(target)) return State.Failed("The model couldn't be saved. Check free storage and try again.")
        prefs(c).edit()
            .putString("installed_id", spec.id)
            .putString("installed_spec", ModelSpec.encode(spec))
            .remove("download_spec")
            .apply()
        val done = State.Ready(spec, target)
        main.post {
            update = null
            installed = done
        }
        // Get it ready for the first question.
        LocalModel.prepare(target)
        return done
    }

    internal fun installedState(c: Context): State {
        val spec = ModelSpec.parse(prefs(c).getString("installed_spec", "") ?: "") ?: return State.Missing
        val file = File(dir(c), spec.fileName)
        return if (file.exists()) State.Ready(spec, file) else State.Missing
    }
}
