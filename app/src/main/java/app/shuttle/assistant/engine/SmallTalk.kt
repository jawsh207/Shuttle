package app.shuttle.assistant.engine

/**
 * Short replies to the social things people say to an assistant: hello, thanks, "what can you
 * do?", "never mind".
 *
 * These aren't commands, but answering "I can't do that yet" to "thank you" feels broken, and
 * "what can you do?" is the most useful question a new user can ask. Replies are fixed text,
 * so they work with no model. [Reply.close] is set for dismissals ("never mind"), which close
 * the panel.
 */
object SmallTalk {

    data class Reply(val text: String, val close: Boolean = false)

    private fun matches(s: String, vararg patterns: String) = patterns.any { Regex(it).matches(s) }

    const val HELP =
        "I can switch things like the flashlight, Do Not Disturb and silent mode, change brightness " +
            "and volume, control music, set alarms and timers, call and text your contacts, open apps, " +
            "give directions, tell you the time or your battery level, check what's switched on, and search the web. " +
            "Try \"set a timer for 10 minutes\" or \"text Sam saying I'm on my way\"."

    fun reply(input: String): Reply? {
        val s = input.lowercase().replace('’', '\'').replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("hey ").removePrefix("ok ").removePrefix("okay ").trim()
        if (s.isEmpty()) return null
        return when {
            matches(s, "(?:hi|hello|hey|hiya|yo|howdy|good (?:morning|afternoon|evening)|shuttle|hey shuttle|hi shuttle)(?: shuttle| there)?") ->
                Reply("Hi! What can I do for you?")
            matches(s, "(?:thanks|thank you|thank you so much|thanks a lot|cheers|ta|nice|great|perfect|awesome|cool|got it)(?: shuttle)?") ->
                Reply("You're welcome.")
            matches(s, "(?:what can you do|what do you do|help|help me|what are your commands|what commands (?:do you know|can i use|are there)|what can i (?:say|ask)|how do (?:you|i use you) work)") ->
                Reply(HELP)
            matches(s, "(?:who are you|what are you|what's your name|whats your name|what is your name)") ->
                Reply("I'm Shuttle, the assistant on your phone. Everything I do happens on this device.")
            matches(s, "(?:how are you|how's it going|hows it going|how are you doing|what's up|whats up|sup)") ->
                Reply("All good, thanks. What can I do for you?")
            matches(s, "(?:never ?mind|nevermind|forget it|cancel|cancel that|nothing|no|nope|stop|that's all|thats all|that's it|thats it|done|bye|goodbye|see you|go away|close|exit)(?: thanks| thank you)?") ->
                Reply("Okay.", close = true)
            else -> null
        }
    }
}
