package app.shuttle.assistant.llm

/**
 * The JNI entry points into the native library (src/main/cpp/shuttle_llm.cpp, built on
 * llama.cpp). Nothing else in the app calls these directly; [LocalModel] wraps them with
 * loading, threading and idle unloading.
 *
 * The names here must match the C++ function names, so R8 keeps them (proguard-rules.pro).
 */
object LlamaEngine {

    init {
        System.loadLibrary("shuttle_llm")
    }

    /** Called by native code with each chunk of complete UTF-8 text; return false to stop. */
    fun interface PieceSink {
        fun onPiece(bytes: ByteArray): Boolean
    }

    /** Loads a GGUF model; returns a handle, or 0 if it couldn't be loaded. */
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long

    /**
     * Streams a reply to [prompt]; with [maxTokens] 0 it only reads the prompt (warm-up).
     * Returns 0 when finished, -4 when stopped by [nativeCancel]; -1 if the prompt couldn't be
     * tokenized, -2 if it doesn't fit the context, -3 if the model failed.
     */
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, sink: PieceSink): Int

    /** Stops a running [nativeGenerate] as soon as possible, even mid-prompt. Any thread. */
    external fun nativeCancel(handle: Long)

    external fun nativeFree(handle: Long)
}
