package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class QuantitiesTest {

    @Test fun numberWords() {
        assertEquals("25 minutes", Quantities.wordsToDigits("twenty five minutes"))
        assertEquals("25 minutes", Quantities.wordsToDigits("twenty-five minutes"))
        assertEquals("7 0 5", Quantities.wordsToDigits("seven oh five"))
        assertEquals("oh well", Quantities.wordsToDigits("oh well"))
    }

    @Test fun clockTimes() {
        assertEquals(Quantities.Clock(7, 30, true), Quantities.parseClock("7:30 am"))
        assertEquals(Quantities.Clock(19, 30, true), Quantities.parseClock("7.30 p.m."))
        assertEquals(Quantities.Clock(7, 30, false), Quantities.parseClock("730"))
        assertEquals(Quantities.Clock(7, 5, false), Quantities.parseClock("seven oh five"))
        assertEquals(Quantities.Clock(0, 0, true), Quantities.parseClock("12 am"))
        assertEquals(Quantities.Clock(12, 0, true), Quantities.parseClock("noon"))
        assertEquals(Quantities.Clock(21, 0, true), Quantities.parseClock("9 tonight"))
        assertEquals(Quantities.Clock(6, 0, true), Quantities.parseClock("6 in the morning"))
        assertEquals(Quantities.Clock(18, 45, true), Quantities.parseClock("18:45"))
        assertNull(Quantities.parseClock("25:00"))
        assertNull(Quantities.parseClock("lunchtime"))
    }

    @Test fun ambiguousHourPicksTheNextOne() {
        val clock = Quantities.Clock(9, 0, meridiemGiven = false)
        assertEquals(9, Quantities.resolveHour(clock, LocalTime.of(22, 0)))
        assertEquals(9, Quantities.resolveHour(clock, LocalTime.of(8, 0)))
        assertEquals(21, Quantities.resolveHour(clock, LocalTime.of(12, 0)))
        assertEquals(12, Quantities.resolveHour(Quantities.Clock(12, 0, false), LocalTime.of(11, 0)))
        assertEquals(0, Quantities.resolveHour(Quantities.Clock(12, 0, false), LocalTime.of(13, 0)))
    }

    @Test fun durations() {
        assertEquals(300, Quantities.parseDuration("5 minutes"))
        assertEquals(300, Quantities.parseDuration("five minutes"))
        assertEquals(5400, Quantities.parseDuration("1 hour 30 minutes"))
        assertEquals(5400, Quantities.parseDuration("an hour and a half"))
        assertEquals(1800, Quantities.parseDuration("half an hour"))
        assertEquals(8100, Quantities.parseDuration("2h 15m"))
        assertEquals(60, Quantities.parseDuration("a minute"))
        assertEquals(3630, Quantities.parseDuration("1 hour and 30 seconds"))
        assertNull(Quantities.parseDuration("a while"))
        assertNull(Quantities.parseDuration("5 minutes for the pasta"))
    }
}
