package app.shuttle.assistant.engine

/**
 * Every tool Shuttle has, described as data, plus the JSON form a tool-calling model reads.
 *
 * The list here is the single source of truth for names and arguments: the rule parser emits
 * calls that match it, the device tools implement it, and [toJson] turns it into the
 * function-calling format used by most local models and by Needle's `--tools tools.json`
 * (an array of `{name, description, parameters}` with a JSON Schema object for parameters).
 * The JSON is written by hand so this file has no Android or library dependencies.
 */
object ToolSchemas {

    /** Settings the status tool can report on. */
    val STATUS_SETTINGS = listOf(
        "wifi", "bluetooth", "mobile_data", "nfc", "airplane_mode", "location", "hotspot", "dark_mode",
        "do_not_disturb", "ringer_mode", "flashlight", "auto_rotate", "auto_brightness", "brightness", "volume", "all",
    )

    private fun switch(name: String, what: String, note: String = "") =
        ToolSpec(name, "Turn $what on or off.$note", listOf(StateParam))

    val all: List<ToolSpec> = listOf(
        switch("flashlight", "the flashlight"),
        switch("wifi", "Wi-Fi", " Opens the Wi-Fi panel for the user to tap."),
        switch("bluetooth", "Bluetooth", " Asks the user to confirm."),
        switch("mobile_data", "mobile data", " Opens the internet panel for the user to tap."),
        switch("nfc", "NFC", " Opens the NFC panel for the user to tap."),
        switch("airplane_mode", "airplane mode", " Opens its settings page."),
        switch("location", "location services", " Opens its settings page."),
        switch("hotspot", "the Wi-Fi hotspot", " Opens its settings page."),
        switch("dark_mode", "dark theme", " Opens its settings page."),
        switch("do_not_disturb", "Do Not Disturb"),
        switch("auto_rotate", "auto-rotate"),
        switch("auto_brightness", "adaptive brightness"),
        ToolSpec(
            "ringer_mode", "Set the phone's ringer to normal, vibrate or silent.",
            listOf(Param("mode", ParamType.STRING, "Ringer mode.", options = listOf("normal", "vibrate", "silent"))),
        ),
        ToolSpec(
            "brightness", "Set the screen brightness.",
            listOf(Param("level", ParamType.STRING, "A percentage from 0 to 100, or up, down, max or min.")),
        ),
        ToolSpec(
            "volume", "Change the media volume.",
            listOf(
                Param("action", ParamType.STRING, "What to do.", options = listOf("up", "down", "set", "mute", "unmute")),
                Param("percent", ParamType.INTEGER, "Volume from 0 to 100, for action=set.", required = false),
            ),
        ),
        ToolSpec(
            "media", "Control whatever music or video is playing.",
            listOf(Param("action", ParamType.STRING, "What to do.", options = listOf("play", "pause", "next", "previous"))),
        ),
        ToolSpec(
            "call", "Phone a contact or number. The user confirms before it dials.",
            listOf(Param("contact", ParamType.STRING, "A contact name or a phone number.")),
        ),
        ToolSpec(
            "send_sms", "Send a text message. The user confirms before it is sent.",
            listOf(
                Param("contact", ParamType.STRING, "A contact name or a phone number.", required = false),
                Param("message", ParamType.STRING, "The message to send.", required = false),
                Param("text", ParamType.STRING, "Recipient and message together, when they could not be split.", required = false),
            ),
        ),
        ToolSpec(
            "set_alarm", "Set an alarm in the clock app.",
            listOf(
                Param("hour", ParamType.INTEGER, "Hour, 0 to 23."),
                Param("minute", ParamType.INTEGER, "Minute, 0 to 59.", required = false),
                Param("label", ParamType.STRING, "Alarm label.", required = false),
            ),
        ),
        ToolSpec(
            "set_timer", "Start a countdown timer in the clock app.",
            listOf(
                Param("seconds", ParamType.INTEGER, "Length of the timer in seconds."),
                Param("label", ParamType.STRING, "Timer label.", required = false),
            ),
        ),
        ToolSpec(
            "open_app", "Open an installed app by name.",
            listOf(Param("name", ParamType.STRING, "The app's name as shown in the launcher.")),
        ),
        ToolSpec(
            "navigate", "Get directions to a place in the maps app.",
            listOf(Param("destination", ParamType.STRING, "Place name or address.")),
        ),
        ToolSpec(
            "quick_setting",
            "Turn a Quick Settings tile on or off, or tap it, by the name shown on it (battery saver, night light, " +
                "extra dim, data saver, screen record…). Needs Quick Settings control turned on.",
            listOf(Param("name", ParamType.STRING, "The tile's name."), StateParam),
        ),
        ToolSpec("battery_status", "Report the battery level and whether the phone is charging."),
        ToolSpec(
            "web_search", "Search the web and read the top results.",
            listOf(Param("query", ParamType.STRING, "What to search for.")),
        ),
        ToolSpec(
            "status", "Tell whether a setting is on, or its current level. Offers to switch it.",
            listOf(Param("setting", ParamType.STRING, "Which setting, or all for a summary.", options = STATUS_SETTINGS)),
        ),
        ToolSpec(
            "date_time", "Tell the current time or date.",
            listOf(Param("what", ParamType.STRING, "Which one.", options = listOf("time", "date"))),
        ),
    )

    fun find(name: String): ToolSpec? = all.firstOrNull { it.name == name }

    /** The tool list as a JSON array in the common function-calling format. */
    fun toJson(specs: List<ToolSpec> = all): String =
        specs.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]\n") { "  " + toolJson(it) }

    /** One tool as a single-line JSON object: `{"name": …, "description": …, "parameters": …}`. */
    fun toolJson(spec: ToolSpec): String = buildString {
        append("{\"name\": ").append(quote(spec.name))
        append(", \"description\": ").append(quote(spec.description))
        append(", \"parameters\": {\"type\": \"object\", \"properties\": {")
        spec.params.forEachIndexed { j, p ->
            if (j > 0) append(", ")
            append(quote(p.name)).append(": {\"type\": ").append(quote(p.type.json))
            append(", \"description\": ").append(quote(p.description))
            if (p.options.isNotEmpty()) {
                append(", \"enum\": [").append(p.options.joinToString(", ") { quote(it) }).append("]")
            }
            append("}")
        }
        append("}, \"required\": [")
        append(spec.params.filter { it.required }.joinToString(", ") { quote(it.name) })
        append("]}}")
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
