package app.shuttle.assistant.tools

import android.content.Context
import app.shuttle.assistant.engine.Memory
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult

/**
 * "Remember…", "forget…" and "what do you remember?": the facts behind [Memory], kept in the
 * app's private storage only (never sent anywhere except to the on-device model), one per
 * line. Settings › Memory shows them, one by one, to delete.
 */
object MemoryStore {

    private fun prefs(context: Context) = context.getSharedPreferences("memory", Context.MODE_PRIVATE)

    fun facts(context: Context): List<String> =
        prefs(context).getString("facts", "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun save(context: Context, facts: List<String>) {
        prefs(context).edit().putString("facts", facts.joinToString("\n") { it.replace('\n', ' ') }).apply()
    }

    internal val runners: Map<String, Runner> = mapOf(
        "remember" to ::remember,
        "forget" to ::forget,
        "recall" to ::recall,
    )

    private fun remember(context: Context, call: ToolCall): ToolResult {
        val fact = call.arg("fact")?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return ToolResult.Failed("What should I remember?")
        val facts = facts(context)
        if (facts.any { it.equals(fact, ignoreCase = true) }) return ToolResult.Done("I already remember that.")
        save(context, facts + fact)
        return ToolResult.Done("Got it. I'll remember: $fact.")
    }

    private fun forget(context: Context, call: ToolCall): ToolResult {
        val what = call.arg("what") ?: return ToolResult.Failed("What should I forget?")
        val facts = facts(context)
        if (facts.isEmpty()) return ToolResult.Done("I don't remember anything yet.")
        if (what.lowercase() in setOf("all", "everything")) {
            save(context, emptyList())
            return ToolResult.Done("Done. I've forgotten everything you asked me to remember.")
        }
        val gone = Memory.matching(facts, what)
        if (gone.isEmpty()) return ToolResult.Done("I don't remember anything about $what.")
        save(context, facts - gone.toSet())
        return ToolResult.Done(if (gone.size == 1) "Forgotten: ${gone.first()}." else "Forgot ${gone.size} things about $what.")
    }

    private fun recall(context: Context, call: ToolCall): ToolResult {
        val facts = facts(context)
        if (facts.isEmpty()) return ToolResult.Done("Nothing yet. Say \"remember…\" and I'll keep it in mind.")
        return ToolResult.Done("Here's what you asked me to remember:\n" + facts.joinToString("\n") { "- $it" })
    }
}

/** Routines (Settings › Routines), kept in the app's private storage. */
object RoutineStore {

    private const val ROUTINE = "\u001e"
    private const val PART = "\u001f"

    private fun prefs(context: Context) = context.getSharedPreferences("routines", Context.MODE_PRIVATE)

    fun all(context: Context): List<app.shuttle.assistant.engine.Routines.Routine> =
        prefs(context).getString("routines", "").orEmpty().split(ROUTINE).filter { it.isNotBlank() }.map { r ->
            val parts = r.split(PART)
            app.shuttle.assistant.engine.Routines.Routine(parts.first(), parts.drop(1))
        }

    fun save(context: Context, routines: List<app.shuttle.assistant.engine.Routines.Routine>) {
        prefs(context).edit().putString(
            "routines",
            routines.joinToString(ROUTINE) { r -> (listOf(r.phrase) + r.commands).joinToString(PART) { it.replace(ROUTINE, " ").replace(PART, " ") } },
        ).apply()
    }
}
