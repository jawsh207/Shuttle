package app.shuttle.assistant.llm

import app.shuttle.assistant.engine.ToolCall

/**
 * Talking to Granite 4.2 in its own format: building the prompt text and reading tool calls
 * out of the reply.
 *
 * This reproduces the model's published chat template (chat_template.jinja in the
 * ibm-granite/granite-4.2-3b repository) with thinking turned off, so answers start straight
 * away instead of after a hidden reasoning pass. Messages are wrapped in `<|im_start|>role` …
 * `<|im_end|>`; tools are listed in the system message as one JSON object per line inside
 * `<tools>`; the model asks for a tool with
 * `<tool_call><function=name><parameter=key>value</parameter></function></tool_call>`; tool
 * results go back as a user message wrapped in `<tool_response>`.
 *
 * Plain Kotlin with no Android types, so the exact text is covered by unit tests. If a future
 * model uses a different format, it gets its own file like this one.
 */
object GranitePrompt {

    enum class Role { USER, ASSISTANT, TOOL }

    data class Message(val role: Role, val content: String)

    private const val TOOL_INSTRUCTIONS =
        "\n\nIf you choose to call a function ONLY reply in the following format with NO suffix:\n\n" +
            "<tool_call>\n<function=example_function_name>\n<parameter=example_parameter_1>\nvalue_1\n</parameter>\n" +
            "<parameter=example_parameter_2>\nThis is the value for the second parameter\nthat can span\nmultiple lines\n" +
            "</parameter>\n</function>\n</tool_call>\n\n<IMPORTANT>\nReminder:\n" +
            "- Function calls MUST follow the specified format: an inner <function=...></function> block must be nested within <tool_call></tool_call> XML tags\n" +
            "- Required parameters MUST be specified\n" +
            "- You may provide optional reasoning for your function call in natural language BEFORE the function call, but NOT after\n" +
            "- If there is no function call available, answer the question like normal with your current knowledge and do not tell the user about function calls\n" +
            "</IMPORTANT>"

    /**
     * The full prompt for the next reply. [tools] are single-line JSON objects
     * (`{"name": …, "description": …, "parameters": …}`); empty means no tools.
     */
    fun build(system: String, tools: List<String>, messages: List<Message>): String = buildString {
        append("<|im_start|>system\n").append(system)
        if (tools.isNotEmpty()) {
            if (system.isNotEmpty()) append("\n\n")
            append("# Tools\n\nYou have access to the following functions:\n\n<tools>")
            tools.forEach { append('\n').append(it) }
            append("\n</tools>")
            append(TOOL_INSTRUCTIONS)
        }
        append("<|im_end|>\n")
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m.role) {
                Role.USER -> append("<|im_start|>user\n").append(m.content).append("<|im_end|>\n")
                Role.ASSISTANT -> append("<|im_start|>assistant\n<think></think>").append(m.content.trim()).append("<|im_end|>\n")
                Role.TOOL -> {
                    // Consecutive tool results share one user turn, as in the template.
                    append("<|im_start|>user\n")
                    while (i < messages.size && messages[i].role == Role.TOOL) {
                        append("<tool_response>\n").append(messages[i].content).append("\n</tool_response>\n")
                        i++
                    }
                    append("<|im_end|>\n")
                    continue
                }
            }
            i++
        }
        append("<|im_start|>assistant\n<think></think>")
    }

    private val callPattern = Regex("<tool_call>\\s*<function=([^>\\s]+)>(.*?)</function>\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val paramPattern = Regex("<parameter=([^>\\s]+)>\\n?(.*?)\\n?</parameter>", RegexOption.DOT_MATCHES_ALL)

    /** Tool calls in a reply, in order. Argument values are trimmed strings. */
    fun toolCalls(reply: String): List<ToolCall> = callPattern.findAll(reply).map { m ->
        val args = paramPattern.findAll(m.groupValues[2]).associate { p -> p.groupValues[1] to p.groupValues[2].trim() }
        ToolCall(m.groupValues[1], args)
    }.toList()

    /**
     * What to show the user from a (possibly still streaming) reply: no thinking block, and
     * nothing from the first tool call on, including a half-written `<tool_call` tag.
     */
    fun visibleText(reply: String): String {
        var s = reply.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        s = s.substringBefore("<think>")
        val call = s.indexOf("<tool_call")
        if (call >= 0) s = s.substring(0, call)
        // A tag still being written: "<", "<to", "<tool_c"…
        val lastLt = s.lastIndexOf('<')
        if (lastLt >= 0 && "<tool_call".startsWith(s.substring(lastLt))) s = s.substring(0, lastLt)
        return s.trim()
    }
}
