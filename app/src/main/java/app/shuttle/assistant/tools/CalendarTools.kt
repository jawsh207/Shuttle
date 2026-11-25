package app.shuttle.assistant.tools

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.text.format.DateFormat
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.engine.When
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/**
 * The calendar: "what's on today?" reads the phone's calendars (every one the user shows in
 * their calendar app, local or synced through DAVx⁵ and the like); "add dentist Friday at 3"
 * opens the user's calendar app with the event filled in, for them to check and save.
 *
 * Reading needs the Calendar permission. Adding needs none: Android's standard "insert
 * event" screen belongs to the calendar app, and the user saves it there, so Shuttle never
 * writes to a calendar itself.
 */
internal object CalendarTools {

    val runners: Map<String, Runner> = mapOf(
        "calendar_events" to ::events,
        "add_event" to ::add,
    )

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun events(context: Context, call: ToolCall): ToolResult {
        if (!context.hasAccess(Access.CALENDAR)) return needs(Access.CALENDAR, call, "To read your calendar,")
        val today = LocalDate.now()
        val dayArg = call.arg("day") ?: "today"
        val week = dayArg == "week"
        val start = if (week) today else When.day(dayArg, today) ?: today
        val end = if (week) today.plusDays(7) else start.plusDays(1)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, millis(start.atStartOfDay()))
            ContentUris.appendId(it, millis(end.atStartOfDay()))
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        data class Event(val title: String, val begin: Long, val allDay: Boolean, val place: String?)
        val found = ArrayList<Event>()
        context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) found += Event(c.getString(0) ?: "(No title)", c.getLong(1), c.getInt(2) == 1, c.getString(3)?.takeIf { it.isNotBlank() })
        }
        val dayName = when {
            week -> "this week"
            start == today -> "today"
            start == today.plusDays(1) -> "tomorrow"
            else -> "on " + start.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        }
        if (found.isEmpty()) return ToolResult.Done("Nothing on your calendar $dayName.")
        val timeFormat = DateFormat.getTimeFormat(context)
        fun line(e: Event): String {
            // All-day events are stored at midnight UTC.
            val date = if (e.allDay) LocalDateTime.ofEpochSecond(e.begin / 1000, 0, ZoneOffset.UTC).toLocalDate()
            else LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(e.begin), ZoneId.systemDefault()).toLocalDate()
            val time = if (e.allDay) "All day" else timeFormat.format(Date(e.begin))
            val dayPart = if (week) date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " else ""
            return "- **$dayPart$time:** ${e.title}" + (e.place?.let { " ($it)" } ?: "")
        }
        val count = if (found.size == 1) "one event" else "${found.size} events"
        return ToolResult.Done("You have $count $dayName:\n" + found.take(15).joinToString("\n", transform = ::line))
    }

    private fun add(context: Context, call: ToolCall): ToolResult {
        val title = call.arg("title") ?: return ToolResult.Failed("What's the event?")
        val moment = call.arg("when")?.let { When.parse(it, LocalDateTime.now()) }
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        var describe = ""
        if (moment != null) {
            if (moment.timeGiven) {
                val begin = millis(moment.at)
                intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60 * 1000)
                describe = " " + Reminders.describe(context, moment.at)
            } else {
                val day = moment.at.toLocalDate()
                intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis(day.atStartOfDay()))
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, millis(day.plusDays(1).atStartOfDay()))
                describe = " " + Reminders.describe(context, moment.at).substringBefore(" at ").ifEmpty { "today" }
                    .let { if (it.startsWith("at")) "today" else it }
            }
        }
        return if (context.launch(intent)) {
            ToolResult.Done("Here's \"$title\"$describe in your calendar. Check it and tap Save.", closePanel = true)
        } else {
            ToolResult.Failed("No calendar app can add events.")
        }
    }
}
