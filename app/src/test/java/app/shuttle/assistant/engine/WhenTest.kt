package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WhenTest {

    // A Wednesday afternoon.
    private val now = LocalDateTime.of(2026, 11, 25, 14, 30)

    private fun at(text: String) = When.parse(text, now)?.at

    @Test fun inSomeTime() {
        assertEquals(now.plusMinutes(20), at("in 20 minutes"))
        assertEquals(now.plusHours(1), at("in an hour"))
        assertEquals(now.plusDays(2), at("in 2 days"))
    }

    @Test fun clockTimes() {
        assertEquals(LocalDateTime.of(2026, 11, 25, 18, 0), at("at 6"))
        assertEquals(LocalDateTime.of(2026, 11, 26, 9, 0), at("at 9am"))
        assertEquals(LocalDateTime.of(2026, 11, 25, 20, 0), at("tonight at 8"))
        assertEquals(LocalDateTime.of(2026, 11, 25, 20, 0), at("tonight"))
    }

    @Test fun days() {
        assertEquals(LocalDateTime.of(2026, 11, 26, 9, 0), at("tomorrow at 9"))
        assertEquals(LocalDateTime.of(2026, 11, 26, 9, 0), at("tomorrow morning"))
        assertEquals(LocalDateTime.of(2026, 11, 27, 15, 0), at("friday at 3"))
        assertEquals(LocalDateTime.of(2026, 12, 3, 9, 0), at("december 3rd"))
        assertEquals(false, When.parse("tomorrow", now)?.timeGiven)
        assertEquals(true, When.parse("tomorrow at 9", now)?.timeGiven)
    }

    @Test fun dayOnly() {
        val today = LocalDate.of(2026, 11, 25)
        assertEquals(today, When.day("what's on today", today))
        assertEquals(LocalDate.of(2026, 11, 27), When.day("friday", today))
        assertEquals(LocalDate.of(2026, 12, 2), When.day("next wednesday", today))
        assertEquals(LocalDate.of(2026, 11, 30), When.day("the 30th", today))
    }

    @Test fun notATime() {
        assertNull(at("buy milk"))
        assertNull(at(""))
    }
}
