package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRoutinesTest {

    private val facts = listOf(
        "My wife's name is sam",
        "My boss is called Dana Lee",
        "Alex is my brother",
        "My gym locker is 214",
        "My mom is Rita",
        "My dog is home alone on Fridays",
    )

    @Test fun relationsBecomeNames() {
        assertEquals("Sam", Memory.resolve("my wife", facts))
        assertEquals("Sam", Memory.resolve("wife", facts))
        assertEquals("Dana Lee", Memory.resolve("boss", facts))
        assertEquals("Alex", Memory.resolve("my brother", facts))
        assertEquals("Rita", Memory.resolve("Mom", facts))
        assertEquals("dog", Memory.resolve("dog", facts))
        assertEquals("Jordan", Memory.resolve("Jordan", facts))
    }

    @Test fun factsForTheModel() {
        assertEquals("What the user asked you to remember: My gym locker is 214; My mom is Rita.", Memory.forModel(facts.subList(3, 5)))
        assertEquals("", Memory.forModel(emptyList()))
        assertTrue(Memory.forModel(List(100) { "Fact number $it is long enough to matter" }).length < 1300)
    }

    @Test fun forgetting() {
        assertEquals(listOf("My gym locker is 214"), Memory.matching(facts, "my gym locker"))
        assertEquals(emptyList<String>(), Memory.matching(facts, "the"))
    }

    @Test fun routines() {
        val night = Routines.Routine("Good night", listOf("do not disturb on", "set brightness to 10%", "set an alarm for 7am", "make me a sandwich"))
        val routines = listOf(night, Routines.Routine("Leaving work", listOf("turn off wifi")))
        assertEquals(night, Routines.match("good night", routines))
        assertEquals(night, Routines.match("Run my good night routine", routines))
        assertEquals("Leaving work", Routines.match("start leaving work", routines)?.phrase)
        assertNull(Routines.match("good morning", routines))
        val (calls, unknown) = Routines.calls(night)
        assertEquals(listOf("do_not_disturb", "brightness", "set_alarm"), calls.map { it.name })
        assertEquals(listOf("make me a sandwich"), unknown)
    }
}
