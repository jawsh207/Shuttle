package app.shuttle.assistant.engine

/**
 * The fast path: turns everyday phrasings into [ToolCall]s with plain pattern rules, no model.
 *
 * It answers instantly, works with nothing downloaded, and behaves the same way every time,
 * so the things people say most ("flashlight on", "call Mom", "wake me up at 7") never depend
 * on a model getting them right. People say the same thing many ways ("kill the wifi", "shut
 * off wifi", "wifi off"), and speech recognisers add "um"s and "please"s, so each command has
 * several patterns and polite or filler words are trimmed first. Anything it doesn't
 * recognise returns null and goes to the model (once there is one).
 *
 * Rules are tried in order and each must match the whole command, so "start a timer for 5
 * minutes" is a timer, not "open app 'a timer…'", and "turn on wifi hotspot" is the hotspot,
 * not Wi-Fi. Names and message text keep the user's capitalisation, and message rules see the
 * words exactly as said, so "text Sam saying thanks" doesn't lose its "thanks" to filler
 * trimming.
 *
 * [parseAll] also splits "turn off wifi and bluetooth" or "set an alarm for 7 and a timer for
 * 10 minutes" into one call each, but only when every part is understood.
 */
object CommandParser {

    private val IC = setOf(RegexOption.IGNORE_CASE)

    /** [verbatim] rules (texts) match the command with only leading filler removed. */
    private class Rule(pattern: String, val verbatim: Boolean = false, val build: (MatchResult) -> ToolCall?) {
        val regex = Regex(pattern, IC)
    }

    /** One command, or null if it isn't one the fast path knows. */
    fun parse(input: String): ToolCall? {
        val text = normalize(input)
        if (text.isEmpty()) return null
        val asSaid = trimLead(input)
        // Speech recognisers put commas where people pause ("set a timer for, five minutes").
        val noCommas = text.replace(Regex("\\s*,\\s*"), " ")
        for (rule in rules) {
            val m = (if (rule.verbatim) rule.regex.matchEntire(asSaid) else null)
                ?: rule.regex.matchEntire(text)
                ?: (if (noCommas != text) rule.regex.matchEntire(noCommas) else null)
                ?: continue
            rule.build(m)?.let { return it }
        }
        return null
    }

    private val joiners = Regex("\\s*,?\\s+(?:and then|and also|and|then|also|plus)\\s+|\\s*[,;]\\s*", IC)

