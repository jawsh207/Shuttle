package app.shuttle.assistant.engine

/**
 * The fast path: turns common phrasings into a [ToolCall] with plain pattern rules, no model.
 *
 * It answers instantly, works before any model is downloaded, and behaves the same way every
 * time, so the things people say most ("flashlight on", "call Mom", "timer 5 minutes") never
 * depend on a model getting them right. Anything it doesn't recognise returns null and goes to
 * the model (once there is one).
 *
 * Rules are tried in order and each must match the whole command, so "start a timer for 5
 * minutes" is a timer, not "open app 'a timer…'", and "turn on wifi hotspot" is the hotspot,
 * not Wi-Fi. Names and message text keep the user's capitalisation.
 */
object CommandParser {

    private val IC = setOf(RegexOption.IGNORE_CASE)

    private class Rule(pattern: String, val build: (MatchResult) -> ToolCall?) {
        val regex = Regex(pattern, IC)
    }

    fun parse(input: String): ToolCall? {
        val text = normalize(input)
        if (text.isEmpty()) return null
        for (rule in rules) {
            val m = rule.regex.matchEntire(text) ?: continue
            rule.build(m)?.let { return it }
        }
        return null
    }

    // --- Clean-up -------------------------------------------------------------------------

    private val leadingFillers = Regex(
        "^(?:(?:hey|ok|okay|hi)\\s+shuttle|shuttle|please|can you|could you|would you|will you|" +
            "i want to|i'd like to|i would like to|i need to|i need you to|go ahead and|just)[,\\s]+",
        IC,
    )
    private val trailingFillers = Regex("[,\\s]+(?:please|for me|now|thanks|thank you)$", IC)

    /** Trims punctuation and polite filler, and spells feature names one way ("wifi"). */
    fun normalize(input: String): String {
        var s = input.trim()
            .replace('’', '\'')
            .replace(Regex("\\bwi[\\s-]?fi\\b", IC), "wifi")
            .replace(Regex("\\bflash\\s+light\\b", IC), "flashlight")
            .replace(Regex("\\bblue\\s+tooth\\b", IC), "bluetooth")
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', '!', '?', ' ')
        while (true) {
            val next = s.replace(leadingFillers, "").replace(trailingFillers, "").trimEnd('.', '!', '?', ' ')
            if (next == s) break
            s = next
        }
        return s.trim()
    }

    // --- Rules ----------------------------------------------------------------------------

    private fun state(word: String): String = when (word.lowercase()) {
        "on", "enable", "activate", "start" -> "on"
        "off", "disable", "deactivate", "stop" -> "off"
        else -> "toggle"
    }

    /** All the usual ways to switch a feature: "turn on X", "X off", "enable X", "toggle X". */
    private fun switchRules(tool: String, aliases: String): List<Rule> {
        val a = "(?:the\\s+|my\\s+)?(?:$aliases)(?:\\s+mode)?"
        return listOf(
            Rule("(?:turn|switch|put)\\s+(on|off)\\s+$a") { ToolCall(tool, mapOf("state" to state(it.groupValues[1]))) },
            Rule("(?:turn|switch|put)\\s+$a\\s+(on|off)") { ToolCall(tool, mapOf("state" to state(it.groupValues[1]))) },
            Rule("$a\\s+(on|off)") { ToolCall(tool, mapOf("state" to state(it.groupValues[1]))) },
            Rule("(enable|disable|activate|deactivate)\\s+$a") { ToolCall(tool, mapOf("state" to state(it.groupValues[1]))) },
            Rule("toggle\\s+$a") { ToolCall(tool, mapOf("state" to "toggle")) },
        )
    }

    private fun media(action: String) = ToolCall("media", mapOf("action" to action))
    private fun volume(action: String, percent: String? = null) =
        ToolCall("volume", buildMap { put("action", action); percent?.let { put("percent", it) } })

