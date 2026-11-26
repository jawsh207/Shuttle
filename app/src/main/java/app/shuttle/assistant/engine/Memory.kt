package app.shuttle.assistant.engine

/**
 * What the user has asked Shuttle to remember ("remember my wife's name is Sam", "remember
 * my gym locker is 214"), and how it's used:
 *
 * - The facts go to the model with each question, so answers can use them.
 * - People named by relation ("my wife", "mom", "my boss") are turned into the names the
 *   facts give, so "text my wife saying I'm on my way" texts Sam.
 *
 * The facts live only in the app's private storage (`tools/MemoryTools.kt`) and can be
 * reviewed and deleted in Settings › Memory. Pure Kotlin, so it's unit-tested.
 */
object Memory {

    private const val NAME = "([A-Z][\\p{L}'.-]*(?:\\s+[A-Z][\\p{L}'.-]*){0,3})"
    private const val ANY_NAME = "([\\p{L}][\\p{L}'.-]*(?:\\s+[\\p{L}][\\p{L}'.-]*){0,3})"
    private const val WHO = "([a-zA-Z][a-zA-Z' -]{1,30}?)"

    private val patterns = listOf(
        // "my wife's name is sam", "my wife is named Sam", "my boss is called Dana" (any case:
        // the words say it's a name)
        Regex("(?i)my\\s+$WHO(?:'s\\s+name\\s+is|\\s+is\\s+named|\\s+is\\s+called|\\s+goes\\s+by)\\s+$ANY_NAME"),
        // "my wife is Sam" (a capital, so not "my wife is home")
        Regex("(?i:my)\\s+$WHO\\s+(?i:is)\\s+$NAME"),
        // "Sam is my wife"
        Regex("$NAME\\s+(?i:is)\\s+(?i:my)\\s+$WHO"),
    )

    private fun capitalized(name: String) = name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Relations a fact gives a name for: "wife" → "Sam". Later facts win. */
    fun names(facts: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (fact in facts) {
            val f = fact.trim().trimEnd('.', '!')
            val named = patterns[0].matchEntire(f)
            val plain = patterns[1].matchEntire(f)
            val reversed = patterns[2].matchEntire(f)
            when {
                named != null -> out[named.groupValues[1].lowercase().trim()] = capitalized(named.groupValues[2])
                plain != null -> out[plain.groupValues[1].lowercase().trim()] = plain.groupValues[2]
                reversed != null -> out[reversed.groupValues[2].lowercase().trim()] = reversed.groupValues[1]
            }
        }
        return out
    }

    /**
     * The name for a contact said as a relation ("my wife", "wife", "Mom"), or the words
     * unchanged when no fact names them.
     */
    fun resolve(contact: String, facts: List<String>): String {
        val key = contact.trim().lowercase().removePrefix("my ").removePrefix("our ").trim()
        return names(facts)[key] ?: contact
    }

    /** The facts as one line for the model, newest last, kept short. */
    fun forModel(facts: List<String>, budget: Int = 1200): String {
        if (facts.isEmpty()) return ""
        val kept = ArrayList<String>()
        var used = 0
        for (f in facts.asReversed()) {
            if (used + f.length > budget) break
            kept += f
            used += f.length + 2
        }
        return "What the user asked you to remember: " + kept.asReversed().joinToString("; ") { it.trimEnd('.') } + "."
    }

    /** The facts that match [what] (any shared word of 3+ letters), for "forget my gym code". */
    fun matching(facts: List<String>, what: String): List<String> {
        val words = what.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in setOf("that", "the", "about", "my", "what") }
        if (words.isEmpty()) return emptyList()
        return facts.filter { f -> val l = f.lowercase(); words.all { it in l } }
            .ifEmpty { facts.filter { f -> val l = f.lowercase(); words.any { it in l } } }
    }
}
