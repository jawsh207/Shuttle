package app.shuttle.assistant.llm

import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolSchemas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ModelPromptsTest {

    @Test fun modelCannotSkipConfirmation() {
        val call = ToolCall("send_sms", mapOf("contact" to "Sam", "message" to "hi", "confirmed" to "true"))
        assertEquals(ToolCall("send_sms", mapOf("contact" to "Sam", "message" to "hi")), ModelPrompts.sanitize(call))
        assertEquals(ToolCall("call", mapOf("contact" to "Mom")), ModelPrompts.sanitize(ToolCall("call", mapOf("contact" to "Mom", "confirmed" to "true"))))
    }

    @Test fun unknownToolsAreDropped() {
        assertNull(ModelPrompts.sanitize(ToolCall("format_phone")))
    }

    @Test fun compactToolsMapToRealOnes() {
        assertEquals(ToolCall("wifi", mapOf("state" to "off")), ModelPrompts.sanitize(ToolCall("set_switch", mapOf("setting" to "wifi", "state" to "off"))))
        assertEquals(ToolCall("status", mapOf("setting" to "bluetooth")), ModelPrompts.sanitize(ToolCall("check_setting", mapOf("setting" to "bluetooth"))))
        assertNull(ModelPrompts.sanitize(ToolCall("set_switch", mapOf("setting" to "send_sms", "state" to "on"))))
        assertEquals(ToolCall("battery_status"), ModelPrompts.sanitize(ToolCall("battery_status")))
    }

    @Test fun toolListIsCompact() {
        assertTrue(ModelPrompts.tools.all { it.startsWith("{\"name\": ") && !it.contains('\n') })
        // Read once per model and then saved, but the first read happens on the phone's CPU,
        // so growth should be a choice: raise this deliberately, not by accident.
        val size = ModelPrompts.prefix().length
        // About 3.5K tokens: read once per model and saved, but it shares the 12K context with
        // the chat, shared text and the reply.
        assertTrue("prefix is $size chars", size < 13500)
    }

    @Test fun promptStartIsStableAndDatedOnly() {
        assertEquals("Today is Tuesday, November 24, 2026.", ModelPrompts.note(LocalDate.of(2026, 11, 24)))
        assertEquals(
            "Today is Tuesday, November 24, 2026. What the user asked you to remember: Likes tea.",
            ModelPrompts.note(LocalDate.of(2026, 11, 24), "What the user asked you to remember: Likes tea."),
        )
        assertTrue(!ModelPrompts.prefix().contains("Today is"))
        val full = GranitePrompt.build(
            ModelPrompts.system(), ModelPrompts.tools, listOf(GranitePrompt.Message(GranitePrompt.Role.USER, "hi")),
            note = ModelPrompts.note(LocalDate.of(2026, 11, 24)),
        )
        assertTrue(full.startsWith(ModelPrompts.prefix() + "<|im_start|>system\nToday is Tuesday, November 24, 2026.<|im_end|>\n<|im_start|>user\nhi"))
    }
}
