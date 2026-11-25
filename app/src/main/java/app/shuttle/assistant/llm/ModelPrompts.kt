package app.shuttle.assistant.llm

import app.shuttle.assistant.engine.Param
import app.shuttle.assistant.engine.ParamType
import app.shuttle.assistant.engine.StateParam
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolSchemas
import app.shuttle.assistant.engine.ToolSpec
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the model is told, and what it's allowed to ask for.
 *
 * The model is a general assistant first (questions, recipes, explanations) and a phone
 * controller second; small models otherwise decide everything is a phone request. Every word
 * here is read by the phone's CPU before answers can start, so it's kept short and, above all,
 * identical forever: [LocalModel] reads it once per model and saves the result. The date,
 * which changes, goes separately in [note]; the time isn't sent at all ("what time is it?" is
 * answered by a tool).
 *
 * The model sees a compact version of the phone's tools ([tools]): the twelve on/off switches
 * are one `set_switch` tool and every status question is one `check_setting`, which roughly
 * halves what it has to read. [sanitize] turns those back into the real tools in
 * [ToolSchemas] (the same ones the rule parser uses), and is also the safety check on
 * everything the model asks for: unknown tools are dropped, and only arguments the real tool
 * declares are passed on. That keeps the model from setting the internal "confirmed" flag, so
 * a call or text it starts always stops at the confirmation card.
 */
object ModelPrompts {

    private val switches = listOf(
        "flashlight", "wifi", "bluetooth", "mobile_data", "nfc", "airplane_mode", "location",
        "hotspot", "dark_mode", "do_not_disturb", "auto_rotate", "auto_brightness",
    )

    /** Short, model-facing tool descriptions. Names other than the two below are real tools. */
    private val specs: List<ToolSpec> = buildList {
        add(
            ToolSpec(
                "set_switch", "Turn a phone setting on or off.",
                listOf(Param("setting", ParamType.STRING, "Which setting.", options = switches), StateParam),
            ),
        )
        add(
            ToolSpec(
                "check_setting", "Say whether a setting is on, or its current level.",
                listOf(Param("setting", ParamType.STRING, "Which setting, or all.", options = ToolSchemas.STATUS_SETTINGS)),
            ),
        )
        val keep = listOf(
            "ringer_mode", "brightness", "volume", "media", "call", "set_alarm", "set_timer",
            "open_app", "navigate", "battery_status", "date_time",
        )
        keep.forEach { name -> add(ToolSchemas.find(name)!!) }
        add(
            ToolSpec(
                "web_search",
                "Search the web. Use it for anything current or that you're unsure of: news, who holds a position now, " +
                    "recent events, prices, schedules, releases, or facts that may have changed.",
                listOf(Param("query", ParamType.STRING, "A short search query.")),
            ),
        )
        add(
            ToolSpec(
                "send_sms", "Send a text message. The user confirms before it is sent.",
                listOf(
                    Param("contact", ParamType.STRING, "Contact name or number."),
                    Param("message", ParamType.STRING, "The message."),
                ),
            ),
        )
    }

    val tools: List<String> = specs.map(ToolSchemas::toolJson)

    fun system(): String =
        "You are Shuttle, a helpful general assistant running on the user's Android phone. " +
            "Answer any question from your own knowledge: facts, explanations, how-tos, recipes, advice, writing and maths. " +
            "Be concise, and use a short list for steps or ingredients. " +
            "Use the earlier conversation: treat short or vague messages as follow-ups to your previous answers, " +
            "and when asked to continue, expand or change an answer, build on what you said before instead of starting over. " +
            "You can also control the phone with the functions below; call one only when the user wants something done on the phone, " +
            "and never claim an action happened unless you called a function for it. " +
            "Your own knowledge may be out of date, so for anything current or likely to have changed " +
            "(who holds an office, news, recent events, prices, scores) call web_search first and answer from its results. " +
            "You don't know exactly when your training data ends, so never state a cutoff date or year; " +
            "if asked, say you can't tell exactly and that recent events may be missing."

    /** The date, sent after the shared start so the start never changes. */
    fun note(today: LocalDate = LocalDate.now()): String =
        "Today is " + today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)) + "."

    /** The prompt start every request shares, for [LocalModel.prepare]. */
    fun prefix(): String = GranitePrompt.systemBlock(system(), tools)

    /**
     * The real tool call for what the model asked, with only declared arguments; null for a
     * tool that doesn't exist.
     */
    fun sanitize(call: ToolCall): ToolCall? {
        val real = when (call.name) {
            "set_switch" -> {
                val setting = call.args["setting"]?.trim() ?: return null
                if (setting !in switches) return null
                ToolCall(setting, call.args - "setting")
            }
            "check_setting" -> ToolCall("status", call.args)
            else -> call
        }
        val spec = ToolSchemas.find(real.name) ?: return null
        val allowed = spec.params.map { it.name }.toSet()
        val args = real.args.filterKeys { it in allowed }.filterValues { it.isNotBlank() }
        return ToolCall(real.name, args)
    }
}
