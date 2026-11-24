package app.shuttle.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Listening and speaking through the phone's own speech services.
 *
 * Shuttle has no speech engine of its own. [SpeechRecognizer.createSpeechRecognizer] binds the
 * system's default recogniser and [TextToSpeech] the default TTS engine, so whatever the user
 * picked in Settings (Distillery, or any other on-device engine) does the work, and changing
 * it there changes it here. The recogniser does the recording; Shuttle still needs the
 * microphone permission because recognisers only accept callers that have it.
 *
 * Everything here runs on the main thread: SpeechRecognizer requires it, and TTS progress
 * callbacks (which arrive on a binder thread) are posted back to it. One instance lives as
 * long as the assistant panel; [close] releases both engines.
 */
class VoiceSession(private val context: Context) {

    sealed interface State {
        data object Idle : State
        /** Listening; [heard] is the recogniser's running guess, empty until it has one. */
        data class Listening(val heard: String = "") : State
        data object Speaking : State
    }

    var state: State by mutableStateOf(State.Idle)
        private set

    /** False when no speech recogniser is installed or enabled. */
    val canListen: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    private val main = Handler(Looper.getMainLooper())

    // --- Listening --------------------------------------------------------------------------

    private var recognizer: SpeechRecognizer? = null
    private var onHeard: ((String) -> Unit)? = null
    private var onMissed: ((String) -> Unit)? = null

    /**
     * Listens for one utterance. [heard] gets the final text; [missed] gets a short reason when
     * nothing usable came back (silence, no match, recogniser error).
     */
    fun listen(heard: (String) -> Unit, missed: (String) -> Unit) {
        stopSpeaking()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        onHeard = heard
        onMissed = missed
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        state = State.Listening()
        r.startListening(intent)
    }

    /** Stops listening and drops whatever was heard so far. */
    fun cancelListening() {
        if (state is State.Listening) {
            recognizer?.cancel()
            state = State.Idle
        }
        onHeard = null
        onMissed = null
    }

    /** Stops listening but keeps what was heard (the recogniser delivers it as the result). */
    fun finishListening() {
        if (state is State.Listening) recognizer?.stopListening()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank() && state is State.Listening) state = State.Listening(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            val heard = onHeard
            val missed = onMissed
            onHeard = null
            onMissed = null
            state = State.Idle
            if (text.isNullOrBlank()) missed?.invoke("I didn't catch that.") else heard?.invoke(text)
        }

        override fun onError(error: Int) {
            val missed = onMissed
            onHeard = null
            onMissed = null
            state = State.Idle
            missed?.invoke(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Shuttle needs the microphone permission."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recogniser is busy. Try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "The speech recogniser needs a connection it doesn't have."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "The speech recogniser doesn't support this language."
                    else -> "The speech recogniser stopped (error $error)."
                },
            )
        }
    }

    // --- Speaking ---------------------------------------------------------------------------

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    /** Text asked for before the engine finished starting. */
    private var pending: Pair<String, () -> Unit>? = null
    private val afterUtterance = mutableMapOf<String, () -> Unit>()
    private var nextUtterance = 0

    private fun engine(): TextToSpeech? {
        if (ttsFailed) return null
        return tts ?: TextToSpeech(context) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    pending?.let { (text, then) -> pending = null; speak(text, then) }
                } else {
                    ttsFailed = true
                    pending?.second?.invoke()
                    pending = null
                    state = State.Idle
                }
            }
        }.also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) = finished(utteranceId)
                @Deprecated("Required override")
                override fun onError(utteranceId: String) = finished(utteranceId)
                override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)
                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    main.post { afterUtterance.remove(utteranceId) }
                }
            })
            tts = engine
        }
    }

    private fun finished(id: String) {
        main.post {
            val then = afterUtterance.remove(id) ?: return@post
            if (state == State.Speaking) state = State.Idle
            then()
        }
    }

    /**
     * Says [text] with the default TTS engine, then runs [then]. If there's no working engine,
     * [then] runs straight away so the conversation doesn't stall.
     */
    fun speak(text: String, then: () -> Unit = {}) {
        cancelListening()
        val engine = engine() ?: return then()
        if (!ttsReady) {
            pending = text to then
            state = State.Speaking
            return
        }
        val id = "shuttle-${nextUtterance++}"
        afterUtterance[id] = then
        state = State.Speaking
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            afterUtterance.remove(id)
            state = State.Idle
            then()
        }
    }

    fun stopSpeaking() {
        pending = null
        afterUtterance.clear()
        if (state == State.Speaking) {
            tts?.stop()
            state = State.Idle
        }
    }

    /** Releases the recogniser and TTS engine. */
    fun close() {
        cancelListening()
        stopSpeaking()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }
}
