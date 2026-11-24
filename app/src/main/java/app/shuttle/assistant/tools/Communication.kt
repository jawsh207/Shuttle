package app.shuttle.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult

/**
 * Calls and texts. Both always stop at a confirmation card showing who and what, because a
 * misheard name or a wrong split between name and message would otherwise reach a real
 * person. Confirming re-runs the same call with `confirmed=true`.
 *
 * Texts go through SmsManager. When Shuttle isn't the default SMS app, Android itself copies
 * sent messages into the SMS store, so they appear in the user's messaging app as usual.
 */
internal object Communication {

    val runners: Map<String, Runner> = mapOf(
        "call" to ::call,
        "send_sms" to ::sms,
    )

    data class Person(val name: String, val number: String)

    private val numberLike = Regex("^\\+?[0-9][0-9 ()\\-.]{2,}$")

    /** A number as typed, or the best contact match for a name. Null if nobody matches. */
    private fun resolve(context: Context, who: String): Person? {
        if (numberLike.matches(who)) return Person(who, who.filter { it.isDigit() || it == '+' })
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(who))
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            var best: Person? = null
            var bestScore = -1
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                // Exact name beats first-name match beats any match; the default number wins ties.
                val score = when {
                    name.equals(who, ignoreCase = true) -> 4
                    name.substringBefore(' ').equals(who, ignoreCase = true) -> 2
                    else -> 0
                } + c.getInt(2)
                if (score > bestScore) {
                    best = Person(name, number); bestScore = score
                }
            }
            return best
        }
        return null
    }

    private fun call(context: Context, call: ToolCall): ToolResult {
        val who = call.arg("contact") ?: return ToolResult.Failed("Who should I call?")
        if (!numberLike.matches(who) && !context.hasAccess(Access.CONTACTS)) {
            return needs(Access.CONTACTS, call, "To find $who,")
        }
        val person = resolve(context, who) ?: return ToolResult.Failed("I couldn't find $who in your contacts.")
        if (!call.confirmed) {
            return ToolResult.Confirm("Call ${person.name}${if (person.name != person.number) " (${person.number})" else ""}?", "Call", call)
        }
        if (!context.hasAccess(Access.PHONE)) return needs(Access.PHONE, call, "")
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", person.number, null))
        return if (context.launch(intent)) {
            ToolResult.Done("Calling ${person.name}.", closePanel = true)
        } else {
            ToolResult.Failed("Couldn't start the call.")
        }
    }

    private fun sms(context: Context, call: ToolCall): ToolResult {
        val needsContacts = listOfNotNull(call.arg("contact"), call.arg("text")).any { !numberLike.matches(it.substringBefore(' ')) }
        if (needsContacts && !context.hasAccess(Access.CONTACTS)) {
            return needs(Access.CONTACTS, call, "To find who to text,")
        }
        val (person, message) = recipientAndMessage(context, call)
            ?: return ToolResult.Failed(
                call.arg("contact")?.let { "I couldn't find $it in your contacts." }
                    ?: "Who should I text? Try \"text Sam saying I'm on my way\".",
            )
        if (message.isBlank()) return ToolResult.Failed("What should the message to ${person.name} say?")
        if (!call.confirmed) {
            // Pin the split we showed, so confirming sends exactly this.
            val pinned = ToolCall("send_sms", mapOf("contact" to person.number, "message" to message, "name" to person.name))
            return ToolResult.Confirm("Text ${person.name}: \"$message\"", "Send", pinned)
        }
        if (!context.hasAccess(Access.SMS)) return needs(Access.SMS, call, "")
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        sms.sendMultipartTextMessage(person.number, null, sms.divideMessage(message), null, null)
        return ToolResult.Done("Sent to ${call.arg("name") ?: person.name}.")
    }

    /**
     * Who and what. With separate arguments that's direct. With one run of text ("Sam I'm
     * running late") the name's length is unknown, so try the first three words, then two,
     * then one, and take the longest that matches a contact.
     */
    private fun recipientAndMessage(context: Context, call: ToolCall): Pair<Person, String>? {
        call.arg("contact")?.let { who ->
            val person = resolve(context, who) ?: return null
            return person to (call.arg("message") ?: "")
        }
        val words = call.arg("text")?.split(Regex("\\s+")) ?: return null
        for (n in minOf(3, words.size) downTo 1) {
            val who = words.take(n).joinToString(" ")
            val person = resolve(context, who) ?: continue
            if (numberLike.matches(who) || person.name.contains(who, ignoreCase = true)) {
                return person to words.drop(n).joinToString(" ")
            }
        }
        return null
    }
}
