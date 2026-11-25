package app.shuttle.assistant.engine

/**
 * When to search the web without being told to. The model is meant to call web_search for
 * anything current, but a small model sometimes answers instead with "I don't have
 * real-time information" or "would you like me to search?". So Shuttle searches first for
 * questions that are plainly about now ([isCurrent]), and when an answer turns out to be one
 * of those deferrals ([isDeferral]) it searches and asks again rather than making the user
 * repeat themselves. Pure Kotlin, so it's unit-tested.
 */
object SearchHints {

    private val currentPatterns = listOf(
        "\\b(?:latest|newest|most recent|current(?:ly)?|right now|nowadays|these days|this (?:week|month|year|season|weekend))\\b",
        "\\b(?:today'?s|tonight'?s|yesterday'?s|recent(?:ly)?|news|headlines|breaking)\\b",
        "\\b(?:score|scores|standings|who won|who is winning|who's winning|final score|game last night|playoffs?)\\b",
        "\\b(?:price of|prices?|cost of|how much (?:is|does|are) .+ (?:cost|worth)|stock|share price|exchange rate|bitcoin|mortgage rates?)\\b",
        "\\b(?:release date|released|come out|came out|coming out|launch(?:ed|es)?|new version|announced)\\b",
        "\\b(?:still (?:alive|open|available|running|the)|is .+ still)\\b",
        "\\bwho (?:is|'s) the (?:current )?(?:president|prime minister|ceo|mayor|governor|king|queen|pope|chancellor|leader|head coach|coach|owner|champion)\\b",
        "\\b(?:election|polls?|inflation|interest rates?|unemployment rate|gas prices?)\\b",
        "\\b20(?:2[4-9]|3\\d)\\b",
        "\\b(?:open|close|opening hours|hours) (?:today|tonight|now|on)\\b",
        "\\bwhen (?:does|is|do|will) .+ (?:start|open|close|air|premiere|play|come out|release)\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** Whether a question is about now: news, scores, prices, who holds an office, new releases. */
    fun isCurrent(question: String): Boolean = currentPatterns.any { it.containsMatchIn(question) }

    /** Phrases that mean "I can't know that, go and look" rather than an answer. */
    private val offers = listOf(
        "would you like me to search", "want me to search", "shall i search", "should i search", "i can search",
        "i can look it up", "i can look that up", "like me to look", "want me to look it up", "search the web for you",
    )
    private val deferrals = listOf(
        "real-time", "real time", "up-to-date information", "up to date information", "current information",
        "latest information", "current data", "live data", "knowledge cutoff", "knowledge cut-off", "as of my last",
        "my last update", "my training data", "my training", "trained on data", "don't have access to the internet",
        "do not have access to the internet", "can't browse", "cannot browse", "unable to browse", "can't access the internet",
        "cannot access the internet", "don't have the ability to browse", "i recommend checking", "i'd recommend checking",
        "please check", "check a reliable", "check the latest", "check official", "for the most current",
        "for the most up-to-date", "for the latest", "don't have current", "do not have current", "beyond my knowledge",
        "i'm not aware of any", "i don't have information about", "i do not have information about", "may have changed since",
    )

    /**
     * Whether [answer] is a deferral rather than an answer: an offer to search, or a short
     * reply saying it can't know. A full answer that only ends with "check the latest
     * figures" doesn't count.
     */
    fun isDeferral(answer: String): Boolean {
        val a = answer.lowercase().replace('’', '\'')
        if (offers.any { it in a }) return true
        return a.length < 500 && deferrals.any { it in a }
    }

    /** "search it", "look that up": a search for the question before, not for the word "it". */
    fun refersBack(query: String): Boolean =
        query.trim().lowercase().removeSuffix("?").removeSuffix(".").trim() in
            setOf("it", "that", "this", "for it", "for that", "for this", "it up", "that up", "that for me", "it for me", "for me")

    /** The question as a search: without "can you tell me", "hey shuttle" and the like. */
    fun queryFor(question: String): String = question.trim()
        .replace(Regex("^(?:hey|hi|ok|okay)?\\s*shuttle[,!.]?\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(?:(?:can|could) you (?:please )?(?:tell me|find out|check|look up)|do you know|please tell me|tell me|i want to know|i'd like to know)\\s+", RegexOption.IGNORE_CASE), "")
        .trimEnd('?', '.', '!', ' ')
}
