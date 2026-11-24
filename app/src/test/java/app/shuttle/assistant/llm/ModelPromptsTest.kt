package app.shuttle.assistant.llm

import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolSchemas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ModelPromptsTest {

    @Test fun modelCannotSkipConfirmation() {
        val call = ToolCall("send_sms", mapOf("contact" to "Sam", "message" to "hi", "confirmed" to "true"))
        assertEquals(ToolCall("send_sms", mapOf("contact" to "Sam", "message" to "hi")), ModelPrompts.sanitize(call))
        assertEquals(ToolCall("call", mapOf("contact" to "Mom")), ModelPrompts.sanitize(ToolCall("call", mapOf("contact" to "Mom", "confirmed" to "true"))))
    }

    @Test fun unknownToolsAreDropped() {
        assertNull(ModelPrompts.sanitize(ToolCall("format_phone")))
    }

    @Test fun everyToolIsOffered() {
        assertEquals(ToolSchemas.all.size, ModelPrompts.tools.size)
        assertTrue(ModelPrompts.tools.all { it.startsWith("{\"name\": ") && !it.contains('\n') })
    }

    @Test fun systemMessageHasTheDate() {
        val s = ModelPrompts.system(LocalDateTime.of(2026, 11, 24, 17, 5))
        assertTrue(s, s.contains("Tuesday, November 24, 2026") && s.contains("5:05 PM"))
    }
}