    private fun level(word: String): String = when (word.lowercase()) {
        "max", "maximum", "full", "all the way up" -> "max"
        "min", "minimum", "lowest" -> "min"
        "half" -> "50"
        else -> word
    }

    private fun alarm(timeText: String): ToolCall? {
        val clock = Quantities.parseClock(timeText) ?: return null
        return ToolCall(
            "set_alarm",
            buildMap {
                put("hour", clock.hour.toString())
                put("minute", clock.minute.toString())
                if (!clock.meridiemGiven) put("ambiguous", "true")
            },
        )
    }

    private fun timer(durationText: String): ToolCall? =
        Quantities.parseDuration(durationText)?.let { ToolCall("set_timer", mapOf("seconds" to it.toString())) }

    private fun sms(to: String, body: String) =
        ToolCall("send_sms", mapOf("contact" to to.trim(), "message" to body.trim()))

    private val target = "(?:the\\s+|my\\s+)?"
    private val musicWords = "(?:\\s+(?:the\\s+)?(?:music|song|track|playback|audio|media|video|podcast))?"
    private val numberOrPercent = "(\\d{1,3})\\s*(?:%|percent)?"

    private val rules: List<Rule> = buildList {
        // Hotspot before Wi-Fi so "wifi hotspot" is the hotspot.
        addAll(switchRules("hotspot", "hotspot|wifi hotspot|mobile hotspot|tethering"))
        addAll(switchRules("flashlight", "flashlight|torch|flash"))
        addAll(switchRules("wifi", "wifi|wireless"))
        addAll(switchRules("bluetooth", "bluetooth|bt"))
        addAll(switchRules("mobile_data", "mobile data|cellular data|cellular|data|mobile network"))
        addAll(switchRules("nfc", "nfc"))
        addAll(switchRules("airplane_mode", "airplane|aeroplane|flight"))
        addAll(switchRules("location", "location|location services|gps"))
        addAll(switchRules("dark_mode", "dark|dark theme|night"))
        addAll(switchRules("do_not_disturb", "do not disturb|dnd"))
        addAll(switchRules("auto_rotate", "auto[- ]?rotate|auto[- ]?rotation|screen rotation|rotation"))
        addAll(switchRules("auto_brightness", "auto[- ]?brightness|adaptive brightness"))

        // Ringer
        val silent = ToolCall("ringer_mode", mapOf("mode" to "silent"))
        val vibrate = ToolCall("ringer_mode", mapOf("mode" to "vibrate"))
        val normal = ToolCall("ringer_mode", mapOf("mode" to "normal"))
        add(Rule("(?:silence|mute)\\s+$target(?:phone|ringer|ringtone)") { silent })
        add(Rule("(?:silent|silent mode)(?:\\s+on)?") { silent })
        add(Rule("(?:turn on|switch to|enable|go|go to|put\\s+${target}phone\\s+(?:on|in|into|to))\\s+silent(?:\\s+mode)?") { silent })
        add(Rule("(?:vibrate|vibration)(?:\\s+mode)?(?:\\s+on)?") { vibrate })
        add(Rule("(?:turn on|switch to|enable|go to|put\\s+${target}phone\\s+(?:on|in|into|to))\\s+vibrat(?:e|ion)(?:\\s+mode)?") { vibrate })
        add(Rule("(?:unmute|unsilence)\\s+$target(?:phone|ringer)") { normal })
        add(Rule("(?:ringer|sound|ring)\\s+on") { normal })
        add(Rule("(?:silent|silent mode|vibrate|vibrate mode)\\s+off") { normal })
        add(Rule("(?:turn off|disable)\\s+(?:silent|vibrate)(?:\\s+mode)?") { normal })

        // Brightness
        add(Rule("(?:set\\s+)?(?:the\\s+)?(?:screen\\s+)?brightness\\s+(?:to\\s+)?$numberOrPercent") {
            ToolCall("brightness", mapOf("level" to it.groupValues[1]))
        })
        add(Rule("(?:set|change|put|turn)\\s+(?:the\\s+)?(?:screen\\s+)?brightness\\s+(?:to|all the way)\\s+(max|maximum|min|minimum|full|half|up|down|lowest)") {
            ToolCall("brightness", mapOf("level" to level(it.groupValues[1])))
        })
        add(Rule("(?:turn\\s+)?(?:the\\s+)?(?:screen\\s+)?brightness\\s+(up|down|max|maximum|min|minimum|full)") {
            ToolCall("brightness", mapOf("level" to level(it.groupValues[1])))
        })
        add(Rule("(?:turn|crank)\\s+(up|down)\\s+(?:the\\s+)?(?:screen\\s+)?brightness") {
            ToolCall("brightness", mapOf("level" to it.groupValues[1].lowercase()))
        })
        add(Rule("(?:increase|raise)\\s+(?:the\\s+)?(?:screen\\s+)?brightness|brighter|make (?:the|my) screen brighter") {
            ToolCall("brightness", mapOf("level" to "up"))
        })
        add(Rule("(?:decrease|lower|reduce)\\s+(?:the\\s+)?(?:screen\\s+)?brightness|dim\\s+(?:the\\s+|my\\s+)?screen|dimmer|make (?:the|my) screen dimmer") {
            ToolCall("brightness", mapOf("level" to "down"))
        })

        // Volume (media)
        add(Rule("(?:set\\s+)?(?:the\\s+)?(?:media\\s+|music\\s+)?volume\\s+(?:to\\s+)?$numberOrPercent") { volume("set", it.groupValues[1]) })
        add(Rule("(?:set|change|put|turn)\\s+(?:the\\s+)?(?:media\\s+|music\\s+)?volume\\s+to\\s+(max|maximum|full|half)") {
            volume("set", if (it.groupValues[1].lowercase() == "half") "50" else "100")
        })
        add(Rule("(?:turn\\s+)?(?:the\\s+)?(?:media\\s+|music\\s+)?volume\\s+(up|down)") { volume(it.groupValues[1].lowercase()) })
        add(Rule("turn\\s+it\\s+(up|down)") { volume(it.groupValues[1].lowercase()) })
        add(Rule("(?:turn|crank)\\s+(up|down)\\s+(?:the\\s+)?(?:volume|music|sound)") { volume(it.groupValues[1].lowercase()) })
        add(Rule("(?:increase|raise)\\s+(?:the\\s+)?volume|louder") { volume("up") })
        add(Rule("(?:decrease|lower|reduce)\\s+(?:the\\s+)?volume|quieter|softer") { volume("down") })
        add(Rule("mute\\s+(?:the\\s+)?(?:music|media|volume|sound|audio)") { volume("mute") })
        add(Rule("unmute\\s+(?:the\\s+)?(?:music|media|volume|sound|audio)") { volume("unmute") })

        // Media
        add(Rule("(?:play|resume|continue|unpause)$musicWords") { media("play") })
        add(Rule("(?:pause|stop)$musicWords") { media("pause") })
        add(Rule("(?:next|skip)(?:\\s+(?:this\\s+|the\\s+)?(?:song|track))?|play\\s+(?:the\\s+)?next\\s+(?:song|track)") { media("next") })
        add(Rule("(?:previous|last)\\s+(?:song|track)|previous|play\\s+(?:the\\s+)?(?:previous|last)\\s+(?:song|track)|go back a (?:song|track)") { media("previous") })

        // Alarms and timers (before "open/start <app>")
        add(Rule("(?:set|create|make|add)\\s+(?:an?\\s+|my\\s+)?alarm\\s+(?:for|at)\\s+(.+)") { alarm(it.groupValues[1]) })
        add(Rule("(?:set\\s+)?(?:an?\\s+)?alarm\\s+(?:for\\s+|at\\s+)?(.+)") { alarm(it.groupValues[1]) })
        add(Rule("wake\\s+me(?:\\s+up)?\\s+(?:at|by)\\s+(.+)") { alarm(it.groupValues[1]) })
        add(Rule("(?:set|start|create|make|add)\\s+(?:an?\\s+)?timer\\s+(?:for\\s+)?(.+)") { timer(it.groupValues[1]) })
        add(Rule("timer\\s+(?:for\\s+)?(.+)") { timer(it.groupValues[1]) })
        add(Rule("(?:(?:set|start|create|make)\\s+)?(?:an?\\s+)?(.+?)\\s+timer") { timer(it.groupValues[1]) })

        // Calls
        add(Rule("(?:call|phone|dial|ring)\\s+(?!me\\b)(.+)") { ToolCall("call", mapOf("contact" to it.groupValues[1])) })
        add(Rule("give\\s+(.+?)\\s+a\\s+(?:call|ring)") { ToolCall("call", mapOf("contact" to it.groupValues[1])) })
        add(Rule("(?:make|place)\\s+a\\s+call\\s+to\\s+(.+)") { ToolCall("call", mapOf("contact" to it.groupValues[1])) })

        // Texts: split on "saying"/":" when present; otherwise the tool splits name from message.
        val textVerb = "(?:text|message|sms)"
        val sendText = "(?:send|write)\\s+(?:a\\s+)?(?:text message|text|message|sms)\\s+to"
        val saying = "(?:saying|that says|that|and say|and tell (?:him|her|them)(?: that)?)"
        add(Rule("$textVerb\\s+(.+?)\\s+$saying\\s+(.+)") { sms(it.groupValues[1], it.groupValues[2]) })
        add(Rule("$sendText\\s+(.+?)\\s+$saying\\s+(.+)") { sms(it.groupValues[1], it.groupValues[2]) })
        add(Rule("(?:$textVerb|$sendText)\\s+([^:,]+?)\\s*[:,]\\s*(.+)") { sms(it.groupValues[1], it.groupValues[2]) })
        add(Rule("(?:$textVerb|$sendText)\\s+(.+)") { ToolCall("send_sms", mapOf("text" to it.groupValues[1])) })
        add(Rule("tell\\s+(?!me\\b)(.+)") { ToolCall("send_sms", mapOf("text" to it.groupValues[1])) })

        // Directions
        add(Rule("(?:navigate|directions|get directions|give me directions|take me|drive me|walk me|route me)\\s+(?:me\\s+)?to\\s+(.+)") {
            ToolCall("navigate", mapOf("destination" to it.groupValues[1]))
        })
        add(Rule("(?:how do i get|how to get|how can i get)\\s+to\\s+(.+)") { ToolCall("navigate", mapOf("destination" to it.groupValues[1])) })

        // Status
        add(Rule("(?:what(?:'s| is)\\s+(?:my\\s+|the\\s+)?)?battery(?:\\s+(?:level|status|percentage|percent|life))?") { ToolCall("battery_status") })
        add(Rule("how much battery(?:\\s+(?:do i have|is|have i got))?(?:\\s+left)?") { ToolCall("battery_status") })
        add(Rule("(?:is\\s+my\\s+phone|am\\s+i|is\\s+it)\\s+charging") { ToolCall("battery_status") })
        add(Rule("(?:what(?:'s| is)\\s+the\\s+)?time(?:\\s+is\\s+it)?(?:\\s+now)?|what time is it(?:\\s+now)?") {
            ToolCall("date_time", mapOf("what" to "time"))
        })
        add(Rule("(?:what(?:'s| is)\\s+)?(?:the\\s+|today's\\s+)?date(?:\\s+today)?|what day is (?:it|today)|what(?:'s| is) today") {
            ToolCall("date_time", mapOf("what" to "date"))
        })

        // Open an app (last: "open"/"start" are also the first word of other commands).
        add(Rule("(?:open|launch|start|run)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?") {
            ToolCall("open_app", mapOf("name" to it.groupValues[1]))
        })
    }
}