    /**
     * One or more commands. A whole-sentence match always wins (so "text Sam saying hi and
     * bye" stays one text). Otherwise the sentence is split on "and"/"then"/commas, and a part
     * missing its verb borrows it from the part before ("turn off wifi and bluetooth"). If any
     * part isn't understood, nothing is returned, rather than doing only half of what was said.
     */
    fun parseAll(input: String): List<ToolCall> {
        parse(input)?.let { return listOf(it) }
        val parts = normalize(input).split(joiners).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2 || parts.size > 4) return emptyList()
        val calls = mutableListOf<ToolCall>()
        for (part in parts) {
            val call = parse(part) ?: calls.lastOrNull()?.let { borrowVerb(it, part) } ?: return emptyList()
            calls += call
        }
        return calls
    }

    private fun borrowVerb(previous: ToolCall, part: String): ToolCall? {
        previous.args["state"]?.let { state ->
            val verb = if (state == "toggle") "toggle" else "turn $state"
            return parse("$verb $part")
        }
        return parse("set $part")
    }

    // --- Clean-up -------------------------------------------------------------------------

    private val leadingFillers = Regex(
        "^(?:(?:hey|ok|okay|hi|yo)\\s+shuttle|shuttle|um+|uh+|erm|hmm+|so|okay so|ok so|alright|all right|" +
            "oh|hey|yo|actually|maybe|quickly|kindly|just|please|" +
            "can you please|could you please|would you please|will you please|please could you|" +
            "can you|could you|would you|will you|would you mind|do you mind|can i get you to|" +
            "i want you to|i'd like you to|i need you to|i wanna|i want to|i'd like to|i would like to|" +
            "i need to|i have to|i gotta|let's|lets|let me|go ahead and|try to|help me)[,\\s]+",
        IC,
    )
    private val trailingFillers = Regex(
        "[,\\s]+(?:please|for me|now|right now|thanks|thank you|thanks a lot|real quick|quickly|asap|" +
            "if you can|if you could|if you don't mind|would you|could you|will you|okay|ok|already)$",
        IC,
    )

    /** Only leading filler removed: how message rules see the command. */
    private fun trimLead(input: String): String {
        var s = input.trim().replace('’', '\'').replace(Regex("\\s+"), " ")
        while (true) {
            val next = s.replace(leadingFillers, "")
            if (next == s) return s
            s = next
        }
    }

    private const val NUMBER_WORD = "(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[\\s-](?:one|two|three|four|five|six|seven|eight|nine))?|" +
        "ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|one|two|three|four|five|six|seven|eight|nine|zero"

    /** "fifty percent" → "50 percent", for brightness and volume. "a hundred percent" too. */
    private val percentWords = Regex("\\b($NUMBER_WORD|(?:a|one) hundred)\\s+percent\\b", IC)

    /** Trims punctuation and polite filler, and spells feature names one way ("wifi"). */
    fun normalize(input: String): String {
        var s = input.trim()
            .replace('’', '\'')
            .replace(Regex("\\bwi[\\s-]?fi\\b", IC), "wifi")
            .replace(Regex("\\bflash\\s+light\\b", IC), "flashlight")
            .replace(Regex("\\bblue\\s+tooth\\b", IC), "bluetooth")
            .replace(Regex("\\bd\\s?n\\s?d\\b", IC), "dnd")
            .replace(Regex("\\bn\\s?f\\s?c\\b", IC), "nfc")
            .replace(Regex("\\bauto\\s+rotate\\b", IC), "auto-rotate")
            .replace(percentWords) { m -> Quantities.wordsToDigits(m.groupValues[1]).replace(" ", "") + " percent" }
            // Hesitations anywhere, and "like"/"you know" set off by commas.
            .replace(Regex(",?\\s*\\b(?:um+|uh+|uhm|erm)\\b,?", IC), " ")
            .replace(Regex(",\\s*(?:like|you know|i mean|sort of|kind of)\\s*,", IC), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', '!', '?', ' ')
        while (true) {
            val next = s.replace(leadingFillers, "").replace(trailingFillers, "").trimEnd('.', '!', '?', ',', ' ')
            if (next == s) break
            s = next
        }
        return s.trim()
    }

    // --- Building blocks ------------------------------------------------------------------

    /** "the", "my", "my phone's", "the phone's" before a feature name. */
    private const val ART = "(?:(?:the|my|your)\\s+)?(?:phone'?s\\s+)?"

    private const val ON_VERBS = "turn on|switch on|flip on|put on|power on|enable|activate|start|start up|fire up"
    private const val OFF_VERBS = "turn off|switch off|flip off|shut off|shut down|power off|kill|cut|cut off|disable|deactivate|stop|end"
    private const val SPLIT_VERBS = "turn|switch|flip|put|power|get|shut|set"

    private fun switchCall(tool: String, state: String) = ToolCall(tool, mapOf("state" to state))

    /** All the ways to switch a feature: "turn on X", "kill the X", "X off", "toggle X"… */
    private fun switchRules(tool: String, aliases: String, aloneToggles: Boolean = false): List<Rule> {
        val a = "$ART(?:$aliases)(?:\\s+mode)?"
        return buildList {
            add(Rule("(?:$ON_VERBS)\\s+$a") { switchCall(tool, "on") })
            add(Rule("(?:$OFF_VERBS)\\s+$a") { switchCall(tool, "off") })
            add(Rule("(?:$SPLIT_VERBS)\\s+$a\\s+(?:back\\s+)?on") { switchCall(tool, "on") })
            add(Rule("(?:$SPLIT_VERBS)\\s+$a\\s+(?:back\\s+)?(?:off|down)") { switchCall(tool, "off") })
            add(Rule("$a\\s+(?:back\\s+)?(on|off)") { switchCall(tool, it.groupValues[1].lowercase()) })
            add(Rule("(?:toggle|flip)\\s+$a") { switchCall(tool, "toggle") })
            if (aloneToggles) add(Rule(a) { switchCall(tool, "toggle") })
        }
    }

    private fun media(action: String) = ToolCall("media", mapOf("action" to action))
    private fun volume(action: String, percent: String? = null) =
        ToolCall("volume", buildMap { put("action", action); percent?.let { put("percent", it) } })
    private fun brightness(level: String) = ToolCall("brightness", mapOf("level" to level))
    private fun ringer(mode: String) = ToolCall("ringer_mode", mapOf("mode" to mode))

    private fun level(word: String): String = when (word.lowercase().trim()) {
        "max", "maximum", "full", "highest", "all the way up", "up all the way" -> "max"
        "min", "minimum", "lowest", "all the way down", "down all the way" -> "min"
        "half", "halfway", "medium", "middle" -> "50"
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

    /** A timer from a duration; a bare number ("timer for 5") means minutes. */
    private fun timer(durationText: String): ToolCall? {
        val seconds = Quantities.parseDuration(durationText)
            ?: Quantities.wordsToDigits(durationText.trim()).toIntOrNull()?.takeIf { it in 1..999 }?.let { it * 60 }
            ?: return null
        return ToolCall("set_timer", mapOf("seconds" to seconds.toString()))
    }

    /**
     * "my mom" → "mom", "Sam's cell" → "Sam", "Jo on her mobile" → "Jo", "Sam back" → "Sam":
     * contacts are saved by name, not by how people refer to them.
     */
    private fun person(raw: String): String = raw.trim()
        .replace(Regex("^(?:my|our)\\s+", IC), "")
        .replace(Regex("'s\\s+(?:cell|cell phone|mobile|phone|number|work phone|work|home phone|home)$", IC), "")
        .replace(Regex("\\s+(?:on|at)\\s+(?:his|her|their|the|my)\\s+(?:cell|cell phone|mobile|phone|work phone|work|home phone|home|number)$", IC), "")
        .replace(Regex("\\s+(?:back|up)$", IC), "")
        .trim()

    /** Name and message still together: only "my" comes off, the message is left alone. */
    private fun together(raw: String): String = raw.trim().replace(Regex("^(?:my|our)\\s+", IC), "")

    private fun callTo(raw: String): ToolCall? =
        person(raw).takeIf { it.isNotEmpty() }?.let { ToolCall("call", mapOf("contact" to it)) }

    private fun sms(to: String, body: String) =
        ToolCall("send_sms", mapOf("contact" to person(to), "message" to body.trim()))

    private const val MUSIC = "(?:music|song|track|playback|audio|media|video|podcast|it|this|that)"
    private const val PERCENT = "(\\d{1,3})\\s*(?:%|percent)?"

    // --- Rules ----------------------------------------------------------------------------

    private val rules: List<Rule> = buildList {
        // Hotspot before Wi-Fi so "wifi hotspot" is the hotspot.
        addAll(switchRules("hotspot", "hotspot|wifi hotspot|mobile hotspot|personal hotspot|tethering"))
        addAll(switchRules("flashlight", "flashlight|torch|flash|light|lights|torch light", aloneToggles = true))
        addAll(switchRules("wifi", "wifi|wireless|wlan|wifi connection"))
        addAll(switchRules("bluetooth", "bluetooth|bt"))
        addAll(switchRules("mobile_data", "mobile data|cellular data|cell data|cellular|data|mobile network|internet|mobile internet"))
        addAll(switchRules("nfc", "nfc|tap to pay|contactless"))
        addAll(switchRules("airplane_mode", "airplane|aeroplane|flight|plane"))
        addAll(switchRules("location", "location|location services|gps|location tracking"))
        addAll(switchRules("dark_mode", "dark|dark theme|night|dark display"))
        addAll(switchRules("do_not_disturb", "do not disturb|dnd|focus|quiet|quiet hours|focus time"))
        addAll(switchRules("auto_rotate", "auto-rotate|auto[- ]?rotation|screen rotation|rotation|rotate|screen rotate"))
        addAll(switchRules("auto_brightness", "auto[- ]?brightness|adaptive brightness|automatic brightness"))
        add(Rule("(?:put|set|switch)\\s+(?:my\\s+|the\\s+)?(?:phone|it)\\s+(?:in|into|on|to)\\s+(?:airplane|aeroplane|flight)\\s+mode") { switchCall("airplane_mode", "on") })
        add(Rule("(?:take|get)\\s+(?:my\\s+|the\\s+)?(?:phone|it)\\s+(?:off|out of)\\s+(?:airplane|aeroplane|flight)\\s+mode") { switchCall("airplane_mode", "off") })
        add(Rule("(?:i need|give me|i could use)\\s+(?:some\\s+|a\\s+)?light|(?:i\\s+)?can't see(?:\\s+anything)?\\s+in\\s+the\\s+dark") { switchCall("flashlight", "on") })
        add(Rule("(?:don't|do not)\\s+disturb\\s+me|(?:no|hold)\\s+(?:all\\s+)?(?:notifications|interruptions)") { switchCall("do_not_disturb", "on") })

        // Ringer. "mute it" is left to the media volume rules below.
        val phone = "(?:(?:my|the)\\s+)?(?:phone|ringer|ringtone|ring)"
        val phoneOrIt = "(?:(?:my|the)\\s+)?(?:phone|ringer|ringtone|ring|it)"
        add(Rule("(?:silence|mute|quiet)\\s+$phone") { ringer("silent") })
        add(Rule("(?:put|set|switch|turn|change)\\s+$phoneOrIt\\s+(?:on|to|in|into)\\s+silent(?:\\s+mode)?") { ringer("silent") })
        add(Rule("(?:go|switch|change)\\s+(?:to\\s+)?silent(?:\\s+mode)?|silent(?:\\s+mode)?(?:\\s+on)?|(?:turn|switch)\\s+on\\s+silent(?:\\s+mode)?|enable\\s+silent(?:\\s+mode)?") { ringer("silent") })
        add(Rule("(?:turn|switch|shut)\\s+off\\s+(?:the\\s+|my\\s+)?(?:ringer|ringtone)|(?:turn|switch|shut)\\s+(?:the\\s+|my\\s+)?(?:ringer|ringtone)\\s+off|(?:ringer|ringtone)\\s+off|no\\s+ringing") { ringer("silent") })
        add(Rule("(?:put|set|switch|turn|change)\\s+$phoneOrIt\\s+(?:on|to|in|into)\\s+vibrat(?:e|ion)(?:\\s+mode)?(?:\\s+only)?") { ringer("vibrate") })
        add(Rule("(?:go|switch|change)\\s+(?:to\\s+)?vibrat(?:e|ion)(?:\\s+mode)?|vibrat(?:e|ion)(?:\\s+mode)?(?:\\s+on)?(?:\\s+only)?|(?:turn|switch)\\s+on\\s+vibrat(?:e|ion)(?:\\s+mode)?|enable\\s+vibrat(?:e|ion)(?:\\s+mode)?") { ringer("vibrate") })
        add(Rule("(?:unmute|unsilence)\\s+$phone|(?:turn|switch|put)\\s+on\\s+(?:the\\s+|my\\s+)?(?:ringer|ringtone)|(?:turn|switch|put)\\s+(?:the\\s+|my\\s+)?(?:ringer|ringtone)\\s+(?:back\\s+)?on") { ringer("normal") })
        add(Rule("(?:ringer|ringtone|sound|ring)\\s+(?:back\\s+)?on|(?:silent|silent mode|vibrate|vibrate mode|vibration)\\s+off") { ringer("normal") })
        add(Rule("(?:turn off|switch off|disable|stop|get out of|exit|leave|end)\\s+(?:silent|vibrate|vibration)(?:\\s+mode)?") { ringer("normal") })
        add(Rule("(?:normal|loud|ring|sound|general)\\s+mode|(?:take|turn|get)\\s+(?:my\\s+|the\\s+)?phone\\s+off\\s+(?:silent|vibrate)|make\\s+(?:my\\s+|the\\s+)?phone\\s+ring(?:\\s+again)?") { ringer("normal") })

        // Brightness
        val bright = "(?:the\\s+|my\\s+)?(?:screen\\s+|display\\s+)?brightness"
        val screen = "(?:the\\s+|my\\s+)?(?:screen|display|phone)"
        val bit = "(?:\\s+(?:a\\s+)?(?:bit|little|tad|notch|touch|lot|little bit))?"
        add(Rule("(?:set\\s+|change\\s+|put\\s+|turn\\s+|make\\s+)?$bright\\s+(?:to\\s+|at\\s+)?$PERCENT") { brightness(it.groupValues[1]) })
        add(Rule("(?:set|change|put|turn|make)\\s+$bright\\s+(?:to\\s+)?(max|maximum|min|minimum|full|half|halfway|medium|highest|lowest|all the way up|all the way down)") { brightness(level(it.groupValues[1])) })
        add(Rule("(?:turn|crank|put)\\s+$bright\\s+(all the way up|all the way down|up all the way|down all the way)") { brightness(level(it.groupValues[1])) })
        add(Rule("(max|maximum|full|highest|min|minimum|lowest|half)\\s+brightness|brightness\\s+(?:to\\s+)?(?:the\\s+)?(max|maximum|full|min|minimum|lowest|highest)") {
            brightness(level(it.groupValues[1].ifEmpty { it.groupValues[2] }))
        })
        add(Rule("(?:turn|crank|bump|push)\\s+up\\s+$bright$bit|(?:turn|crank|bump|push)\\s+$bright\\s+up$bit|$bright\\s+up$bit|(?:increase|raise|boost)\\s+$bright$bit") { brightness("up") })
        add(Rule("(?:make\\s+)?$screen\\s+brighter$bit|(?:make\\s+it\\s+)?brighter|$screen\\s+is\\s+too\\s+(?:dark|dim)|(?:i\\s+)?can't\\s+see\\s+$screen|(?:it's|its)\\s+too\\s+dim") { brightness("up") })
        add(Rule("(?:turn|bump|push)\\s+down\\s+$bright$bit|(?:turn|bump|push)\\s+$bright\\s+down$bit|$bright\\s+down$bit|(?:decrease|lower|reduce|drop)\\s+$bright$bit") { brightness("down") })
        add(Rule("(?:make\\s+)?$screen\\s+(?:darker|dimmer)$bit|dim\\s+$screen$bit|dimmer|(?:make\\s+it\\s+)?darker|$screen\\s+is\\s+too\\s+bright|(?:it's|its)\\s+too\\s+bright|too\\s+bright") { brightness("down") })

        // Volume (media)
        val vol = "(?:the\\s+)?(?:media\\s+|music\\s+)?(?:volume|sound|audio|music)"
        val target = "(?:it|this|that|the\\s+volume|the\\s+music|the\\s+sound|the\\s+audio|volume|music|sound)"
        add(Rule("(?:set\\s+|change\\s+|put\\s+|turn\\s+)?(?:the\\s+)?(?:media\\s+|music\\s+)?volume\\s+(?:to\\s+|at\\s+)?$PERCENT") { volume("set", it.groupValues[1]) })
        add(Rule("(?:set|change|put|turn)\\s+$target\\s+(?:to|at)\\s+$PERCENT") { volume("set", it.groupValues[1]) })
        add(Rule("(?:max|maximum|full)\\s+volume|(?:set|put|turn)\\s+$vol\\s+(?:to\\s+)?(?:max|maximum|full)|(?:turn|crank|put|pump)\\s+$target\\s+(?:all the way up|up all the way)") { volume("set", "100") })
        add(Rule("half\\s+volume|(?:set|put|turn)\\s+$vol\\s+(?:to\\s+)?(?:half|halfway)") { volume("set", "50") })
        add(Rule("(?:turn|put)\\s+$target\\s+(?:all the way down|down all the way)") { volume("set", "0") })
        add(Rule("(?:turn|crank|bump|pump|push)\\s+$target\\s+up$bit|(?:turn|crank|bump|pump|push)\\s+up\\s+$target$bit|(?:increase|raise|boost)\\s+$vol$bit|(?:the\\s+)?volume\\s+up$bit") { volume("up") })
        add(Rule("(?:make\\s+it\\s+|a\\s+(?:bit|little)\\s+)?louder|(?:i\\s+)?can't\\s+hear(?:\\s+(?:it|this|that|anything|the music))?|(?:it's|its)\\s+too\\s+quiet|too\\s+quiet") { volume("up") })
        add(Rule("(?:turn|bump|push)\\s+$target\\s+down$bit|(?:turn|bump|push)\\s+down\\s+$target$bit|(?:decrease|lower|reduce|drop)\\s+$vol$bit|(?:the\\s+)?volume\\s+down$bit") { volume("down") })
        add(Rule("(?:make\\s+it\\s+|a\\s+(?:bit|little)\\s+)?(?:quieter|softer)|(?:it's|its|that's|thats|this is)\\s+too\\s+loud|too\\s+loud") { volume("down") })
        add(Rule("mute\\s+(?:the\\s+)?(?:music|media|volume|sound|audio|video|it|this|that)|(?:turn|shut)\\s+(?:the\\s+)?(?:sound|volume|audio)\\s+off|(?:turn|shut)\\s+off\\s+(?:the\\s+)?(?:sound|volume|audio)") { volume("mute") })
        add(Rule("mute") { volume("mute") })
        add(Rule("unmute") { volume("unmute") })
        add(Rule("unmute\\s+(?:the\\s+)?(?:music|media|volume|sound|audio|video|it|this|that)|(?:turn\\s+)?(?:the\\s+)?(?:volume|audio)\\s+back\\s+on") { volume("unmute") })

        // Media ("play that again" restarts the track, so it's checked before plain "play").
        add(Rule("play\\s+(?:that|it|this|the\\s+song|this\\s+song)\\s+again|(?:restart|replay)\\s+(?:the\\s+|this\\s+)?(?:song|track)") { media("previous") })
        add(Rule("(?:play|resume|continue|unpause)(?:\\s+(?:playing|playback))?(?:\\s+(?:the|my|some))?(?:\\s+$MUSIC)?(?:\\s+again)?") { media("play") })
        add(Rule("(?:start|keep)\\s+(?:playing|the music|the song|my music)|(?:hit|press)\\s+play|(?:turn|put)\\s+(?:the\\s+|some\\s+|my\\s+)?music\\s+(?:back\\s+)?on|(?:turn|put)\\s+on\\s+(?:the\\s+|some\\s+|my\\s+)?music") { media("play") })
        add(Rule("(?:pause|stop|halt)(?:\\s+(?:playing|playback))?(?:\\s+(?:the|my))?(?:\\s+$MUSIC)?|(?:hit|press)\\s+pause|(?:turn|shut)\\s+off\\s+(?:the\\s+)?music|(?:turn|shut)\\s+(?:the\\s+)?music\\s+off") { media("pause") })
        add(Rule("next(?:\\s+(?:one|song|track))?|skip(?:\\s+(?:it|this|that|ahead|forward))?(?:\\s+(?:one|song|track))?|skip\\s+(?:this|that)\\s+(?:one|song|track)") { media("next") })
        add(Rule("(?:skip|go|jump)\\s+(?:to\\s+)?(?:the\\s+)?next(?:\\s+(?:one|song|track))?|play\\s+(?:the\\s+)?next(?:\\s+(?:one|song|track))?|(?:change|switch)\\s+(?:the\\s+)?(?:song|track)|(?:new|different|another)\\s+song") { media("next") })
        add(Rule("previous(?:\\s+(?:one|song|track))?|(?:last|previous)\\s+(?:one|song|track)|play\\s+(?:the\\s+)?(?:previous|last)(?:\\s+(?:one|song|track))?") { media("previous") })
        add(Rule("(?:go|skip|jump)\\s+back(?:\\s+(?:a|one))?(?:\\s+(?:song|track))?|(?:back|rewind)\\s+(?:a|one)\\s+(?:song|track)") { media("previous") })

        // Timers before alarms: "wake me up in 20 minutes" is a countdown.
        add(Rule("(?:set|start|create|make|add|put|run)\\s+(?:an?\\s+|the\\s+|my\\s+)?(?:timer|countdown|count down)\\s+(?:for\\s+|of\\s+|to\\s+)?(.+)") { timer(it.groupValues[1]) })
        add(Rule("(?:timer|countdown)\\s+(?:for\\s+|of\\s+)?(.+)") { timer(it.groupValues[1]) })
        add(Rule("count\\s*down\\s+(?:from\\s+|for\\s+)?(.+)") { timer(it.groupValues[1]) })
        add(Rule("(?:(?:set|start|create|make|add|put|give me|i need)\\s+)?(?:an?\\s+)?(.+?)\\s+(?:timer|countdown)") { timer(it.groupValues[1]) })
        add(Rule("time\\s+(.+)") { timer(it.groupValues[1]) })
        add(Rule("(?:remind|ping|alert|buzz|tell|wake)\\s+me(?:\\s+up)?\\s+in\\s+(.+)") { timer(it.groupValues[1]) })

        // Alarms
        add(Rule("(?:set|create|make|add|put|schedule)\\s+(?:an?\\s+|my\\s+|the\\s+)?alarm\\s+(?:for|at|to|on)\\s+(.+)") { alarm(it.groupValues[1]) })
        add(Rule("(?:set\\s+|put\\s+)?(?:an?\\s+|my\\s+)?alarm\\s+(?:for\\s+|at\\s+)?(.+)") { alarm(it.groupValues[1]) })
        add(Rule("(?:wake|get)\\s+me(?:\\s+up)?\\s+(?:at|by|for|around)\\s+(.+)|(?:wake|get)\\s+up\\s+(?:at|by|around)\\s+(.+)") {
            alarm(it.groupValues[1].ifEmpty { it.groupValues[2] })
        })
        add(Rule("(?:(?:set|create|make|add|put)\\s+)?(?:an?\\s+|my\\s+)?(.+?)\\s+alarm") { alarm(it.groupValues[1]) })

        // Calls
        add(Rule("(?:call|phone|dial|ring)\\s+back\\s+(.+)") { callTo(it.groupValues[1]) })
        add(Rule("(?:call|phone|dial|ring)(?:\\s+up)?\\s+(?!me\\b|you\\b|it\\b)(.+)") { callTo(it.groupValues[1]) })
        add(Rule("give\\s+(?!me\\b)(.+?)\\s+a\\s+(?:call|ring|buzz|phone call)") { callTo(it.groupValues[1]) })
        add(Rule("(?:make|place|start)\\s+a\\s+(?:phone\\s+)?call\\s+to\\s+(.+)") { callTo(it.groupValues[1]) })
        add(Rule("get\\s+(.+?)\\s+on\\s+the\\s+(?:phone|line)") { callTo(it.groupValues[1]) })
        add(Rule("(?:talk|speak)\\s+(?:to|with)\\s+(?!me\\b|you\\b)(.+)") { callTo(it.groupValues[1]) })

        // Texts: split on "saying"/":" when present; otherwise the tool splits name from message.
        val textVerb = "(?:text|message|msg|sms)"
        val noun = "(?:quick\\s+)?(?:text message|text|message|msg|sms|note)"
        val sendTo = "(?:send|write|shoot|drop|fire off)\\s+(?:a\\s+|an\\s+)?$noun\\s+to"
        val saying = "(?:saying|that says|that|to say|and say|and tell (?:him|her|them)(?: that)?|and let (?:him|her|them) know(?: that)?)"
        add(Rule("$textVerb\\s+(.+?)\\s+$saying\\s+(.+)", verbatim = true) { sms(it.groupValues[1], it.groupValues[2]) })
        add(Rule("$sendTo\\s+(.+?)\\s+$saying\\s+(.+)", verbatim = true) { sms(it.groupValues[1], it.groupValues[2]) })
        add(Rule("(?:send|write|shoot|drop|fire off)\\s+(?!me\\b)(.+?)\\s+an?\\s+$noun(?:\\s+$saying)?\\s*[:,]?\\s+(.+)", verbatim = true) { sms(it.groupValues[1], it.groupValues[2]) })
        add(Rule("(?:send|write|shoot|drop|fire off)\\s+(?!me\\b|a\\b|an\\b)(.+?)\\s+an?\\s+$noun") { sms(it.groupValues[1], "") })
        add(Rule("(?:$textVerb|$sendTo)\\s+([^:,]+?)\\s*[:,]\\s*(.+)", verbatim = true) { sms(it.groupValues[1], it.groupValues[2]) })
        add(Rule("(?:reply|respond|write back)\\s+to\\s+(.+?)\\s+(?:saying|with|that)\\s+(.+)", verbatim = true) { sms(it.groupValues[1], it.groupValues[2]) })
        add(Rule("let\\s+(?!me\\b)(.+?)\\s+know\\s+(?:that\\s+)?(.+)", verbatim = true) { sms(it.groupValues[1], it.groupValues[2]) })
        add(Rule("tell\\s+(?!me\\b)(.+?)\\s+that\\s+(.+)", verbatim = true) { sms(it.groupValues[1], it.groupValues[2]) })
        add(Rule("(?:$textVerb|$sendTo)\\s+(.+)", verbatim = true) { ToolCall("send_sms", mapOf("text" to together(it.groupValues[1]))) })
        add(Rule("tell\\s+(?!me\\b)(.+)", verbatim = true) { ToolCall("send_sms", mapOf("text" to together(it.groupValues[1]))) })

        // Apps named with "app": before directions, so "take me to the camera app" opens it.
        add(Rule("(?:go|switch|take me|bring me|get me)\\s+to\\s+(?:the\\s+|my\\s+)?(.+?)\\s+app(?:lication)?") { ToolCall("open_app", mapOf("name" to it.groupValues[1])) })
        add(Rule("(?:show|give)\\s+me\\s+(?:the\\s+|my\\s+)?(.+?)\\s+app(?:lication)?") { ToolCall("open_app", mapOf("name" to it.groupValues[1])) })

        // Directions
        add(Rule("(?:navigate|directions|get directions|give me directions|route|route me)\\s+(?:me\\s+)?(?:to\\s+)?(.+)") {
            ToolCall("navigate", mapOf("destination" to it.groupValues[1]))
        })
        add(Rule("(?:take|drive|walk|get|bring|guide)\\s+me\\s+(?:to\\s+(.+)|(home))") {
            ToolCall("navigate", mapOf("destination" to it.groupValues[1].ifEmpty { it.groupValues[2] }))
        })
        add(Rule("(?:how do i get|how to get|how can i get|how do i go|show me the way|what's the way|whats the way)\\s+to\\s+(.+)|(?:the\\s+)?(?:way|route)\\s+to\\s+(.+)") {
            ToolCall("navigate", mapOf("destination" to it.groupValues[1].ifEmpty { it.groupValues[2] }))
        })
        add(Rule("(?:find|where(?:'s| is)|locate|look for|search for|show me)\\s+((?:the\\s+|a\\s+)?(?:nearest|closest)\\s+.+)") {
            ToolCall("navigate", mapOf("destination" to it.groupValues[1]))
        })
        add(Rule("(?:find\\s+(?:me\\s+)?|show\\s+me\\s+|any\\s+|is\\s+there\\s+an?\\s+)?(.+?)\\s+(?:near me|nearby|near here|around here|close by)") {
            ToolCall("navigate", mapOf("destination" to "${it.groupValues[1]} near me"))
        })

        // Status
        val charge = "(?:battery|charge|juice|power)"
        add(Rule("(?:what(?:'s| is)\\s+(?:my\\s+|the\\s+)?)?battery(?:\\s+(?:level|status|percentage|percent|life|at))?") { ToolCall("battery_status") })
        add(Rule("(?:how(?:'s| is)|check)\\s+(?:my\\s+|the\\s+)?battery(?:\\s+(?:doing|looking|level|life))?|how much $charge(?:\\s+(?:do i have|is there|have i got|does my phone have|i have|is|have i))?(?:\\s+left)?") { ToolCall("battery_status") })
        add(Rule("(?:is\\s+my\\s+phone|is\\s+it|am\\s+i|is\\s+the\\s+phone)\\s+(?:charging|charged|plugged in)|what\\s+percent(?:age)?\\s+(?:am i at|is my (?:phone|battery)|is the battery|is it)|do\\s+i\\s+need\\s+to\\s+charge(?:\\s+(?:my\\s+)?(?:phone|it))?") { ToolCall("battery_status") })
        add(Rule("(?:what(?:'s| is)\\s+the\\s+|got\\s+the\\s+|tell\\s+me\\s+the\\s+|the\\s+)?(?:current\\s+)?time(?:\\s+is\\s+it)?|what time is it|do you (?:know|have) (?:what time it is|the time)") {
            ToolCall("date_time", mapOf("what" to "time"))
        })
        add(Rule("(?:what(?:'s| is)\\s+|tell\\s+me\\s+)?(?:the\\s+|today's\\s+)?date(?:\\s+today)?|what(?:'s| is)\\s+(?:the\\s+date\\s+)?today(?:'s\\s+date)?|what day (?:is it|is today|of the week is it|is it today)|what month is it") {
            ToolCall("date_time", mapOf("what" to "date"))
        })

        // Open an app (last: "open"/"start" are also the first word of other commands).
        add(Rule("(?:open|launch|start|run|load|pull up|bring up|fire up|open up)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app(?:lication)?)?") {
            ToolCall("open_app", mapOf("name" to it.groupValues[1]))
        })
    }
}
