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

    // --- Everyday wording ------------------------------------------------------------------

    @Test fun moreWaysToSwitch() {
        check("kill the wifi", call("wifi", "state" to "off"))
        check("shut off bluetooth", call("bluetooth", "state" to "off"))
        check("shut the wifi off", call("wifi", "state" to "off"))
        check("cut the data", call("mobile_data", "state" to "off"))
        check("turn off the internet", call("mobile_data", "state" to "off"))
        check("put the flashlight on", call("flashlight", "state" to "on"))
        check("flashlight", call("flashlight", "state" to "toggle"))
        check("lights off", call("flashlight", "state" to "off"))
        check("turn on my phone's flashlight", call("flashlight", "state" to "on"))
        check("I need some light", call("flashlight", "state" to "on"))
        check("um can you turn the torch on real quick", call("flashlight", "state" to "on"))
        check("get bluetooth back on", call("bluetooth", "state" to "on"))
        check("start do not disturb", call("do_not_disturb", "state" to "on"))
        check("don't disturb me", call("do_not_disturb", "state" to "on"))
        check("quiet mode on", call("do_not_disturb", "state" to "on"))
        check("put my phone in airplane mode", call("airplane_mode", "state" to "on"))
        check("take my phone off airplane mode", call("airplane_mode", "state" to "off"))
        check("enable flight mode", call("airplane_mode", "state" to "on"))
        check("switch off GPS", call("location", "state" to "off"))
        check("turn off rotation", call("auto_rotate", "state" to "off"))
        check("Turn on the Wi-Fi, please.", call("wifi", "state" to "on"))
    }

    @Test fun moreWaysForTheRinger() {
        check("put it on silent", call("ringer_mode", "mode" to "silent"))
        check("go silent", call("ringer_mode", "mode" to "silent"))
        check("turn off the ringer", call("ringer_mode", "mode" to "silent"))
        check("switch to vibrate", call("ringer_mode", "mode" to "vibrate"))
        check("vibrate only", call("ringer_mode", "mode" to "vibrate"))
        check("turn the ringer back on", call("ringer_mode", "mode" to "normal"))
        check("take my phone off silent", call("ringer_mode", "mode" to "normal"))
        check("normal mode", call("ringer_mode", "mode" to "normal"))
        check("make my phone ring again", call("ringer_mode", "mode" to "normal"))
    }

    @Test fun moreWaysForBrightness() {
        check("make the screen brighter", call("brightness", "level" to "up"))
        check("the screen is too dark", call("brightness", "level" to "up"))
        check("turn the brightness up a bit", call("brightness", "level" to "up"))
        check("it's too bright", call("brightness", "level" to "down"))
        check("make it darker", call("brightness", "level" to "down"))
        check("lower the brightness a little", call("brightness", "level" to "down"))
        check("full brightness", call("brightness", "level" to "max"))
        check("turn the brightness all the way down", call("brightness", "level" to "min"))
        check("brightness to fifty percent", call("brightness", "level" to "50"))
        check("set the volume to twenty five percent", call("volume", "action" to "set", "percent" to "25"))
        check("volume a hundred percent", call("volume", "action" to "set", "percent" to "100"))
        check("set the brightness to 50%", call("brightness", "level" to "50"))
    }

    @Test fun moreWaysForVolume() {
        check("make it louder", call("volume", "action" to "up"))
        check("I can't hear it", call("volume", "action" to "up"))
        check("turn the music up a bit", call("volume", "action" to "up"))
        check("crank it up", call("volume", "action" to "up"))
        check("that's too loud", call("volume", "action" to "down"))
        check("turn it down a notch", call("volume", "action" to "down"))
        check("max volume", call("volume", "action" to "set", "percent" to "100"))
        check("turn it all the way up", call("volume", "action" to "set", "percent" to "100"))
        check("half volume", call("volume", "action" to "set", "percent" to "50"))
        check("mute it", call("volume", "action" to "mute"))
        check("turn the sound off", call("volume", "action" to "mute"))
    }

    @Test fun moreWaysForMedia() {
        check("resume playing", call("media", "action" to "play"))
        check("put some music on", call("media", "action" to "play"))
        check("hit play", call("media", "action" to "play"))
        check("stop", call("media", "action" to "pause"))
        check("pause it", call("media", "action" to "pause"))
        check("turn off the music", call("media", "action" to "pause"))
        check("skip", call("media", "action" to "next"))
        check("skip to the next song", call("media", "action" to "next"))
        check("change the song", call("media", "action" to "next"))
        check("go back a song", call("media", "action" to "previous"))
        check("play that again", call("media", "action" to "previous"))
    }

    @Test fun moreWaysForAlarms() {
        check("wake me up at 7 o'clock", call("set_alarm", "hour" to "7", "minute" to "0", "ambiguous" to "true"))
        check("set an alarm for half past six in the morning", call("set_alarm", "hour" to "6", "minute" to "30"))
        check("alarm at quarter to eight", call("set_alarm", "hour" to "7", "minute" to "45", "ambiguous" to "true"))
        check("I need to get up at 5:45 tomorrow", call("set_alarm", "hour" to "5", "minute" to "45", "ambiguous" to "true"))
        check("get me up at 6 tomorrow morning", call("set_alarm", "hour" to "6", "minute" to "0"))
        check("set a 6:30 am alarm", call("set_alarm", "hour" to "6", "minute" to "30"))
        check("set my alarm for twenty past six pm", call("set_alarm", "hour" to "18", "minute" to "20"))
    }

    @Test fun moreWaysForTimers() {
        check("remind me in 10 minutes", call("set_timer", "seconds" to "600"))
        check("wake me up in 20 minutes", call("set_timer", "seconds" to "1200"))
        check("countdown 30 seconds", call("set_timer", "seconds" to "30"))
        check("count down from 2 minutes", call("set_timer", "seconds" to "120"))
        check("timer for 5", call("set_timer", "seconds" to "300"))
        check("give me a 3 minute timer", call("set_timer", "seconds" to "180"))
        check("set a timer for a couple of minutes", call("set_timer", "seconds" to "120"))
        check("set a timer for 1 and a half hours", call("set_timer", "seconds" to "5400"))
        check("time 45 seconds", call("set_timer", "seconds" to "45"))
        check("Uh, set a timer for, like, five minutes", call("set_timer", "seconds" to "300"))
    }

    @Test fun moreWaysToCall() {
        check("call my mom", call("call", "contact" to "mom"))
        check("call up Sam", call("call", "contact" to "Sam"))
        check("call Sam back", call("call", "contact" to "Sam"))
        check("call back Sam", call("call", "contact" to "Sam"))
        check("call Jo's cell", call("call", "contact" to "Jo"))
        check("call Jo on her mobile", call("call", "contact" to "Jo"))
        check("give Dad a ring", call("call", "contact" to "Dad"))
        check("get Alex on the phone", call("call", "contact" to "Alex"))
        check("I want to talk to Grandma", call("call", "contact" to "Grandma"))
        check("let me call Mom", call("call", "contact" to "Mom"))
    }

    @Test fun moreWaysToText() {
        check("send Sam a text saying I'm here", call("send_sms", "contact" to "Sam", "message" to "I'm here"))
        check("shoot Mom a message: running late", call("send_sms", "contact" to "Mom", "message" to "running late"))
        check("let Jamie know I'll be 10 minutes late", call("send_sms", "contact" to "Jamie", "message" to "I'll be 10 minutes late"))
        check("tell Sam that dinner's ready", call("send_sms", "contact" to "Sam", "message" to "dinner's ready"))
        check("text my sister saying call me", call("send_sms", "contact" to "sister", "message" to "call me"))
        check("message Alex to say thanks", call("send_sms", "contact" to "Alex", "message" to "thanks"))
        check("text Sam saying I'm leaving now", call("send_sms", "contact" to "Sam", "message" to "I'm leaving now"))
        check("reply to Jo saying sounds good", call("send_sms", "contact" to "Jo", "message" to "sounds good"))
        check("send Dad a text", call("send_sms", "contact" to "Dad", "message" to ""))
        check("text my mom I'll call you back", call("send_sms", "text" to "mom I'll call you back"))
    }

    @Test fun moreWaysForAppsPlacesAndStatus() {
        check("pull up the calculator", call("open_app", "name" to "calculator"))
        check("take me to the camera app", call("open_app", "name" to "camera"))
        check("go to the settings app", call("open_app", "name" to "settings"))
        check("take me home", call("navigate", "destination" to "home"))
        check("where's the nearest gas station", call("navigate", "destination" to "the nearest gas station"))
        check("coffee shops near me", call("navigate", "destination" to "coffee shops near me"))
        check("find a pharmacy nearby", call("navigate", "destination" to "a pharmacy near me"))
        check("how's my battery", call("battery_status"))
        check("how much juice do I have left", call("battery_status"))
        check("do I need to charge my phone", call("battery_status"))
        check("got the time", call("date_time", "what" to "time"))
        check("do you know what time it is", call("date_time", "what" to "time"))
        check("what's today's date", call("date_time", "what" to "date"))
        check("what day of the week is it", call("date_time", "what" to "date"))
    }

    @Test fun askingWhetherSomethingIsOn() {
        val st = { setting: String -> call("status", "setting" to setting) }
        check("is wifi on?", st("wifi"))
        check("Is the Wi-Fi off", st("wifi"))
        check("am I connected to wifi", st("wifi"))
        check("is bluetooth turned on", st("bluetooth"))
        check("do I have mobile data on", st("mobile_data"))
        check("is my location on", st("location"))
        check("check airplane mode", st("airplane_mode"))
        check("is the hotspot on", st("hotspot"))
        check("is the flashlight still on", st("flashlight"))
        check("is the light on", st("flashlight"))
        check("is dark mode on", st("dark_mode"))
        check("is do not disturb on", st("do_not_disturb"))
        check("am I on DND", st("do_not_disturb"))
        check("is auto-rotate on", st("auto_rotate"))
        check("nfc status", st("nfc"))
        check("am I on silent", st("ringer_mode"))
        check("is my phone on vibrate", st("ringer_mode"))
        check("will my phone ring", st("ringer_mode"))
        check("what's my brightness at", st("brightness"))
        check("how bright is the screen", st("brightness"))
        check("what's the volume at", st("volume"))
        check("how loud is it", st("volume"))
        check("is the music muted", st("volume"))
        check("volume", st("volume"))
        check("what's on", st("all"))
        check("what settings are on", st("all"))
        check("status", st("all"))
        check("give me a status update", st("all"))
        // Changing a setting is still a change, not a question.
        check("wifi on", call("wifi", "state" to "on"))
        check("turn on bluetooth", call("bluetooth", "state" to "on"))
        check("brightness up", call("brightness", "level" to "up"))
    }

    @Test fun severalCommandsAtOnce() {
        assertEquals(
            listOf(call("wifi", "state" to "off"), call("bluetooth", "state" to "off")),
            CommandParser.parseAll("turn off wifi and bluetooth"),
        )
        assertEquals(
            listOf(call("flashlight", "state" to "on"), call("brightness", "level" to "max")),
            CommandParser.parseAll("flashlight on, then full brightness"),
        )
        assertEquals(
            listOf(call("set_alarm", "hour" to "7", "minute" to "0"), call("set_timer", "seconds" to "600")),
            CommandParser.parseAll("set an alarm for 7 am and a timer for 10 minutes"),
        )
        // One command that contains "and" stays whole.
        assertEquals(
            listOf(call("send_sms", "contact" to "Sam", "message" to "milk and eggs")),
            CommandParser.parseAll("text Sam saying milk and eggs"),
        )
        assertEquals(
            listOf(call("set_timer", "seconds" to "5400")),
            CommandParser.parseAll("timer for an hour and a half"),
        )
        // Nothing runs if any part isn't understood.
        assertEquals(emptyList<ToolCall>(), CommandParser.parseAll("turn off wifi and tell me a joke"))
    }

    @Test fun leavesOpenQuestionsForTheModel() {
        check("what's the weather like in Boston", null)
        check("tell me a joke", null)
        check("who won the game last night", null)
        check("call me later", null)
        check("call it a day", null)
        check("play Taylor Swift", null)
        check("stop the alarm", null)
        check("", null)
    }

    @Test fun everyCallNamesARealTool() {
        val phrases = listOf("flashlight on", "silence my phone", "brightness 40%", "volume up", "play",
            "set a timer for 5 minutes", "set an alarm for 7 am", "call Mom", "text Sam: hi", "open camera",
            "navigate to work", "battery", "what time is it", "wifi off", "hotspot on", "is wifi on")
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
