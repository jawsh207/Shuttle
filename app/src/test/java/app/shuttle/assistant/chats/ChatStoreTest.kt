package app.shuttle.assistant.chats

import app.shuttle.assistant.chats.ChatStore.Chat
import app.shuttle.assistant.chats.ChatStore.Kind
import app.shuttle.assistant.chats.ChatStore.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChatStoreTest {

    private val awkward = Line("text Sam: hi\tthere\nnew line \\ backslash", Kind.CONFIRM, "Text Sam: \"hi\"\t?")
    private val chat = Chat("c1", "text Sam: hi", 100, 200, listOf(awkward, Line("", Kind.DONE, "Sent to Sam.")))

    @Test fun encodeDecodeRoundTrip() {
        assertEquals(chat, ChatStore.decode(ChatStore.encode(chat)))
    }

    @Test fun rejectsOtherFiles() {
        assertNull(ChatStore.decode("not a chat"))
    }

    @Test fun saveListDelete() {
        val dir = Files.createTempDirectory("chats").toFile()
        try {
            val store = ChatStore(dir)
            store.save(chat)
            store.save(chat.copy(id = "c2", updated = 300))
            File(dir, "junk.chat").writeText("garbage")
            assertEquals(listOf("c2", "c1"), store.list().map { it.id })
            store.delete("c2")
            assertEquals(listOf("c1"), store.list().map { it.id })
            store.save(chat.copy(title = "renamed"))
            assertEquals("renamed", store.list().single().title)
            store.deleteAll()
            assertTrue(store.list().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun titlesAndSearch() {
        assertEquals("New chat", ChatStore.titleFor(emptyList()))
        assertEquals("flashlight on", ChatStore.titleFor(listOf(Line(" flashlight on ", Kind.DONE, "Flashlight on."))))
        assertEquals(60, ChatStore.titleFor(listOf(Line("x".repeat(100), Kind.DONE, ""))).length)

        val other = Chat("c3", "battery", 1, 2, listOf(Line("battery", Kind.DONE, "Battery is at 80%.")))
        val results = ChatStore.search(listOf(chat, other), "80%")
        assertEquals(listOf("c3"), results.map { it.first.id })
        assertEquals("Battery is at 80%.", results.single().second)
        assertEquals(2, ChatStore.search(listOf(chat, other), "").size)
        assertEquals(listOf("c1"), ChatStore.search(listOf(chat, other), "sam").map { it.first.id })
    }
}
