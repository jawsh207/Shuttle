package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The fast-path phrasings, and the ones it must leave for the model. */
class CommandParserTest {

    private fun call(name: String, vararg args: Pair<String, String>) = ToolCall(name, mapOf(*args))
    private fun check(text: String, expected: ToolCall?) = assertEquals(text, expected, CommandParser.parse(text))

    @Test fun switches() {
        check("Turn on the flashlight", call("flashlight", "state" to "on"))
        check("flashlight off", call("flashlight", "state" to "off"))
        check("torch on please", call("flashlight", "state" to "on"))
        check("toggle flashlight", call("flashlight", "state" to "toggle"))
        check("Hey Shuttle, turn off Wi-Fi.", call("wifi", "state" to "off"))
        check("switch wifi on", call("wifi", "state" to "on"))
        check("enable bluetooth", call("bluetooth", "state" to "on"))
        check("turn off mobile data", call("mobile_data", "state" to "off"))
        check("airplane mode on", call("airplane_mode", "state" to "on"))
        check("turn on wifi hotspot", call("hotspot", "state" to "on"))
        check("turn on dark mode", call("dark_mode", "state" to "on"))
        check("turn on do not disturb", call("do_not_disturb", "state" to "on"))
        check("dnd off", call("do_not_disturb", "state" to "off"))
        check("disable auto-rotate", call("auto_rotate", "state" to "off"))
        check("turn off adaptive brightness", call("auto_brightness", "state" to "off"))
        check("can you turn the location off", call("location", "state" to "off"))
    }

    @Test fun ringer() {
        check("silence my phone", call("ringer_mode", "mode" to "silent"))
        check("put my phone on vibrate", call("ringer_mode", "mode" to "vibrate"))
        check("vibrate mode", call("ringer_mode", "mode" to "vibrate"))
        check("silent mode off", call("ringer_mode", "mode" to "normal"))
        check("unmute my phone", call("ringer_mode", "mode" to "normal"))
    }

    @Test fun brightnessAndVolume() {
        check("brightness 40%", call("brightness", "level" to "40"))
        check("set brightness to 75 percent", call("brightness", "level" to "75"))
        check("set the brightness to max", call("brightness", "level" to "max"))
        check("turn up the brightness", call("brightness", "level" to "up"))
        check("dim the screen", call("brightness", "level" to "down"))
        check("volume up", call("volume", "action" to "up"))
        check("turn it down", call("volume", "action" to "down"))
        check("set volume to 30", call("volume", "action" to "set", "percent" to "30"))
        check("mute the music", call("volume", "action" to "mute"))
    }

    @Test fun media() {
        check("play", call("media", "action" to "play"))
        check("pause the music", call("media", "action" to "pause"))
        check("skip this song", call("media", "action" to "next"))
        check("next track", call("media", "action" to "next"))
        check("previous song", call("media", "action" to "previous"))
    }

    @Test fun alarmsAndTimers() {
        check("set an alarm for 7:30 am", call("set_alarm", "hour" to "7", "minute" to "30"))
        check("wake me up at 6", call("set_alarm", "hour" to "6", "minute" to "0", "ambiguous" to "true"))
        check("alarm for 18:45", call("set_alarm", "hour" to "18", "minute" to "45"))
        check("set alarm for seven thirty pm", call("set_alarm", "hour" to "19", "minute" to "30"))
        check("set a timer for 5 minutes", call("set_timer", "seconds" to "300"))
        check("timer 90 seconds", call("set_timer", "seconds" to "90"))
        check("start a ten minute timer", call("set_timer", "seconds" to "600"))
        check("set a timer for an hour and a half", call("set_timer", "seconds" to "5400"))
        check("timer for half an hour", call("set_timer", "seconds" to "1800"))
    }

    @Test fun callsAndTexts() {
        check("call Mom", call("call", "contact" to "Mom"))
        check("Call Jo Smith.", call("call", "contact" to "Jo Smith"))
        check("dial 555 123 4567", call("call", "contact" to "555 123 4567"))
        check("give Sam a call", call("call", "contact" to "Sam"))
        check("text Sam saying I'm running late", call("send_sms", "contact" to "Sam", "message" to "I'm running late"))
        check("send a message to Mom that I'll be home soon", call("send_sms", "contact" to "Mom", "message" to "I'll be home soon"))
        check("text Alex: on my way", call("send_sms", "contact" to "Alex", "message" to "on my way"))
        check("text Sam I'm running late", call("send_sms", "text" to "Sam I'm running late"))
        check("tell Jamie dinner is ready", call("send_sms", "text" to "Jamie dinner is ready"))
    }

    @Test fun appsPlacesAndStatus() {
        check("open camera", call("open_app", "name" to "camera"))
        check("launch the Signal app", call("open_app", "name" to "Signal"))
        check("navigate to the nearest pharmacy", call("navigate", "destination" to "the nearest pharmacy"))
        check("take me to 10 Main Street", call("navigate", "destination" to "10 Main Street"))
        check("what's my battery level", call("battery_status"))
        check("am I charging", call("battery_status"))
        check("what time is it", call("date_time", "what" to "time"))
        check("what's the date today", call("date_time", "what" to "date"))
    }

    @Test fun leavesOpenQuestionsForTheModel() {
        check("what's the weather like in Boston", null)
        check("tell me a joke", null)
        check("who won the game last night", null)
        check("call me later", null)
        check("", null)
    }

    @Test fun everyCallNamesARealTool() {
        val phrases = listOf("flashlight on", "silence my phone", "brightness 40%", "volume up", "play",
            "set a timer for 5 minutes", "set an alarm for 7 am", "call Mom", "text Sam: hi", "open camera",
            "navigate to work", "battery", "what time is it", "wifi off", "hotspot on")
        for (p in phrases) {
            val name = CommandParser.parse(p)?.name
            assertEquals(p, true, name != null && ToolSchemas.find(name) != null)
        }
    }

    @Test fun normalizeStripsFiller() {
        assertEquals("turn on wifi", CommandParser.normalize("Hey Shuttle, can you turn on Wi Fi for me please?"))
        assertNull(CommandParser.parse("   "))
    }
}
