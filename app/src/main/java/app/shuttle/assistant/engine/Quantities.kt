package app.shuttle.assistant.engine

import java.time.LocalTime

/**
 * Turning spoken-style numbers, clock times and durations into values.
 *
 * Typed commands mostly use digits, but voice input arrives as words ("set a timer for five
 * minutes", "wake me up at seven thirty"), so number words are converted first. Covers 0–99,
 * which is all clock times and everyday timers need.
 */
object Quantities {

    private val units = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    /**
     * "twenty five minutes" → "25 minutes". "oh" only counts as zero after another number
     * ("seven oh five" → "7 0 5", which [parseClock] reads as 7:05).
     */
    fun wordsToDigits(text: String): String {
        val out = mutableListOf<String>()
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var i = 0
        while (i < words.size) {
            val w = words[i].lowercase()
            val ten = tens[w.substringBefore('-')]
            when {
                ten != null -> {
                    // "twenty-five" or "twenty five"
                    val hyphenUnit = units[w.substringAfter('-', "")]
                    val next = words.getOrNull(i + 1)?.lowercase()
                    val nextUnit = next?.let { units[it] }
                    if (hyphenUnit != null && hyphenUnit in 1..9) {
                        out += (ten + hyphenUnit).toString()
                    } else if (nextUnit != null && nextUnit in 1..9 && next != "oh") {
                        out += (ten + nextUnit).toString(); i++
                    } else {
                        out += ten.toString()
                    }
                }
                w == "oh" && out.lastOrNull()?.all { it.isDigit() } != true -> out += words[i]
                w == "hundred" && out.lastOrNull() in setOf("a", "1") -> { out.removeAt(out.lastIndex); out += "100" }
                units[w] != null -> out += units.getValue(w).toString()
                else -> out += words[i]
            }
            i++
        }
        return out.joinToString(" ")
    }

    /** A clock time as said: hour and minute, and whether am/pm was given. */
    data class Clock(val hour: Int, val minute: Int, val meridiemGiven: Boolean)

    /**
     * "7", "7:30", "7.30", "730", "7 30", "7 0 5", "7am", "7:30 p.m.", "noon", "midnight",
     * "7 in the morning", "9 tonight", "7 o'clock", "half past 6", "quarter to 8",
     * "6 tomorrow morning". Returns null when it isn't a time.
     */
    fun parseClock(raw: String): Clock? {
        var s = wordsToDigits(raw.lowercase().trim())
            .replace(Regex("\\b(today|tomorrow|tonight's)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        when (s) {
            "noon", "midday" -> return Clock(12, 0, true)
            "midnight" -> return Clock(0, 0, true)
        }
        var pm: Boolean? = null
        Regex("\\s*(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?|(?:in the|this|tomorrow|every) (?:morning|afternoon|evening)|morning|afternoon|evening|at night|tonight)$")
            .find(s)?.let { m ->
                val tag = m.groupValues[1]
                pm = !(tag.startsWith("a") || tag.endsWith("morning"))
                s = s.substring(0, m.range.first).trim()
            }
        s = s.removePrefix("at ").removePrefix("for ").trim()
            .replace(Regex("\\s*(?:o'?\\s?clock|sharp|on the dot)$"), "")
            .trim()
        // "half past 7", "quarter to 8", "20 past 6", "10 to 9"
        Regex("^(half|quarter|\\d{1,2})(?: minutes?)? (past|after|to|till|before) (\\d{1,2})$").find(s)?.let { m ->
            val minutes = when (m.groupValues[1]) { "half" -> 30; "quarter" -> 15; else -> m.groupValues[1].toInt() }
            val base = m.groupValues[3].toInt()
            if (minutes !in 1..59 || base !in 0..23) return null
            s = if (m.groupValues[2] in setOf("past", "after")) {
                "$base:$minutes"
            } else {
                "${(base + 23) % 24}:${60 - minutes}"
            }
        }
        val m = Regex("^(\\d{1,2})(?:(?::|\\.|\\s)(\\d{1,2})(?:\\s(\\d))?)?$").find(s)
            ?: Regex("^(\\d{1,2})(\\d{2})$").find(s)
            ?: return null
        var hour = m.groupValues[1].toInt()
        var minute = when {
            m.groupValues.size > 3 && m.groupValues[3].isNotEmpty() ->
                // "7 0 5": "oh five" came through as two digits
                (m.groupValues[2] + m.groupValues[3]).toInt()
            m.groupValues[2].isNotEmpty() -> m.groupValues[2].toInt()
            else -> 0
        }
        if (hour > 23 || minute > 59) return null
        val isPm = pm
        if (isPm != null) {
            if (hour !in 1..12) return null
            hour = when {
                isPm && hour < 12 -> hour + 12
                !isPm && hour == 12 -> 0
                else -> hour
            }
        }
        return Clock(hour, minute, meridiemGiven = isPm != null || hour == 0 || hour > 12)
    }

    /**
     * "Alarm for 7" without am/pm: pick whichever of 7:00 and 19:00 comes next, the way
     * people mean it ("alarm for 9" said at 10 pm means 9 am; said at 8 am, 9 am too; said at
     * noon, 9 pm).
     */
    fun resolveHour(clock: Clock, now: LocalTime): Int {
        if (clock.meridiemGiven || clock.hour !in 1..12) return clock.hour
        val am = clock.hour % 12
        val candidates = listOf(am, am + 12)
        val nowMinutes = now.hour * 60 + now.minute
        return candidates.minBy { h ->
            val delta = h * 60 + clock.minute - nowMinutes
            if (delta <= 0) delta + 24 * 60 else delta
        }
    }

    /**
     * "5 minutes", "1 hour 30 minutes", "an hour and a half", "half an hour", "90 seconds",
     * "2h 15m", "a couple of minutes", "2 and a half hours". Returns seconds, or null.
     */
    fun parseDuration(raw: String): Int? {
        val plain = raw.lowercase().replace(Regex("\\s+"), " ").trim()
        if (Regex("^half an? (hour|minute)$").matches(plain)) {
            return if (plain.endsWith("hour")) 1800 else 30
        }
        var s = wordsToDigits(plain)
            .replace(Regex("\\b(?:a\\s+)?couple(?:\\s+of)?\\b"), "2")
            .replace(Regex("\\b(an?)\\s+(hour|minute|second)"), "1 $2")
        var extra = 0
        // "1 and a half hours", "2 and a half minutes"
        Regex("^(\\d+) and a half (hours?|minutes?)$").find(s)?.let { m ->
            val unit = if (m.groupValues[2].startsWith("h")) 3600 else 60
            return m.groupValues[1].toInt() * unit + unit / 2
        }
        Regex("\\s*and a half$").find(s)?.let { m ->
            val unit = Regex("(hour|minute)s?").findAll(s).lastOrNull()?.groupValues?.get(1)
            extra = if (unit == "hour") 1800 else 30
            s = s.substring(0, m.range.first).trim()
        }
        val part = Regex("(\\d+)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b")
        var total = 0
        var matched = 0
        var rest = s
        for (p in part.findAll(s)) {
            val n = p.groupValues[1].toInt()
            val u = p.groupValues[2]
            total += when {
                u.startsWith("h") -> n * 3600
                u.startsWith("m") -> n * 60
                else -> n
            }
            matched++
            rest = rest.replaceFirst(p.value, " ")
        }
        if (matched == 0) return null
        // Anything left besides joining words means this wasn't a plain duration.
        if (rest.replace(Regex("\\b(and|,)\\b|,"), " ").isNotBlank()) return null
        return (total + extra).takeIf { it > 0 }
    }
}
