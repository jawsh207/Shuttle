package app.shuttle.assistant.llm

import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.llm.GranitePrompt.Message
import app.shuttle.assistant.llm.GranitePrompt.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GranitePromptTest {

    @Test fun promptWithoutTools() {
        val p = GranitePrompt.build("Be brief.", emptyList(), listOf(Message(Role.USER, "hi")))
        assertEquals(
            "<|im_start|>system\nBe brief.<|im_end|>\n<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n<think></think>",
            p,
        )
    }

    @Test fun promptWithToolsAndHistory() {
        val p = GranitePrompt.build(
            "Sys",
            listOf("{\"name\": \"a\"}", "{\"name\": \"b\"}"),
            listOf(
                Message(Role.USER, "one"),
                Message(Role.ASSISTANT, " first answer "),
                Message(Role.TOOL, "r1"),
                Message(Role.TOOL, "r2"),
                Message(Role.USER, "two"),
            ),
        )
        assertTrue(p, p.startsWith("<|im_start|>system\nSys\n\n# Tools\n\nYou have access to the following functions:\n\n<tools>\n{\"name\": \"a\"}\n{\"name\": \"b\"}\n</tools>\n\nIf you choose to call a function ONLY reply"))
        assertTrue(p, p.contains("</IMPORTANT><|im_end|>\n<|im_start|>user\none<|im_end|>\n"))
        assertTrue(p, p.contains("<|im_start|>assistant\n<think></think>first answer<|im_end|>\n"))
        assertTrue(p, p.contains("<|im_start|>user\n<tool_response>\nr1\n</tool_response>\n<tool_response>\nr2\n</tool_response>\n<|im_end|>\n"))
        assertTrue(p, p.endsWith("<|im_start|>user\ntwo<|im_end|>\n<|im_start|>assistant\n<think></think>"))
    }

    @Test fun readsToolCalls() {
        val reply = "Sure.\n<tool_call>\n<function=wifi>\n<parameter=state>\noff\n</parameter>\n</function>\n</tool_call>\n" +
            "<tool_call>\n<function=send_sms>\n<parameter=contact>\nSam\n</parameter>\n<parameter=message>\nLine one\nline two\n</parameter>\n</function>\n</tool_call>"
        assertEquals(
            listOf(
                ToolCall("wifi", mapOf("state" to "off")),
                ToolCall("send_sms", mapOf("contact" to "Sam", "message" to "Line one\nline two")),
            ),
            GranitePrompt.toolCalls(reply),
        )
        assertEquals(emptyList<ToolCall>(), GranitePrompt.toolCalls("Just text."))
    }

    @Test fun visibleTextHidesToolCallsAndThinking() {
        assertEquals("Sure.", GranitePrompt.visibleText("Sure.\n<tool_call>\n<function=wifi>"))
        assertEquals("Hello", GranitePrompt.visibleText("<think>hmm</think>Hello"))
        assertEquals("Working", GranitePrompt.visibleText("Working <tool_c"))
        assertEquals("a < b", GranitePrompt.visibleText("a < b"))
        assertEquals("", GranitePrompt.visibleText("<think>still thinking"))
    }
}
