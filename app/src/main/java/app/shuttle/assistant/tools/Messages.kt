package app.shuttle.assistant.tools

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.QuickTileNames
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult

/**
 * Reading notifications and replying to messages: "read my messages", "what did Sam say?",
 * "reply to Sam saying on my way".
 *
 * Needs notification access, which the user turns on in Android's settings
 * ([MessagesService]). Replies go through the message's own Reply button (Android's
 * RemoteInput), so they're sent by the app the message came from (Signal, Molly, twill,
 * any messenger that offers a reply) and always stop at a confirmation card first. With no
 * message to reply to, a reply to a named person falls back to a text, as before.
 *
 * Nothing is kept: notifications are read from the system's current list only when asked.
 */
internal object Messages {

    val runners: Map<String, Runner> = mapOf(
        "read_notifications" to ::read,
        "reply_message" to ::reply,
    )

    fun enabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, MessagesService::class.java))

    /** One notification, as people would read it. */
    private class Item(val sbn: StatusBarNotification, val app: String, val title: String, val lines: List<String>, val senders: List<String>) {
        val replyAction: Notification.Action?
            get() = sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
    }

    private fun appName(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    /** The current notifications worth reading, newest first. */
    private fun items(context: Context): List<Item>? {
        val service = MessagesService.instance ?: return null
        val active = runCatching { service.activeNotifications }.getOrNull() ?: return emptyList()
        return active
            .filter { sbn ->
                val n = sbn.notification
                sbn.packageName != context.packageName && !sbn.isOngoing &&
                    (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                    n.category != Notification.CATEGORY_PROGRESS && n.category != Notification.CATEGORY_SERVICE &&
                    n.category != Notification.CATEGORY_TRANSPORT
            }
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn ->
                val e = sbn.notification.extras
                val title = (e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: e.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
                val senders = ArrayList<String>()
                // Chat apps list the messages themselves (MessagingStyle); show the last few.
                @Suppress("DEPRECATION")
                val messages = (e.getParcelableArray(Notification.EXTRA_MESSAGES) as? Array<Parcelable>)?.mapNotNull { p ->
                    val b = p as? Bundle ?: return@mapNotNull null
                    val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                    @Suppress("DEPRECATION")
                    val sender = b.getCharSequence("sender")?.toString() ?: b.getParcelable<Person>("sender_person")?.name?.toString()
                    sender?.let { senders += it }
                    if (sender != null && sender != title) "$sender: $text" else text
                }?.takeLast(3)
                val lines = messages?.takeIf { it.isNotEmpty() }
                    ?: e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }?.takeLast(3)
                    ?: listOfNotNull((e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString())
                if (title.isBlank() && lines.isEmpty()) null else Item(sbn, appName(context, sbn.packageName), title, lines, senders)
            }
    }

    /** How well [item] matches what the user named: an app, a conversation or a sender. */
    private fun matches(item: Item, who: String): Int {
        val names = listOf(item.title) + item.senders
        return maxOf(
            names.maxOfOrNull { QuickTileNames.score(it, who) } ?: 0,
            QuickTileNames.score(item.app, who),
        )
    }

    private fun read(context: Context, call: ToolCall): ToolResult {
        if (!context.hasAccess(Access.NOTIFICATION_ACCESS)) return needs(Access.NOTIFICATION_ACCESS, call, "To read your notifications,")
        val all = items(context) ?: return ToolResult.Failed("Notification access is on, but Android hasn't connected it yet. Try again in a moment.")
        val from = call.arg("from")
        val chosen = if (from == null) all else all.filter { matches(it, from) > 0 }
        if (chosen.isEmpty()) return ToolResult.Done(if (from == null) "No new notifications." else "Nothing from $from.")
        val shown = chosen.take(6)
        val head = when {
            from != null -> "From $from:"
            chosen.size == 1 -> "One notification:"
            chosen.size > shown.size -> "Your latest ${shown.size} of ${chosen.size} notifications:"
            else -> "${chosen.size} notifications:"
        }
        return ToolResult.Done(
            head + "\n" + shown.joinToString("\n") { i ->
                val title = i.title.takeIf { it.isNotBlank() && it != i.app }?.let { " · $it" }.orEmpty()
                "- **${i.app}$title:** " + i.lines.joinToString(" / ").ifBlank { "(no text)" }
            },
        )
    }

    private fun reply(context: Context, call: ToolCall): ToolResult {
        val message = call.arg("message") ?: return ToolResult.Failed("What should the reply say?")
        val who = call.arg("contact")
        // Without notification access, or with nothing to reply to, a named person gets a text.
        fun textInstead(): ToolResult =
            if (who != null) DeviceTools.run(context, ToolCall("send_sms", mapOf("contact" to who, "message" to message) + call.args.filterKeys { it == "confirmed" }))
            else ToolResult.Failed("There's no message I can reply to.")
        if (!context.hasAccess(Access.NOTIFICATION_ACCESS)) {
            return if (who != null) textInstead() else needs(Access.NOTIFICATION_ACCESS, call, "To reply to messages,")
        }
        val candidates = (items(context) ?: return textInstead()).filter { it.replyAction != null }
        val item = if (who == null) candidates.firstOrNull() else candidates.filter { matches(it, who) > 0 }.maxByOrNull { matches(it, who!!) }
        item ?: return textInstead()
        val name = item.title.ifBlank { who ?: item.app }
        if (!call.confirmed) return ToolResult.Confirm("Reply to $name on ${item.app}: \"$message\"?", "Send", call)
        val action = item.replyAction ?: return ToolResult.Failed("That message can't be replied to any more.")
        val input = action.remoteInputs.first { it.allowFreeFormInput }
        val fill = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), fill, Bundle().apply { putCharSequence(input.resultKey, message) })
        return try {
            action.actionIntent.send(context, 0, fill)
            ToolResult.Done("Replied to $name.")
        } catch (e: Exception) {
            ToolResult.Failed("${item.app} didn't take the reply. Open the message to answer it.")
        }
    }
}

/**
 * Shuttle's notification listener, turned on by the user in Android's notification access
 * settings. It only holds the connection so [Messages] can read the current notifications
 * when asked; it doesn't watch or keep any.
 */
class MessagesService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: MessagesService? = null
            private set
    }
}
