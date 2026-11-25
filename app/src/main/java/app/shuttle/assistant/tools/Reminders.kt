package app.shuttle.assistant.tools

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import app.shuttle.assistant.MainActivity
import app.shuttle.assistant.R
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.engine.When
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/**
 * Reminders: "remind me to call Mom at 6" shows a notification saying "Call Mom" at 6.
 *
 * Unlike alarms and timers (which belong to the clock app), reminders are Shuttle's own, so
 * they work with any clock app. Each is set with Android's AlarmManager to wake the phone on
 * time ([ReminderReceiver] then shows it), and kept in the app's private storage so
 * [ReminderRestore] can set them again after a restart, which clears AlarmManager. Exact
 * timing uses USE_EXACT_ALARM (Android 13+) or SCHEDULE_EXACT_ALARM (12); when neither is
 * allowed a reminder may arrive a few minutes late instead.
 */
internal object Reminders {

    private const val CHANNEL = "reminders"
    private const val PREFS = "reminders"

    val runners: Map<String, Runner> = mapOf("set_reminder" to ::set)

    private fun set(context: Context, call: ToolCall): ToolResult {
        val text = call.arg("text") ?: return ToolResult.Failed("What should I remind you about?")
        val whenText = call.arg("when") ?: return ToolResult.Failed("When should I remind you?")
        val moment = When.parse(whenText, LocalDateTime.now()) ?: return ToolResult.Failed("I didn't catch when. Try \"at 6pm\" or \"in 20 minutes\".")
        if (!context.hasAccess(Access.NOTIFY)) return needs(Access.NOTIFY, call, "Reminders arrive as notifications.")
        val at = moment.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at <= System.currentTimeMillis()) return ToolResult.Failed("That time has already passed.")
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val clean = text.replaceFirstChar { it.uppercase() }
        save(context, Saved(id, at, clean))
        schedule(context, id, at, clean)
        return ToolResult.Done("I'll remind you to ${text.replaceFirstChar { it.lowercase() }.removeSuffix(".")} ${describe(context, moment.at)}.")
    }

    /** "at 6:00 PM", "tomorrow at 9:00 AM", "on Friday at 3:00 PM", "on Dec 3 at 9:00 AM". */
    fun describe(context: Context, at: LocalDateTime): String {
        val time = DateFormat.getTimeFormat(context).format(Date.from(at.atZone(ZoneId.systemDefault()).toInstant()))
        val today = LocalDate.now()
        val day = at.toLocalDate()
        return when {
            day == today -> "at $time"
            day == today.plusDays(1) -> "tomorrow at $time"
            day.isBefore(today.plusDays(7)) -> "on ${day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} at $time"
            else -> "on ${DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMd"), Date.from(at.atZone(ZoneId.systemDefault()).toInstant()))} at $time"
        }
    }

    // --- Scheduling and storage ----------------------------------------------------------------

    data class Saved(val id: Int, val at: Long, val text: String)

    private fun intent(context: Context, id: Int, text: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun schedule(context: Context, id: Int, at: Long, text: String) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = intent(context, id, text)
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saved(context: Context): List<Saved> = prefs(context).all.mapNotNull { (key, value) ->
        val line = value as? String ?: return@mapNotNull null
        val at = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
        key.toIntOrNull()?.let { Saved(it, at, line.substringAfter('\t')) }
    }

    private fun save(context: Context, r: Saved) = prefs(context).edit().putString(r.id.toString(), "${r.at}\t${r.text}").apply()

    fun forget(context: Context, id: Int) = prefs(context).edit().remove(id.toString()).apply()

    /** Shows a due reminder as a notification; tapping it opens Shuttle. */
    fun show(context: Context, id: Int, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(text)
            .setContentText("Reminder")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, notification) }
    }
}

/** A reminder is due: show it and forget it. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        Reminders.show(context, id, intent.getStringExtra("text") ?: "Reminder")
        Reminders.forget(context, id)
    }
}

/**
 * After a restart (or an app update, which also clears them), sets every saved reminder
 * again; any that came due meanwhile are shown now.
 */
class ReminderRestore : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        for (r in Reminders.saved(context)) {
            if (r.at <= now) {
                Reminders.show(context, r.id, r.text)
                Reminders.forget(context, r.id)
            } else {
                Reminders.schedule(context, r.id, r.at, r.text)
            }
        }
    }
}
