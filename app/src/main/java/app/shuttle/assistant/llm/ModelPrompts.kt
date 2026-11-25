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

    /**
     * Shorter versions of a few real tools for the model, to keep what it reads once small:
     * settings pages are named freely (the tool works out which page), not listed.
     */
    private val shorter: Map<String, ToolSpec> = mapOf(
        "open_settings" to ToolSpec(
            "open_settings", "Open a settings page (wifi, battery, display, apps, privacy...) or an app's App info.",
            listOf(
                Param("page", ParamType.STRING, "Which page.", required = false),
                Param("app", ParamType.STRING, "An app, for its App info.", required = false),
            ),
        ),
        "quick_setting" to ToolSpec(
            "quick_setting", "Turn a Quick Settings tile on or off by its name (battery saver, night light, data saver...).",
            listOf(Param("name", ParamType.STRING, "The tile's name."), StateParam),
        ),
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
            "ringer_mode", "brightness", "volume", "media", "quick_setting", "call", "set_alarm", "set_timer",
            "open_app", "navigate", "battery_status", "date_time",
            "weather", "set_reminder", "calendar_events", "add_event", "read_notifications", "reply_message",
            "phone_action", "open_settings", "camera", "contact_info", "send_email", "add_note",
        )
        keep.forEach { name -> add(shorter[name] ?: ToolSchemas.find(name)!!) }
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
            "Start with the answer itself, with no preamble and without repeating the question. " +
            "Match the length to the question: a sentence or two for simple ones, more only when it needs it. " +
            "Use Markdown sparingly: a numbered list for steps, bullets for ingredients or options, **bold** for key terms, " +
            "and a short heading only for longer answers. " +
            "If you aren't sure of something, say so rather than guessing. " +
            "The user is an adult asking in good faith: answer frankly and fully, including on sensitive or awkward subjects " +
            "such as health, medication and drugs, sex, law, money, security, history, politics, religion and dark or mature themes " +
            "in fiction. Give real, specific information instead of vague advice or only telling them to see a professional; " +
            "add a short note on risks or when to get help only where it truly matters. Don't lecture, moralise or add disclaimers. " +
            "For contested questions, lay out the main views fairly. " +
            "Decline only requests that would give serious help to hurting people, such as making weapons or dangerous chemicals, " +
            "attacking computer systems, or harming children; then say briefly that you can't help with that one, without a lecture. " +
            "Use the earlier conversation: treat short or vague messages as follow-ups to your previous answers, " +
            "and when asked to continue, expand or change an answer, build on what you said before instead of starting over. " +
            "You can also control the phone with the functions below; call one only when the user wants something done on the phone, " +
            "and never claim an action happened unless you called a function for it. " +
            "Your own knowledge may be out of date, so for anything current or likely to have changed " +
            "(who holds an office, news, recent events, prices, scores) call web_search first and answer from its results. " +
            "You don't know exactly when your training data ends, so never state a cutoff date or year; " +
            "if asked, say you can't tell exactly and that recent events may be missing."

    /**
     * The date and the chosen [Personality], sent after the shared start so the start never
     * changes (and so switching personality costs nothing).
     */
    fun note(today: LocalDate = LocalDate.now(), personality: Personality = Personality.BALANCED): String =
        "Today is " + today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)) + "." +
            if (personality.instruction.isEmpty()) "" else " Personality: " + personality.instruction

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
