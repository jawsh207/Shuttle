package app.shuttle.assistant.llm

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * The downloaded model, ready to answer: loads it on first use, runs one reply at a time on a
 * background thread, and unloads it after a few idle minutes to give the memory back.
 *
 * Loading takes a few seconds and holds about 3 GB of memory while loaded, so it happens only
 * when a request actually needs the model (the rule parser answers everything it can without
 * it), and the model is let go once the conversation goes quiet. The context is capped at
 * 8K tokens, plenty for an assistant and far less memory than the model's full 128K.
 */
object LocalModel {

    private const val CONTEXT = 8192
    private const val IDLE_UNLOAD_MS = 5 * 60 * 1000L

    sealed interface Event {
        /** The visible reply so far (already cleaned of tool-call markup by the caller). */
        data class Text(val raw: String) : Event
        data class Finished(val raw: String) : Event
        data class Failed(val message: String) : Event
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "shuttle-model").apply { priority = Thread.NORM_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val unload = Runnable { worker.execute { release() } }

    @Volatile private var handle = 0L
    @Volatile private var loadedPath: String? = null
    @Volatile private var cancelled = false

    /** Stops the reply being written, at the next piece. */
    fun cancel() {
        cancelled = true
    }

    /**
     * Writes a reply to [prompt] with the model in [file]. [onEvent] is called on the main
     * thread with the growing raw text, then once with [Event.Finished] or [Event.Failed].
     * [shouldStop] can end it early once it has seen enough (it gets the raw text so far).
     */
    fun generate(
        file: File,
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.3f,
        shouldStop: (String) -> Boolean = { false },
        onEvent: (Event) -> Unit,
    ) {
        main.removeCallbacks(unload)
        cancelled = false
        worker.execute {
            try {
                if (!ensureLoaded(file)) {
                    post(onEvent, Event.Failed("The model couldn't be loaded. Try deleting and downloading it again in Settings."))
                    return@execute
                }
                val out = StringBuilder()
                var lastSent = 0L
                val code = LlamaEngine.nativeGenerate(handle, prompt, maxTokens, temperature) { bytes ->
                    out.append(String(bytes, Charsets.UTF_8))
                    val now = System.currentTimeMillis()
                    // Update the screen at most ~20 times a second.
                    if (now - lastSent > 50) {
                        lastSent = now
                        post(onEvent, Event.Text(out.toString()))
                    }
                    !cancelled && !shouldStop(out.toString())
                }
                when (code) {
                    0 -> post(onEvent, Event.Finished(out.toString()))
                    -2 -> post(onEvent, Event.Failed("This chat is too long for the model. Start a new chat to keep going."))
                    else -> post(onEvent, Event.Failed("The model stopped unexpectedly (code $code)."))
                }
            } catch (e: Throwable) {
                post(onEvent, Event.Failed("The model stopped unexpectedly: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                main.postDelayed(unload, IDLE_UNLOAD_MS)
            }
        }
    }

    private fun post(onEvent: (Event) -> Unit, event: Event) = main.post { onEvent(event) }

    /** On the worker thread. */
    private fun ensureLoaded(file: File): Boolean {
        if (handle != 0L && loadedPath == file.path) return true
        release()
        if (!file.exists()) return false
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { if (it > 4) 4 else it }
        handle = LlamaEngine.nativeLoad(file.path, CONTEXT, threads)
        loadedPath = if (handle != 0L) file.path else null
        return handle != 0L
    }

    /** On the worker thread. */
    private fun release() {
        if (handle != 0L) LlamaEngine.nativeFree(handle)
        handle = 0L
        loadedPath = null
    }

    /** Before the model file is deleted or replaced. */
    fun unloadNow() {
        main.removeCallbacks(unload)
        worker.execute { release() }
    }
}
