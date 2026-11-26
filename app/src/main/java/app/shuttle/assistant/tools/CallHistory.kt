package app.shuttle.assistant.tools

import android.content.Context
import android.provider.CallLog
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.CONFIRMED
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Call history: "who called?", "any missed calls?", "show my recent calls", and "call them
 * back", which calls the last person who rang (through the call tool, so it's confirmed
 * first). Reads Android's call log, which needs the Call logs permission (asked for the
 * first time it's needed).
 */
internal object CallHistory {

    val runners: Map<String, Runner> = mapOf("call_log" to ::log, "call_back" to ::callBack)

    private data class Call(val number: String, val name: String?, val type: Int, val date: Long) {
        val who get() = name?.takeIf { it.isNotBlank() } ?: number.ifBlank { "A private number" }
    }

    private fun recent(context: Context, max: Int = 30): List<Call> {
        val out = ArrayList<Call>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < max) out += Call(c.getString(0).orEmpty(), c.getString(1), c.getInt(2), c.getLong(3))
        }
        return out
    }

    private fun whenOf(context: Context, date: Long) =
        Reminders.describe(context, LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(date), ZoneId.systemDefault()))
            .let { if (it.startsWith("at ")) "today $it" else it }

    private fun log(context: Context, call: ToolCall): ToolResult {
        if (!context.hasAccess(Access.CALL_LOG)) return needs(Access.CALL_LOG, call, "To check your calls,")
        val calls = recent(context)
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        return if (call.arg("filter") == "recent") {
            if (calls.isEmpty()) return ToolResult.Done("No calls yet.")
            ToolResult.Done(
                "Recent calls:\n" + calls.take(8).joinToString("\n") { c ->
                    val kind = when (c.type) {
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.OUTGOING_TYPE -> "you called"
                        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "declined"
                        else -> "called you"
                    }
                    "- **${c.who}** ($kind), ${whenOf(context, c.date)}"
                },
            )
        } else {
            val missed = calls.filter { it.type == CallLog.Calls.MISSED_TYPE && it.date > weekAgo }
            if (missed.isEmpty()) return ToolResult.Done("No missed calls this week.")
            ToolResult.Done(
                (if (missed.size == 1) "You missed a call:\n" else "You missed ${missed.size} calls:\n") +
                    missed.take(8).joinToString("\n") { "- **${it.who}**, ${whenOf(context, it.date)}" },
            )
        }
    }

    private fun callBack(context: Context, call: ToolCall): ToolResult {
        if (!context.hasAccess(Access.CALL_LOG)) return needs(Access.CALL_LOG, call, "To see who called,")
        val last = recent(context).firstOrNull { it.type != CallLog.Calls.OUTGOING_TYPE && it.number.isNotBlank() }
            ?: return ToolResult.Failed("I can't find anyone to call back.")
        // The call tool confirms first ("Call Sam?"); a confirmation comes back here with the flag.
        return DeviceTools.run(context, ToolCall("call", mapOf("contact" to last.number) + call.args.filterKeys { it == CONFIRMED }))
    }
}
