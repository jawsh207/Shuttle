package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallTalkTest {

    @Test fun socialPhrases() {
        assertEquals("You're welcome.", SmallTalk.reply("Thank you!")?.text)
        assertEquals("You're welcome.", SmallTalk.reply("thanks shuttle")?.text)
        assertTrue(SmallTalk.reply("Hey Shuttle")!!.text.startsWith("Hi"))
        assertEquals(SmallTalk.HELP, SmallTalk.reply("What can you do?")?.text)
        assertEquals(SmallTalk.HELP, SmallTalk.reply("help")?.text)
    }

    @Test fun dismissalsClose() {
        assertEquals(true, SmallTalk.reply("never mind")?.close)
        assertEquals(true, SmallTalk.reply("nevermind")?.close)
        assertEquals(true, SmallTalk.reply("that's all thanks")?.close)
        assertEquals(false, SmallTalk.reply("thanks")?.close)
    }

    @Test fun commandsAreNotSmallTalk() {
        assertNull(SmallTalk.reply("thanks for the reminder, text Sam"))
        assertNull(SmallTalk.reply("what's the weather"))
    }
}
