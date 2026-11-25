package app.shuttle.assistant.llm

/**
 * How the model talks: chosen in Settings › Personality, and sent to the model as one line of
 * instruction with each question ([ModelPrompts.note]).
 *
 * It goes in that per-question note rather than the main instructions, so switching
 * personality never makes the model re-read its instructions and tool list. It shapes only
 * the model's own answers; phone commands ("Wi-Fi off.") reply the same in every personality.
 * Pure Kotlin, so it's unit-tested; the choice is stored by [PersonalityPrefs].
 */
enum class Personality(val label: String, val description: String, val instruction: String) {
    BALANCED("Balanced", "Clear and helpful. The default.", ""),
    BRIEF(
        "Brief", "As short as possible, straight to the point.",
        "Be as brief as possible: answer in a sentence or two unless asked for more, with no examples or extras.",
    ),
    FRIENDLY(
        "Friendly", "Warm and casual, like a friend who knows a lot.",
        "Be warm, casual and encouraging, like a friend who knows a lot. Use everyday words and the odd light joke. No emoji.",
    ),
    PROFESSIONAL(
        "Professional", "Formal, precise and neutral.",
        "Be formal, precise and neutral: complete sentences, exact terms, no jokes, slang or chit-chat.",
    ),
    WITTY(
        "Witty", "Dry humour and wordplay, still accurate.",
        "Be witty: dry humour, the occasional pun or quip, playful phrasing. Keep the facts accurate and the answer useful.",
    ),
    TEACHER(
        "Teacher", "Explains the why, step by step.",
        "Be a patient teacher: explain the reasoning step by step in simple terms, with a quick example or analogy for key ideas.",
    ),
    SASSY(
        "Sassy", "Cheeky and a bit sarcastic, but still helps.",
        "Be cheeky and a little sarcastic, with playful teasing, but never mean, and always give the real answer in full.",
    );

    companion object {
        /** The stored name back to a personality; anything unknown is [BALANCED]. */
        fun from(name: String?): Personality = entries.firstOrNull { it.name == name } ?: BALANCED
    }
}
