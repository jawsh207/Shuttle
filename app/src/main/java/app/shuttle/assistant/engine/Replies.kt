package app.shuttle.assistant.engine

/**
 * Reading a spoken answer to a yes/no question, such as "Text Sam: on my way. Send it?".
 *
 * Kept deliberately strict: only clear yeses and noes count, and anything else returns null so
 * the caller treats it as a new request instead of guessing. A misheard "yes" would send a
 * message, so "yes, but change it…" or "sure, no" is not a yes.
 */
object Replies {

    enum class Answer { YES, NO }

    private val yes = setOf(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "correct", "right",
        "send", "send it", "call", "call them", "do it", "go", "go ahead", "please do", "yes please",
        "that's right", "sounds good",
    )
    private val no = setOf(
        "no", "nope", "nah", "cancel", "stop", "don't", "do not", "never mind", "nevermind",
        "no thanks", "no thank you", "forget it", "wait", "hold on", "don't send it", "don't call",
    )

    fun yesNo(heard: String): Answer? {
        val s = heard.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("shuttle ")
        return when {
            s in yes -> Answer.YES
            s in no -> Answer.NO
            else -> null
        }
    }
}
