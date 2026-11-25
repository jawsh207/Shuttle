package app.shuttle.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.SettingsPages
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult

/**
 * Things done by handing over to another app or screen: settings pages and App info, the
 * camera, starting an email, saving a note, and reading out a contact's details.
 *
 * All use Android's public intents, so they open whatever the user has installed: their
 * camera, email app and notes app. A note goes to the one notes-like app that takes shared
 * text (Notes, Markor, Joplin…), or the share sheet when there are several or none.
 */
internal object Shortcuts {

    val runners: Map<String, Runner> = mapOf(
        "open_settings" to ::settings,
        "camera" to ::camera,
        "contact_info" to ::contactInfo,
        "send_email" to ::email,
        "add_note" to ::note,
    )

    // --- Settings pages ------------------------------------------------------------------------

    /** Each page's screens, best first: some have no public constant, or differ by version. */
    private val pages: Map<String, Pair<String, List<String>>> = mapOf(
        "wifi" to ("Wi-Fi" to listOf(Settings.ACTION_WIFI_SETTINGS)),
        "bluetooth" to ("Bluetooth" to listOf(Settings.ACTION_BLUETOOTH_SETTINGS)),
        "mobile_network" to ("mobile network" to listOf(Settings.ACTION_DATA_ROAMING_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)),
        "data_usage" to ("data usage" to listOf(Settings.ACTION_DATA_USAGE_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)),
        "hotspot" to ("hotspot" to listOf("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)),
        "vpn" to ("VPN" to listOf(Settings.ACTION_VPN_SETTINGS)),
        "nfc" to ("NFC" to listOf(Settings.ACTION_NFC_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)),
        "airplane" to ("airplane mode" to listOf(Settings.ACTION_AIRPLANE_MODE_SETTINGS)),
        "network" to ("network" to listOf(Settings.ACTION_WIRELESS_SETTINGS)),
        "display" to ("display" to listOf(Settings.ACTION_DISPLAY_SETTINGS)),
        "dark_theme" to ("dark theme" to listOf("android.settings.DARK_THEME_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS)),
        "night_light" to ("Night Light" to listOf("android.settings.NIGHT_DISPLAY_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS)),
        "sound" to ("sound" to listOf(Settings.ACTION_SOUND_SETTINGS)),
        "notifications" to ("notification" to listOf("android.settings.NOTIFICATION_SETTINGS", Settings.ACTION_SETTINGS)),
        "do_not_disturb" to ("Do Not Disturb" to listOf("android.settings.ZEN_MODE_SETTINGS", Settings.ACTION_SOUND_SETTINGS)),
        "battery" to ("battery" to listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS)),
        "battery_saver" to ("battery saver" to listOf(Settings.ACTION_BATTERY_SAVER_SETTINGS)),
        "apps" to ("app" to listOf(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS)),
        "default_apps" to ("default apps" to listOf(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)),
        "storage" to ("storage" to listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
        "location" to ("location" to listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
        "security" to ("security" to listOf(Settings.ACTION_SECURITY_SETTINGS)),
        "privacy" to ("privacy" to listOf(Settings.ACTION_PRIVACY_SETTINGS)),
        "accessibility" to ("accessibility" to listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
        "language" to ("language" to listOf(Settings.ACTION_LOCALE_SETTINGS)),
        "keyboard" to ("keyboard" to listOf(Settings.ACTION_INPUT_METHOD_SETTINGS)),
        "date_time" to ("date and time" to listOf(Settings.ACTION_DATE_SETTINGS)),
        "accounts" to ("accounts" to listOf(Settings.ACTION_SYNC_SETTINGS)),
        "users" to ("users" to listOf("android.settings.USER_SETTINGS", Settings.ACTION_SETTINGS)),
        "developer" to ("developer" to listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),
        "about" to ("about phone" to listOf(Settings.ACTION_DEVICE_INFO_SETTINGS)),
        "system_update" to ("system update" to listOf("android.settings.SYSTEM_UPDATE_SETTINGS", Settings.ACTION_DEVICE_INFO_SETTINGS)),
        "wallpaper" to ("wallpaper" to listOf(Intent.ACTION_SET_WALLPAPER)),
        "cast" to ("cast" to listOf(Settings.ACTION_CAST_SETTINGS)),
        "settings" to ("" to listOf(Settings.ACTION_SETTINGS)),
    )

    private fun settings(context: Context, call: ToolCall): ToolResult {
        call.arg("app")?.let { wanted ->
            val (name, info) = Apps.find(context, wanted) ?: return ToolResult.Failed("I couldn't find an app called $wanted.")
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${info.packageName}"))
            return if (context.launch(intent)) ToolResult.Done("Here's App info for $name.", closePanel = true)
            else ToolResult.Failed("Couldn't open App info for $name.")
        }
        // The model names pages freely ("battery usage"); the parser already sends a key.
        val key = call.arg("page")?.let { SettingsPages.find(it) ?: it } ?: "settings"
        val (name, actions) = pages[key] ?: return ToolResult.Failed("I don't know that settings page.")
        for (action in actions) {
            if (context.launch(Intent(action))) {
                return ToolResult.Done(if (name.isEmpty()) "Opening Settings." else "Opening $name settings.", closePanel = true)
            }
        }
        return ToolResult.Failed("Couldn't open ${name.ifEmpty { "Settings" }}.")
    }

    // --- Camera --------------------------------------------------------------------------------

    private fun camera(context: Context, call: ToolCall): ToolResult {
        val mode = call.arg("mode") ?: "photo"
        val intent = Intent(if (mode == "video") MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (mode == "selfie") {
            // No standard extra for the front camera; these are the ones camera apps look for.
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
                .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        return if (context.launch(intent)) {
            ToolResult.Done(
                when (mode) {
                    "video" -> "Opening the camera for video."
                    "selfie" -> "Opening the camera. If it isn't on the front camera, tap the switch button."
                    else -> "Opening the camera."
                },
                closePanel = true,
            )
        } else {
            ToolResult.Failed("No camera app is installed.")
        }
    }

    // --- Contacts and email --------------------------------------------------------------------

    private data class Found(val id: Long, val name: String)

    /** The contact best matching [who]: an exact name, then a first name, then any match. */
    private fun findContact(context: Context, who: String): Found? {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(who))
        context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
            var best: Found? = null
            var bestScore = -1
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val score = when {
                    name.equals(who, ignoreCase = true) -> 2
                    name.substringBefore(' ').equals(who, ignoreCase = true) -> 1
                    else -> 0
                }
                if (score > bestScore) { best = Found(c.getLong(0), name); bestScore = score }
            }
            return best
        }
        return null
    }

    /** One kind of detail (by its MIME type) for a contact: number, email, address or birthday. */
    private fun details(context: Context, id: Long, mime: String, extraWhere: String = ""): List<String> {
        val out = ArrayList<String>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DATA1),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?$extraWhere",
            arrayOf(id.toString(), mime), null,
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { if (it !in out) out += it } }
        return out
    }

    private fun contactInfo(context: Context, call: ToolCall): ToolResult {
        val who = call.arg("contact") ?: return ToolResult.Failed("Whose details?")
        if (!context.hasAccess(Access.CONTACTS)) return needs(Access.CONTACTS, call, "To look up $who,")
        val contact = findContact(context, who) ?: return ToolResult.Failed("I couldn't find $who in your contacts.")
        val phone = { details(context, contact.id, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) }
        val email = { details(context, contact.id, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) }
        val address = { details(context, contact.id, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE).map { it.replace('\n', ',').replace(",,", ",") } }
        val birthday = {
            details(
                context, contact.id, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                " AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ${ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY}",
            ).map(::readableDate)
        }
        val name = contact.name
        fun one(label: String, values: List<String>) =
            if (values.isEmpty()) "$name has no $label saved." else "$name's $label: ${values.joinToString(", ")}."
        return ToolResult.Done(
            when (call.arg("field")) {
                "email" -> one("email", email())
                "address" -> one("address", address())
                "birthday" -> one("birthday", birthday())
                "phone" -> one("number", phone())
                else -> listOf("Phone" to phone(), "Email" to email(), "Address" to address(), "Birthday" to birthday())
                    .filter { it.second.isNotEmpty() }
                    .joinToString("\n", prefix = "**$name**\n") { (label, v) -> "- **$label:** ${v.joinToString(", ")}" }
                    .let { if ('-' !in it) "$name has no details saved besides the name." else it }
            },
        )
    }

    /** "1990-05-14" → "May 14, 1990"; "--05-14" (no year) → "May 14". */
    private fun readableDate(raw: String): String = runCatching {
        if (raw.startsWith("--")) {
            java.time.MonthDay.parse(raw).let { "${it.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} ${it.dayOfMonth}" }
        } else {
            val d = java.time.LocalDate.parse(raw.take(10))
            "${d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} ${d.dayOfMonth}, ${d.year}"
        }
    }.getOrDefault(raw)

    private fun email(context: Context, call: ToolCall): ToolResult {
        val who = call.arg("contact") ?: return ToolResult.Failed("Who should the email go to?")
        val (name, address) = if ('@' in who) {
            who to who
        } else {
            if (!context.hasAccess(Access.CONTACTS)) return needs(Access.CONTACTS, call, "To find $who's email,")
            val contact = findContact(context, who) ?: return ToolResult.Failed("I couldn't find $who in your contacts.")
            val first = details(context, contact.id, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE).firstOrNull()
                ?: return ToolResult.Failed("${contact.name} has no email address saved.")
            contact.name to first
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
        call.arg("subject")?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        call.arg("message")?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        return if (context.launch(intent)) ToolResult.Done("Here's an email to $name. Check it and send.", closePanel = true)
        else ToolResult.Failed("No email app is installed.")
    }

    // --- Notes -----------------------------------------------------------------------------------

    private val notesLike = Regex("note|notes|memo|markor|joplin|obsidian|journal|list|keep|todo|to-do|tasks", RegexOption.IGNORE_CASE)

    private fun note(context: Context, call: ToolCall): ToolResult {
        val text = call.arg("text") ?: return ToolResult.Failed("What should the note say?")
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val pm = context.packageManager
        val notesApps = pm.queryIntentActivities(share, 0).filter { notesLike.containsMatchIn(it.loadLabel(pm)) && it.activityInfo.packageName != context.packageName }
        val target = notesApps.distinctBy { it.activityInfo.packageName }.singleOrNull()
        return if (target != null) {
            share.setClassName(target.activityInfo.packageName, target.activityInfo.name)
            if (context.launch(share)) ToolResult.Done("Sent to ${target.loadLabel(pm)}. Save it there if it asks.", closePanel = true)
            else ToolResult.Failed("Couldn't open ${target.loadLabel(pm)}.")
        } else {
            if (context.launch(Intent.createChooser(share, "Save note with"))) ToolResult.Done("Choose where to save the note.", closePanel = true)
            else ToolResult.Failed("No app can take notes.")
        }
    }
}
