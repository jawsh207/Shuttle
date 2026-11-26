package app.shuttle.assistant.tools

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.text.format.DateFormat
import app.shuttle.assistant.engine.Quantities
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Alarms, timers and the time and date.
 *
 * Alarms and timers go to the clock app through Android's public AlarmClock intents with
 * "skip UI" set, so they're created in the user's own clock app (which rings them) without
 * leaving the current screen. The time follows the phone's 12/24-hour setting. Checking and
 * cancelling them is more limited (the clock app keeps its own list): see [alarms] and
 * [timers].
 */
internal object Clock {

    val runners: Map<String, Runner> = mapOf(
        "set_alarm" to ::alarm,
        "set_timer" to ::timer,
        "alarms" to ::alarms,
        "timers" to ::timers,
        "date_time" to ::dateTime,
    )

    private fun alarm(context: Context, call: ToolCall): ToolResult {
        val hour = call.arg("hour")?.toIntOrNull()?.takeIf { it in 0..23 }
            ?: return ToolResult.Failed("What time should the alarm be?")
        val minute = call.arg("minute")?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        val resolved = if (call.arg("ambiguous") == "true") {
            Quantities.resolveHour(Quantities.Clock(hour, minute, meridiemGiven = false), LocalTime.now())
        } else {
            hour
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, resolved)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        call.arg("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (!context.launch(intent)) return ToolResult.Failed("No clock app can set alarms.")
        return ToolResult.Done("Alarm set for ${formatTime(context, resolved, minute)}.")
    }

    private fun timer(context: Context, call: ToolCall): ToolResult {
        val seconds = call.arg("seconds")?.toIntOrNull()?.takeIf { it > 0 }
            ?: return ToolResult.Failed("How long should the timer be?")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        call.arg("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (!context.launch(intent)) return ToolResult.Failed("No clock app can set timers.")
        TimerLog.add(context, System.currentTimeMillis() + seconds * 1000L, seconds)
        return ToolResult.Done("Timer set for ${describe(seconds)}.")
    }

    /**
     * Alarms belong to the clock app, which doesn't share its list. Android does say when the
     * next alarm will ring, and clock apps (AOSP's included) take "show alarms" and "turn off
     * the 7:00 alarm" requests.
     */
    private fun alarms(context: Context, call: ToolCall): ToolResult = when (call.arg("action")) {
        "show" -> if (context.launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))) ToolResult.Done("Here are your alarms.", closePanel = true)
        else ToolResult.Failed("No clock app can show alarms.")
        "cancel" -> {
            val hour = call.arg("hour")?.toIntOrNull()
            val minute = call.arg("minute")?.toIntOrNull() ?: 0
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            val what = if (hour != null) {
                val h = if (call.arg("ambiguous") == "true") Quantities.resolveHour(Quantities.Clock(hour, minute, false), LocalTime.now()) else hour
                intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                    .putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_IS_PM, h >= 12)
                "the ${formatTime(context, h, minute)} alarm"
            } else {
                intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                "your next alarm"
            }
            if (context.launch(intent)) ToolResult.Done("Asked your clock app to turn off $what. It may ask you to confirm.", closePanel = true)
            else ToolResult.Failed("Your clock app can't turn alarms off for me. Say \"show my alarms\" to do it there.")
        }
        else -> {
            val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
            if (next == null) {
                ToolResult.Done("No alarms are set.")
            } else {
                val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(next.triggerTime), ZoneId.systemDefault())
                ToolResult.Done("Your next alarm is ${Reminders.describe(context, at)}.")
            }
        }
    }

    /**
     * Timers belong to the clock app too, and Android gives apps no way to read or cancel a
     * running one. So Shuttle remembers the timers it set ([TimerLog]) to say how long is left,
     * and for cancelling opens the clock's timers (or dismisses one that's ringing).
     */
    private fun timers(context: Context, call: ToolCall): ToolResult {
        val now = System.currentTimeMillis()
        val running = TimerLog.running(context, now)
        return when (call.arg("action")) {
            "show" -> if (context.launch(Intent(AlarmClock.ACTION_SHOW_TIMERS))) ToolResult.Done("Here are your timers.", closePanel = true)
            else ToolResult.Failed("No clock app can show timers.")
            "cancel" -> {
                TimerLog.clear(context)
                if (running.isEmpty() && android.os.Build.VERSION.SDK_INT >= 28 && context.launch(Intent(AlarmClock.ACTION_DISMISS_TIMER))) {
                    ToolResult.Done("Timer dismissed.", closePanel = true)
                } else if (context.launch(Intent(AlarmClock.ACTION_SHOW_TIMERS))) {
                    ToolResult.Done("Android doesn't let apps stop a running timer, so here are your timers: tap delete on it.", closePanel = true)
                } else {
                    ToolResult.Failed("No clock app can show timers.")
                }
            }
            else -> when {
                running.isEmpty() -> ToolResult.Done("No timer I set is running. (I can't see timers started in the clock app itself.)")
                running.size == 1 -> {
                    val t = running.first()
                    ToolResult.Done("About ${describe(((t.end - now) / 1000).toInt().coerceAtLeast(1))} left on your ${describe(t.seconds)} timer.")
                }
                else -> ToolResult.Done(
                    running.joinToString("\n", prefix = "Timers running:\n") { t ->
                        "- **${describe(t.seconds)}:** about ${describe(((t.end - now) / 1000).toInt().coerceAtLeast(1))} left"
                    },
                )
            }
        }
    }

    private fun dateTime(context: Context, call: ToolCall): ToolResult {
        val now = Date()
        return if (call.arg("what") == "date") {
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd")
            ToolResult.Done("It's ${DateFormat.format(pattern, now)}.")
        } else {
            ToolResult.Done("It's ${DateFormat.getTimeFormat(context).format(now)}.")
        }
    }

    private fun formatTime(context: Context, hour: Int, minute: Int): String {
        val time = LocalTime.of(hour, minute)
        val date = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, time.hour)
            set(java.util.Calendar.MINUTE, time.minute)
        }.time
        return DateFormat.getTimeFormat(context).format(date)
    }

    /** 90 → "1 minute 30 seconds". */
    fun describe(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour${if (it == 1) "" else "s"}" },
            m.takeIf { it > 0 }?.let { "$it minute${if (it == 1) "" else "s"}" },
            s.takeIf { it > 0 }?.let { "$it second${if (it == 1) "" else "s"}" },
        ).joinToString(" ")
    }
}

/** The timers Shuttle has started (end time and length), so it can say how long is left. */
internal object TimerLog {
    data class Timer(val end: Long, val seconds: Int)

    private fun prefs(context: Context) = context.getSharedPreferences("timers", Context.MODE_PRIVATE)

    fun add(context: Context, end: Long, seconds: Int) {
        val kept = running(context, System.currentTimeMillis()) + Timer(end, seconds)
        prefs(context).edit().putString("timers", kept.joinToString(";") { "${it.end},${it.seconds}" }).apply()
    }

    fun running(context: Context, now: Long): List<Timer> =
        prefs(context).getString("timers", "").orEmpty().split(';').mapNotNull { e ->
            val (end, secs) = e.split(',').takeIf { it.size == 2 } ?: return@mapNotNull null
            Timer(end.toLongOrNull() ?: return@mapNotNull null, secs.toIntOrNull() ?: return@mapNotNull null)
        }.filter { it.end > now }.sortedBy { it.end }

    fun clear(context: Context) = prefs(context).edit().remove("timers").apply()
}
