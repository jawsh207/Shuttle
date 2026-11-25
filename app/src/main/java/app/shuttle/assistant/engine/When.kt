package app.shuttle.assistant.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.temporal.TemporalAdjusters

/**
 * Days and times as people say them, for reminders and calendar events: "in 20 minutes",
 * "at 6", "tomorrow at 9am", "Friday at 3", "tonight", "next Monday morning", "December 3rd".
 *
 * The parser and the model both pass the words through unchanged (a `when` or `day`
 * argument), and this turns them into a date and time against the current moment. A time
 * without am/pm means its next occurrence today, or on a later day, an hour people mean
 * (1–7 is the afternoon or evening). Pure Kotlin, so it's unit-tested.
 */
object When {

    /** A moment, and whether a time of day was actually said (an event without one is all-day). */
    data class Moment(val at: LocalDateTime, val timeGiven: Boolean)

    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )

    private val months = Month.entries.associateBy { it.name.lowercase() } +
        Month.entries.associateBy { it.name.lowercase().take(3) } + mapOf("sept" to Month.SEPTEMBER)

    /** Parts of the day with a usual time, used when no clock time is said. */
    private val partsOfDay = mapOf(
        "morning" to LocalTime.of(9, 0), "afternoon" to LocalTime.of(15, 0),
        "evening" to LocalTime.of(18, 0), "tonight" to LocalTime.of(20, 0), "night" to LocalTime.of(20, 0),
        "noon" to LocalTime.NOON, "lunchtime" to LocalTime.NOON, "midnight" to LocalTime.MIDNIGHT,
    )

    private val dayPattern = Regex(
        "\\b(?:(day after tomorrow)|(today|tonight)|(tomorrow|tmrw|tmr)|(?:(next|this|on)\\s+)?(" +
            weekdays.keys.joinToString("|") + ")|(" + months.keys.joinToString("|") + ")\\s+(\\d{1,2})(?:st|nd|rd|th)?|" +
            "(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(" + months.keys.joinToString("|") + ")|" +
            "(?:on\\s+)?the\\s+(\\d{1,2})(?:st|nd|rd|th))\\b",
    )

    /** Just the day in [text] ("tomorrow", "Friday", "the 5th"), or null. */
    fun day(text: String, today: LocalDate): LocalDate? {
        val m = dayPattern.find(text.lowercase()) ?: return null
        val g = m.groupValues
        return when {
            g[1].isNotEmpty() -> today.plusDays(2)
            g[2].isNotEmpty() -> today
            g[3].isNotEmpty() -> today.plusDays(1)
            g[5].isNotEmpty() -> {
                val dow = weekdays.getValue(g[5])
                val next = today.with(TemporalAdjusters.nextOrSame(dow))
                // "next Friday" said on a Wednesday is still this week's; on Friday it's a week on.
                if (g[4] == "next" && next == today) next.plusWeeks(1) else next
            }
            g[6].isNotEmpty() -> dateIn(months.getValue(g[6]), g[7].toInt(), today)
            g[8].isNotEmpty() -> dateIn(months.getValue(g[9]), g[8].toInt(), today)
            g[10].isNotEmpty() -> {
                val d = g[10].toInt()
                if (d !in 1..31) return null
                val thisMonth = runCatching { today.withDayOfMonth(d) }.getOrNull()
                if (thisMonth != null && !thisMonth.isBefore(today)) thisMonth else runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull()
            }
            else -> null
        }
    }

    /** The next such date: "December 3" said in December after the 3rd is next year's. */
    private fun dateIn(month: Month, dayOfMonth: Int, today: LocalDate): LocalDate? {
        val date = runCatching { LocalDate.of(today.year, month, dayOfMonth) }.getOrNull() ?: return null
        return if (date.isBefore(today)) date.plusYears(1) else date
    }

    /**
     * A moment from [text], or null when it names neither a day nor a time. "in 10 minutes"
     * counts from [now]; a day without a time is at 9am for a reminder, all-day for an event
     * ([Moment.timeGiven] is false).
     */
    fun parse(text: String, now: LocalDateTime): Moment? {
        val s = Quantities.wordsToDigits(text.lowercase().trim().removeSuffix(".").removeSuffix("?"))
            .replace(Regex("\\s+"), " ")
        // "in 20 minutes", "in an hour", "in 2 days"
        Regex("^(?:in|after)\\s+(.+)$").find(s)?.let { m ->
            val rest = m.groupValues[1]
            Regex("^(an?|\\d+)\\s+(days?|weeks?)$").find(rest)?.let { d ->
                val n = d.groupValues[1].toIntOrNull() ?: 1
                val days = if (d.groupValues[2].startsWith("w")) n * 7L else n.toLong()
                return Moment(now.plusDays(days), timeGiven = true)
            }
            Quantities.parseDuration(rest)?.let { return Moment(now.plusSeconds(it.toLong()), timeGiven = true) }
        }
        val today = now.toLocalDate()
        val dayMatch = dayPattern.find(s)
        val date = day(s, today)
        // Whatever isn't the day should be the time ("at 3", "3pm", "in the morning").
        var timeText = (if (dayMatch != null) s.removeRange(dayMatch.range) else s)
            .replace(Regex("\\b(?:at|on|by|around|for)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val tonight = Regex("\\btonight\\b").containsMatchIn(s)
        if (tonight && timeText.isNotEmpty() && !Regex("(?:am|pm|a\\.m|p\\.m)").containsMatchIn(timeText)) timeText += " pm"
        val part = partsOfDay.entries.firstOrNull { (word, _) -> Regex("\\b$word\\b").containsMatchIn(s) }
        val clock = timeText.takeIf { it.isNotEmpty() }?.let(Quantities::parseClock)
        if (clock == null && timeText.isNotEmpty() && part == null) return null
        if (date == null && clock == null && part == null) return null

        val time: LocalTime? = when {
            clock != null && clock.meridiemGiven -> LocalTime.of(clock.hour, clock.minute)
            clock != null && (date == null || date == today) ->
                LocalTime.of(Quantities.resolveHour(clock, now.toLocalTime()), clock.minute)
            // Another day without am/pm: 1–7 means afternoon or evening, as people mean it.
            clock != null -> LocalTime.of(if (clock.hour in 1..7) clock.hour + 12 else clock.hour, clock.minute)
            part != null -> part.value
            else -> null
        }
        var at = (date ?: today).atTime(time ?: LocalTime.of(9, 0))
        // A time with no day that has already passed today means tomorrow.
        if (date == null && !at.isAfter(now)) at = at.plusDays(1)
        return Moment(at, timeGiven = time != null)
    }
}
