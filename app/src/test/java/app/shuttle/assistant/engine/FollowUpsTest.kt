package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FollowUpsTest {

    private val torchOn = ToolCall("flashlight", mapOf("state" to "on"))
    private val torchOff = ToolCall("flashlight", mapOf("state" to "off"))
    private fun torch(state: String) = ToolCall("flashlight", mapOf("state" to state))

    @Test fun pronounsActOnTheLastCommand() {
        assertEquals(torch("off"), FollowUps.resolve("turn it off", torchOn))
        assertEquals(torch("on"), FollowUps.resolve("switch it back on", torchOff))
        assertEquals(torch("off"), FollowUps.resolve("Turn that off please", torchOn))
        assertEquals(torch("on"), FollowUps.resolve("back on", torchOff))
        assertEquals(torch("off"), FollowUps.resolve("off again", torchOn))
        assertEquals(torch("off"), FollowUps.resolve("shut it off", torchOn))
        assertEquals(ToolCall("wifi", mapOf("state" to "on")), FollowUps.resolve("turn it on", ToolCall("wifi", mapOf("state" to "off"))))
    }

    @Test fun complaintsMeanDoItProperly() {
        assertEquals(torch("off"), FollowUps.resolve("it's still on", torchOff))
        assertEquals(torch("off"), FollowUps.resolve("It’s still on!", torchOn))
        assertEquals(torch("on"), FollowUps.resolve("it's still off", torchOn))
        assertEquals(torch("off"), FollowUps.resolve("it didn't turn off", torchOff))
        assertEquals(torch("on"), FollowUps.resolve("it's not on", torchOn))
    }

    @Test fun retries() {
        assertEquals(torchOff, FollowUps.resolve("that didn't work", torchOff))
        assertEquals(torchOff, FollowUps.resolve("try again", torchOff))
        val timer = ToolCall("set_timer", mapOf("seconds" to "300"))
        assertEquals(timer, FollowUps.resolve("do it again", timer))
        // A confirmed call is asked about again rather than redone silently.
        assertEquals(ToolCall("call", mapOf("contact" to "Sam")), FollowUps.resolve("try again", ToolCall("call", mapOf("contact" to "Sam", CONFIRMED to "true"))))
    }

    @Test fun leavesEverythingElse() {
        assertNull(FollowUps.resolve("turn it off", null))
        assertNull(FollowUps.resolve("why is the sky blue", torchOn))
        assertNull(FollowUps.resolve("turn it off", ToolCall("set_timer", mapOf("seconds" to "60"))))
        assertNull(FollowUps.resolve("turn on wifi", torchOn))
    }
}
