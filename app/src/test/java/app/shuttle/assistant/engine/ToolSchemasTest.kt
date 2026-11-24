package app.shuttle.assistant.engine

import app.shuttle.assistant.tools.DeviceTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolSchemasTest {

    @Test fun namesAreUnique() {
        val names = ToolSchemas.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun everyToolHasAnImplementation() {
        assertEquals(emptyList<String>(), DeviceTools.missing)
    }

    @Test fun jsonHasTheFunctionCallingShape() {
        val json = ToolSchemas.toJson(listOf(ToolSchemas.find("set_timer")!!))
        assertTrue(json, json.contains("\"name\": \"set_timer\""))
        assertTrue(json, json.contains("\"seconds\": {\"type\": \"integer\""))
        assertTrue(json, json.contains("\"required\": [\"seconds\"]"))
        assertTrue(json, json.trim().startsWith("[") && json.trim().endsWith("]"))
    }

    /**
     * docs/tools.json is the tool list for fine-tuning and testing a tool-calling model. It's
     * generated from [ToolSchemas]; this fails when they drift apart. Regenerate by copying
     * the "actual" value from the failure into the file.
     */
    @Test fun toolsJsonIsCurrent() {
        val file = listOf(File("../docs/tools.json"), File("docs/tools.json")).firstOrNull { it.exists() }
            ?: return // not run from the project tree
        assertEquals(ToolSchemas.toJson(), file.readText())
    }

    @Test fun jsonEscapesQuotes() {
        val json = ToolSchemas.toJson(listOf(ToolSpec("x", "say \"hi\"\n")))
        assertTrue(json, json.contains("say \\\"hi\\\"\\n"))
    }
}
