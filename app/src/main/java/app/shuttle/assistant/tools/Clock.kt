package app.shuttle.assistant.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.text.format.DateFormat
import app.shuttle.assistant.engine.Quantities
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import java.time.LocalTime
import java.util.Date
import java.util.Locale

/**
 * Alarms, timers and the time and date.
 *
 * Alarms and timers go to the clock app through Android's public AlarmClock intents with
 * "skip UI" set, so they're created in the user's own clock app (which rings them) without
 * leaving the current screen. The time follows the phone's 12/24-hour setting.
 */
internal object Clock {

    val runners: Map<String, Runner> = mapOf(
        "set_alarm" to ::alarm,
        "set_timer" to ::timer,
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
        return ToolResult.Done("Timer set for ${describe(seconds)}.")
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
