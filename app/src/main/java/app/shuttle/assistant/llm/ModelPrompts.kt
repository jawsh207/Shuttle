package app.shuttle.assistant.llm

import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolSchemas
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the model is told, and what it's allowed to ask for.
 *
 * The system message sets the model's job: brief answers, use the phone's tools for actions,
 * and say plainly that it can't look things up online yet rather than guessing at current
 * events. The tools are exactly the ones in [ToolSchemas], the same list the rule parser
 * uses.
 *
 * [sanitize] is the safety check on everything the model asks for: unknown tools are dropped,
 * and only arguments the tool declares are passed on. That keeps the model from setting the
 * internal "confirmed" flag, so a call or text it starts always stops at the confirmation card.
 */
object ModelPrompts {

    val tools: List<String> = ToolSchemas.all.map(ToolSchemas::toolJson)

    fun system(now: LocalDateTime = LocalDateTime.now()): String {
        val date = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH))
        val time = now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        return "You are Shuttle, the assistant built into this Android phone. You run entirely on the phone. " +
            "Keep answers short: one to three sentences unless the user asks for more. " +
            "When the user wants something done on the phone, call one of the functions. Never claim an action happened unless you called a function for it. " +
            "You can't access the internet yet, so you can't look up news, weather, prices, scores or anything else current. " +
            "If asked, say so briefly instead of guessing. " +
            "Today is $date and the time is $time."
    }

    /** The call with only declared arguments, or null for a tool that doesn't exist. */
    fun sanitize(call: ToolCall): ToolCall? {
        val spec = ToolSchemas.find(call.name) ?: return null
        val allowed = spec.params.map { it.name }.toSet()
        val args = call.args.filterKeys { it in allowed }.filterValues { it.isNotBlank() }
        return ToolCall(call.name, args)
    }
}
