package app.shuttle.assistant.llm

import android.os.Handler
import android.view.Choreographer
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * The downloaded model, ready to answer: loads it on first use, runs one reply at a time on a
 * background thread, and unloads it after a few idle minutes to give the memory back.
 *
 * Reading a prompt is the slow part on a phone's CPU, far slower than writing the reply, and
 * every prompt starts with the same instructions and tool list. That shared start is read only
 * once per model: afterwards its state is saved to a file ([configure]'s folder), and every
 * later load restores it in a moment instead of reading it again. [prepare] does this as soon
 * as the assistant opens (just after its first frame), so a request only has to read the new
 * words. The model holds about 3 GB while loaded and is let go after ten quiet minutes. The context is capped at 8K tokens, plenty for an assistant and far less memory
 * than the model's full 128K.
 */
object LocalModel {

    private const val CONTEXT = 8192
    private const val IDLE_UNLOAD_MS = 10 * 60 * 1000L

    sealed interface Event {
        /** What's happening before the first words: "Loading the model…", "Thinking…". */
        data class Status(val text: String) : Event
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

    @Volatile private var prefix: String = ""
    @Volatile private var stateDir: File? = null

    /** The prompt start every request shares, and where its saved state is kept. */
    fun configure(prefix: String, stateDir: File) {
        this.prefix = prefix
        this.stateDir = stateDir
    }

    /** One file per model and instructions; a change to either reads them afresh. */
    private fun stateFile(model: File): File? {
        val dir = stateDir ?: return null
        if (prefix.isEmpty()) return null
        val key = Integer.toHexString(prefix.hashCode()) + "-" + prefix.length
        return File(dir, "${model.nameWithoutExtension}-$key.state")
    }

    /** Stops the reply being written straight away, even while it's still reading. */
    fun cancel() {
        cancelled = true
        handle.takeIf { it != 0L }?.let { LlamaEngine.nativeCancel(it) }
    }

    /**
     * Loads the model and gets the shared prompt start ready in the background: restored from
     * its saved state, or read and then saved the first time. Waits behind a running reply.
     */
    fun prepare(file: File) {
        main.removeCallbacks(unload)
        worker.execute {
            try {
                if (!ensureLoaded(file) || prefix.isEmpty()) return@execute
                // Cheap when the state was restored: everything but the last token is reused.
                val code = LlamaEngine.nativeGenerate(handle, prefix, 0, 0f) { false }
                val state = stateFile(file)
                if (code == 0 && state != null && !state.exists()) {
                    state.parentFile?.mkdirs()
                    // Only one saved state is worth keeping.
                    state.parentFile?.listFiles { f -> f.name.endsWith(".state") }?.forEach { it.delete() }
                    if (!LlamaEngine.nativeSaveState(handle, state.path)) state.delete()
                }
            } catch (_: Throwable) {
                // A failed warm-up just means the first request does the work instead.
            } finally {
                main.postDelayed(unload, IDLE_UNLOAD_MS)
            }
        }
    }

    /**
     * [prepare], once the screen has drawn its first frame. Loading 2 GB and restoring the
     * saved instructions keeps the CPU and storage busy for a moment, so starting it in the
     * same instant as the screen would slow the screen down; a question asked before then
     * simply waits for it.
     */
    fun prepareAfterFirstFrame(file: File) {
        Choreographer.getInstance().postFrameCallback { main.post { prepare(file) } }
    }

    /**
     * Writes a reply to [prompt] with the model in [file]. [onEvent] is called on the main
     * thread with the growing raw text, then once with [Event.Finished] or [Event.Failed].
     * [shouldStop] can end it early once it has seen enough (it gets the raw text so far).
     */
    fun generate(
        file: File,
        prompt: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.3f,
        shouldStop: (String) -> Boolean = { false },
        onEvent: (Event) -> Unit,
    ) {
        main.removeCallbacks(unload)
        cancelled = false
        worker.execute {
            try {
                if (cancelled) {
                    post(onEvent, Event.Finished(""))
                    return@execute
                }
                if (handle == 0L || loadedPath != file.path) post(onEvent, Event.Status("Loading the model…"))
                if (!ensureLoaded(file)) {
                    post(onEvent, Event.Failed("The model couldn't be loaded. Try deleting and downloading it again in Settings."))
                    return@execute
                }
                post(onEvent, Event.Status("Thinking…"))
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
                    0, -4 -> post(onEvent, Event.Finished(out.toString()))
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
        if (handle == 0L) return false
        // Restore the instructions already read on an earlier run; a file from another model or
        // llama.cpp version simply doesn't load and is replaced on the next prepare.
        stateFile(file)?.takeIf { it.exists() }?.let { state ->
            if (LlamaEngine.nativeLoadState(handle, state.path) == 0) state.delete()
        }
        return true
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
