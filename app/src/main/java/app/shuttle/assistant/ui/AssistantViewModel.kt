package app.shuttle.assistant.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import app.shuttle.assistant.engine.CONFIRMED
import app.shuttle.assistant.engine.CommandParser
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.tools.DeviceTools

/**
 * The conversation in the assistant panel: what the user asked and what came back.
 *
 * Requests go to the rule parser and then to the device tools. Text the parser doesn't
 * recognise gets a plain "not yet" for now; that's where the on-device model plugs in.
 * Tools are run with the activity as context so they can open panels and other apps over it.
 */
class AssistantViewModel : ViewModel() {

    /** One request and its result. [settled] once a confirm card has been answered. */
    data class Exchange(val id: Int, val asked: String, val result: ToolResult, val settled: Boolean = false)

    val exchanges = mutableStateListOf<Exchange>()
    private var nextId = 0

    /**
     * True while the user is away in Settings granting a special access, so the assistant
     * panel stays open for them to come back and tap "Try again".
     */
    var awaitingSettings = false

    /** Runs [text]; returns true when the result wants the panel closed. */
    fun submit(context: Context, text: String): Boolean {
        val asked = text.trim()
        if (asked.isEmpty()) return false
        val call = CommandParser.parse(asked)
        val result = if (call != null) {
            DeviceTools.run(context, call)
        } else {
            ToolResult.Failed(
                "I can't do that yet. Right now I handle phone commands like \"flashlight on\", " +
                    "\"call Mom\" or \"timer 5 minutes\". Chat and search arrive with the on-device model.",
            )
        }
        exchanges += Exchange(nextId++, asked, result)
        return (result as? ToolResult.Done)?.closePanel == true
    }

    /** The user tapped Call/Send on a confirm card. */
    fun confirm(context: Context, exchange: Exchange): Boolean {
        val pending = exchange.result as? ToolResult.Confirm ?: return false
        settle(exchange)
        return rerun(context, exchange.asked, pending.call.with(CONFIRMED, "true"))
    }

    fun cancel(exchange: Exchange) {
        settle(exchange)
        exchanges += Exchange(nextId++, exchange.asked, ToolResult.Done("Cancelled."), settled = true)
    }

    /** After the user granted an access, run the blocked call again. */
    fun retry(context: Context, exchange: Exchange): Boolean {
        val blocked = exchange.result as? ToolResult.NeedsAccess ?: return false
        awaitingSettings = false
        settle(exchange)
        return rerun(context, exchange.asked, blocked.retry)
    }

    private fun rerun(context: Context, asked: String, call: ToolCall): Boolean {
        val result = DeviceTools.run(context, call)
        exchanges += Exchange(nextId++, asked, result)
        return (result as? ToolResult.Done)?.closePanel == true
    }

    private fun settle(exchange: Exchange) {
        val i = exchanges.indexOfFirst { it.id == exchange.id }
        if (i >= 0) exchanges[i] = exchanges[i].copy(settled = true)
    }
}
