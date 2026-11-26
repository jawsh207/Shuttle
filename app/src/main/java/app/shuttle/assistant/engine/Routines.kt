package app.shuttle.assistant.engine

/**
 * Routines: one phrase that runs several commands, set up in Settings › Routines. "Good
 * night" might turn on Do Not Disturb, set brightness to 10% and set an alarm for 7.
 *
 * Each routine is a phrase and a list of commands, one per line, written the way you'd say
 * them to Shuttle. This matches what was said to a routine and turns its lines into tool
 * calls with the same parser as everything else; storing them is in `tools/RoutineStore.kt`.
 * Pure Kotlin, so it's unit-tested.
 */
object Routines {

    data class Routine(val phrase: String, val commands: List<String>)

    private fun clean(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** The routine [said] calls: its phrase, or "run/start/do (my|the) <phrase> (routine)". */
    fun match(said: String, routines: List<Routine>): Routine? {
        val s = clean(said)
            .replace(Regex("^(?:hey shuttle |ok |okay )"), "")
            .replace(Regex("^(?:run|start|do|begin|activate|trigger)\\s+(?:my\\s+|the\\s+)?"), "")
            .replace(Regex("\\s+(?:routine|mode)$"), "")
            .trim()
        if (s.isEmpty()) return null
        return routines.firstOrNull { clean(it.phrase).removeSuffix(" routine") == s }
    }

    /** The routine's commands as calls, and the lines that weren't understood. */
    fun calls(routine: Routine): Pair<List<ToolCall>, List<String>> {
        val calls = ArrayList<ToolCall>()
        val unknown = ArrayList<String>()
        for (line in routine.commands.map { it.trim() }.filter { it.isNotEmpty() }) {
            val parsed = CommandParser.parseAll(line)
            if (parsed.isEmpty()) unknown += line else calls += parsed
        }
        return calls to unknown
    }

    /** Lines of a routine's commands, as typed in Settings (one per line, blank lines dropped). */
    fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
