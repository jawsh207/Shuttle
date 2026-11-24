package app.shuttle.assistant.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.Replies
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.tools.hasAccess
import app.shuttle.assistant.voice.VoiceSession

/**
 * The spoken side of a conversation: listen, run what was heard, say the answer, and for a
 * call or text, ask "send it?" and listen for yes or no.
 *
 * Typed and tapped requests go through here too, so there's one path for running a request
 * and deciding what happens next; only requests that were spoken get spoken answers. A
 * spoken answer to a confirmation that isn't a clear yes or no (see [Replies]) is treated as
 * a new request and the card stays for tapping, so nothing is sent on a guess.
 */
class VoiceConversation(
    private val context: Context,
    private val vm: AssistantViewModel,
    private val voice: VoiceSession?,
    private val speakReplies: () -> Boolean,
    private val onClose: () -> Unit,
) {
    /** Asks for the microphone permission; set by the panel, which owns the launcher. */
    var requestMic: () -> Unit = {}

    /** A short note shown in the input box, e.g. "I didn't catch that." */
    var hint: String? by mutableStateOf(null)

    val canListen: Boolean get() = voice?.canListen == true

    /** Mic button / listen on open. */
    fun startListening() {
        hint = null
        if (voice == null || !voice.canListen) {
            hint = "No speech recogniser is set up. Choose one in Shuttle's settings."
            return
        }
        if (!context.hasAccess(Access.MICROPHONE)) {
            requestMic()
            return
        }
        voice.listen(heard = { submit(it, spoken = true) }, missed = { hint = it })
    }

    fun submit(text: String, spoken: Boolean) {
        hint = null
        val from = vm.exchanges.size
        // A model reply finishes later, so what happens next waits for it.
        vm.submit(context, text) { close -> after(close, spoken, from) }
    }

    fun confirm(exchange: AssistantViewModel.Exchange) {
        val from = vm.exchanges.size
        after(vm.confirm(context, exchange), spoken = false, from)
    }

    fun retry(exchange: AssistantViewModel.Exchange) {
        val from = vm.exchanges.size
        after(vm.retry(context, exchange), spoken = false, from)
    }

    /**
     * Decide what follows the results added since index [from]: close, speak them, and listen
     * for a yes/no if the last one asks one.
     */
    private fun after(closePanel: Boolean, spoken: Boolean, from: Int) {
        if (closePanel) {
            // Another app or screen is opening; it's the answer.
            voice?.stopSpeaking()
            onClose()
            return
        }
        if (!spoken || voice == null) return
        val added = vm.exchanges.drop(from)
        val last = added.lastOrNull() ?: return
        val waitForAnswer = last.result is ToolResult.Confirm && !last.settled
        val next: () -> Unit = { if (waitForAnswer) listenForAnswer(last.id) }
        if (speakReplies()) voice.speak(added.joinToString(" ") { speechFor(it.result) }, next) else next()
    }

    private fun listenForAnswer(id: Int) {
        val v = voice ?: return
        if (!context.hasAccess(Access.MICROPHONE)) return
        v.listen(
            heard = { said ->
                val exchange = vm.exchanges.firstOrNull { it.id == id && !it.settled }
                when {
                    exchange == null -> submit(said, spoken = true)
                    else -> when (Replies.yesNo(said)) {
                        Replies.Answer.YES -> {
                            val from = vm.exchanges.size
                            after(vm.confirm(context, exchange), spoken = true, from)
                        }
                        Replies.Answer.NO -> {
                            val from = vm.exchanges.size
                            vm.cancel(exchange)
                            after(false, spoken = true, from)
                        }
                        null -> submit(said, spoken = true)
                    }
                }
            },
            // Silence leaves the card on screen to tap.
            missed = { hint = "Tap Send or Cancel, or tap the mic and say yes or no." },
        )
    }

    /** What to say for a result. Confirmations end in a question so a yes/no fits. */
    private fun speechFor(result: ToolResult): String = when (result) {
        is ToolResult.Confirm ->
            if (result.text.trimEnd().endsWith("?")) result.text
            else "${result.text.trimEnd('.', ' ')}. ${result.confirmLabel} it?"
        else -> result.text
    }
}
