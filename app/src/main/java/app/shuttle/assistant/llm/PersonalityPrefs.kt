package app.shuttle.assistant.llm

import android.content.Context

/** Where the chosen [Personality] is kept (private app storage). */
object PersonalityPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun get(context: Context): Personality = Personality.from(prefs(context).getString("personality", null))

    fun set(context: Context, personality: Personality) {
        prefs(context).edit().putString("personality", personality.name).apply()
    }
}
