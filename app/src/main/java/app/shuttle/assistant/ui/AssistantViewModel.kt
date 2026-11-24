package app.shuttle.assistant.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import app.shuttle.assistant.engine.CONFIRMED
import app.shuttle.assistant.engine.CommandParser
import app.shuttle.assistant.engine.SmallTalk
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.tools.DeviceTools

/**
 * The conversation in the assistant panel: what the user asked and what came back.
 *
 * Requests go to the rule parser and then to the device tools. Text the parser doesn't
 * recognise gets a short social reply when it's small talk ([SmallTalk]), otherwise a plain
 * "not yet" for now; that's where the on-device model plugs in.
 * Tools are run with the activity as context so they can open panels and other apps over it.
 */
class AssistantViewModel : ViewModel() {

    /**
     * One request and its result. [settled] once a confirm card has been answered. [asked] is
     * empty for the second and later results of a multi-part request.
     */
    data class Exchange(val id: Int, val asked: String, val result: ToolResult, val settled: Boolean = false)

    val exchanges = mutableStateListOf<Exchange>()
    private var nextId = 0

    /**
     * True while the user is away in Settings granting a special access, so the assistant
     * panel stays open for them to come back and tap "Try again".
     */
    var awaitingSettings = false

    /**
     * Runs [text]; returns true when a result wants the panel closed. "Turn off wifi and
     * bluetooth" runs both, one card each; the first card carries what was asked.
     */
    fun submit(context: Context, text: String): Boolean {
        val asked = text.trim()
        if (asked.isEmpty()) return false
        val calls = CommandParser.parseAll(asked)
        if (calls.isEmpty()) {
            val chat = SmallTalk.reply(asked)
            val result = if (chat != null) {
                ToolResult.Done(chat.text, closePanel = chat.close)
            } else {
                ToolResult.Failed(
                    "I can't do that yet. Right now I handle phone commands like \"flashlight on\", " +
                        "\"call Mom\" or \"timer 5 minutes\". Ask \"what can you do?\" for more.",
                )
            }
            exchanges += Exchange(nextId++, asked, result)
            return (result as? ToolResult.Done)?.closePanel == true
        }
        var close = false
        calls.forEachIndexed { i, call ->
            val result = DeviceTools.run(context, call)
            exchanges += Exchange(nextId++, if (i == 0) asked else "", result)
            if ((result as? ToolResult.Done)?.closePanel == true) close = true
        }
        return close
    }

    /** The user tapped Call/Send on a confirm card. */
    fun confirm(context: Context, exchange: Exchange): Boolean {
        val pending = exchange.result as? ToolResult.Confirm ?: return false
        settle(exchange)
        return rerun(context, pending.call.with(CONFIRMED, "true"))
    }

    fun cancel(exchange: Exchange) {
        settle(exchange)
        exchanges += Exchange(nextId++, "", ToolResult.Done("Cancelled."), settled = true)
    }

    /** After the user granted an access, run the blocked call again. */
    fun retry(context: Context, exchange: Exchange): Boolean {
        val blocked = exchange.result as? ToolResult.NeedsAccess ?: return false
        awaitingSettings = false
        settle(exchange)
        return rerun(context, blocked.retry)
    }

    /** A follow-up result for the same request, so no new "asked" bubble. */
    private fun rerun(context: Context, call: ToolCall): Boolean {
        val result = DeviceTools.run(context, call)
        exchanges += Exchange(nextId++, "", result)
        return (result as? ToolResult.Done)?.closePanel == true
    }

    private fun settle(exchange: Exchange) {
        val i = exchanges.indexOfFirst { it.id == exchange.id }
        if (i >= 0) exchanges[i] = exchanges[i].copy(settled = true)
    }
}
