package app.shuttle.assistant.engine

/**
 * Commands that only make sense after the one before: "turn it off", "switch it back on",
 * "it's still on", "that didn't work", "try again". They act on the last command the user
 * gave, so "Flashlight on" then "turn it off" turns the flashlight off, and "it's still on"
 * after "turn the flashlight off" tries turning it off again.
 *
 * Handled here, not by the model: a small model may answer "Flashlight off." without
 * actually doing it. Pure Kotlin, so it's unit-tested.
 */
object FollowUps {

    private fun clean(s: String) = s.lowercase().replace('’', '\'').replace(Regex("[^a-z' ]+"), " ").replace(Regex("\\s+"), " ").trim()
        .removePrefix("please ").removePrefix("ok ").removePrefix("okay ").removePrefix("now ").removeSuffix(" please").removeSuffix(" now").trim()

    private const val IT = "(?:it|that|this|them|the light|the thing)"

    /** "turn it off", "switch that back on", "off again", "back on". */
    private val setState = listOf(
        Regex("(?:turn|switch|shut|put|flip|set|get)\\s+$IT\\s+(?:back\\s+)?(on|off)(?:\\s+again)?"),
        Regex("(?:turn|switch|shut)\\s+(on|off)\\s+$IT(?:\\s+again)?"),
        Regex("$IT\\s+(?:back\\s+)?(on|off)(?:\\s+again)?"),
        Regex("(?:and\\s+)?(?:back\\s+)?(on|off)\\s+again|back\\s+(on|off)"),
        Regex("(?:shut|switch|turn)\\s+(off)"),
        Regex("(?:kill|stop)\\s+$IT()"),
    )

    /** "it's still on" → off; "it's still off" / "it's not on" → on; "it didn't turn off" → off. */
    private val stillState = listOf(
        Regex("(?:it'?s|it is|that'?s|that is|it was|its|they'?re|they are)\\s+still\\s+(on|off)"),
        Regex("still\\s+(on|off)"),
    )
    private val notState = listOf(
        Regex("(?:it'?s|it is|that'?s|its|it'?s still|it is still)\\s+not\\s+(on|off)"),
        Regex("(?:it|that)\\s+(?:didn'?t|did not|won'?t|wouldn'?t|doesn'?t)\\s+(?:turn|go|switch|come)\\s+(on|off)"),
    )

    /** "that didn't work", "try again", "do it again", "nothing happened". */
    private val retry = Regex(
        "(?:it|that|this)?\\s*(?:didn'?t|did not|doesn'?t|does not)\\s+work|try\\s+(?:it\\s+|that\\s+)?again|do\\s+(?:it|that)\\s+again|" +
            "nothing\\s+happened|it\\s+didn'?t\\s+do\\s+anything|that\\s+didn'?t\\s+do\\s+anything|again|one\\s+more\\s+time",
    )

    /** Tools a follow-up can act on: things that are switched on or off, or simply redone. */
    private fun hasState(call: ToolCall) = ToolSchemas.find(call.name)?.params?.any { it.name == "state" } == true

    /**
     * The command [said] means, given the [last] one, or null when it isn't such a follow-up
     * (or there's nothing to follow up on).
     */
    fun resolve(said: String, last: ToolCall?): ToolCall? {
        if (last == null) return null
        val s = clean(said)
        if (s.isEmpty()) return null
        val base = last.args - CONFIRMED
        fun withState(state: String) = if (hasState(last)) ToolCall(last.name, base + ("state" to state)) else null
        for (r in stillState) r.matchEntire(s)?.let { m -> return withState(if (m.groupValues[1] == "on") "off" else "on") }
        for (r in notState) r.matchEntire(s)?.let { m -> return withState(m.groupValues[1]) }
        for (r in setState) r.matchEntire(s)?.let { m ->
            val state = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "off"
            return withState(state)
        }
        if (retry.matches(s)) return ToolCall(last.name, base)
        return null
    }
}
