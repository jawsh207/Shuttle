package app.shuttle.assistant.chats

import android.content.Context

/**
 * Chat settings. Saving is on by default; turning it off stops new chats being written but
 * leaves saved ones until they're deleted.
 */
class ChatPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("chats", Context.MODE_PRIVATE)

    var saveChats: Boolean
        get() = prefs.getBoolean("save_chats", true)
        set(value) = prefs.edit().putBoolean("save_chats", value).apply()
}
