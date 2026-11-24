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
 * Every word here is read by the phone's CPU before each answer can start, so it's kept
 * short and, above all, identical from one request to the next: [LocalModel] reads it once and
 * reuses it. That's why the system message carries only the date (it changes once a day), not
 * the time ("what time is it?" is answered by a tool anyway).
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
                "send_sms", "Send a text message. The user confirms before it is sent.",
                listOf(
                    Param("contact", ParamType.STRING, "Contact name or number."),
                    Param("message", ParamType.STRING, "The message."),
                ),
            ),
        )
    }

    val tools: List<String> = specs.map(ToolSchemas::toolJson)

    fun system(today: LocalDate = LocalDate.now()): String {
        val date = today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH))
        return "You are Shuttle, the assistant on this Android phone. You run entirely on the phone. " +
            "Answer in one to three short sentences unless asked for more. " +
            "Use a function when the user wants something done on the phone, and never claim an action happened unless you called one. " +
            "You can't access the internet, so you can't look up news, weather, prices, scores or other current facts; say so briefly instead of guessing. " +
            "Today is $date."
    }

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
