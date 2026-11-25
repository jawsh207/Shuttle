package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class InstantAnswersTest {

    private fun a(q: String) = InstantAnswers.answer(q, now)
    private val now = ZonedDateTime.of(2026, 11, 25, 14, 30, 0, 0, ZoneId.of("America/New_York"))

    @Test fun arithmetic() {
        assertEquals("84", a("what's 12 times 7"))
        assertEquals("12", a("15% of 80"))
        assertEquals("40", a("20 percent off 50"))
        assertEquals("14", a("(3 + 4) * 2"))
        assertEquals("12", a("square root of 144"))
        assertEquals("0.3", a("0.1 + 0.2"))
        assertEquals("1,024", a("2 to the power of 10"))
        assertEquals("25", a("five squared"))
        assertEquals("2.5", a("what is 10 divided by 4?"))
        assertEquals("You can't divide by zero.", a("5 / 0"))
        assertEquals("1,234,567", a("1,234,566 + 1"))
    }

    @Test fun conversions() {
        assertEquals("5 miles is 8.047 km.", a("5 miles in km"))
        assertEquals("1 pound is 16 ounces.", a("how many ounces in a pound"))
        assertEquals("350°F is 176.7°C.", a("350 f to c"))
        assertEquals("1 litre is 4.227 cups.", a("how many cups in a liter"))
        assertEquals("2 cups is 473.2 ml.", a("convert 2 cups to ml"))
        assertEquals("100°C is 212°F.", a("100 degrees celsius in fahrenheit"))
    }

    @Test fun worldClock() {
        assertEquals("It's 4:30 AM Thursday in Tokyo.", a("what time is it in Tokyo"))
        assertEquals("It's 7:30 PM in London.", a("time in london"))
        assertEquals("It's 11:30 AM in Los Angeles.", a("what's the time in los angeles?"))
        assertEquals("It's 19:30 in London.", InstantAnswers.answer("time in london", now, use24h = true))
    }

    @Test fun leavesEverythingElse() {
        assertNull(a("what time is it"))
        assertNull(a("why is the sky blue"))
        assertNull(a("42"))
        assertNull(a("set a timer for 5 minutes"))
        assertNull(a("what time is it in narnia"))
        assertNull(a("turn on wifi and bluetooth"))
        assertNull(a("text sam 5 minutes"))
    }
}
