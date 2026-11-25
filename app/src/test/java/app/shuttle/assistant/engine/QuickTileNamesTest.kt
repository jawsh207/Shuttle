package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickTileNamesTest {

    @Test
    fun matchesTileLabels() {
        val labels = listOf("Internet, Off", "Bluetooth\nOn", "Battery Saver", "Flashlight", "Dark theme, On")
        assertEquals(0, QuickTileNames.best(labels, QuickTileNames.forSwitch.getValue("wifi")))
        assertEquals(1, QuickTileNames.best(labels, listOf("bluetooth")))
        assertEquals(2, QuickTileNames.best(labels, listOf("battery saver")))
        assertEquals(4, QuickTileNames.best(labels, QuickTileNames.forSwitch.getValue("dark_mode")))
        assertNull(QuickTileNames.best(labels, listOf("night light")))
    }

    @Test
    fun exactBeatsPartial() {
        val labels = listOf("Wi-Fi hotspot", "Wi‑Fi")
        assertEquals(1, QuickTileNames.best(labels, listOf("Wi-Fi")))
        assertEquals(0, QuickTileNames.best(labels, QuickTileNames.forSwitch.getValue("hotspot")))
    }

    @Test
    fun realTilesBeatStatusIcons() {
        // The status bar's Wi-Fi icon matches "Wi-Fi" exactly; the Internet tile is the one to tap.
        val labels = listOf("Wi-Fi, signal full", "Bluetooth", "Internet, Home", "Bluetooth, On")
        val tile = setOf(2, 3)
        val bonus = { i: Int -> if (i in tile) 15 else 0 }
        assertEquals(2, QuickTileNames.ranked(labels, QuickTileNames.forSwitch.getValue("wifi"), bonus).first())
        assertEquals(3, QuickTileNames.ranked(labels, listOf("Bluetooth"), bonus).first())
        // Every match is kept, so a failed tap can try the next one.
        assertEquals(listOf(3, 1), QuickTileNames.ranked(labels, listOf("Bluetooth"), bonus))
    }

    @Test
    fun mobileDataTileNamedAfterTheCarrier() {
        assertEquals(true, QuickTileNames.looksLikeMobileData("Mint, LTE, On"))
        assertEquals(true, QuickTileNames.looksLikeMobileData("T-Mobile\n5G"))
        assertEquals(false, QuickTileNames.looksLikeMobileData("Wi-Fi, On, Home 5G"))
        assertEquals(false, QuickTileNames.looksLikeMobileData("Battery Saver"))
        // "Internet" is no longer one of mobile data's own names.
        assertNull(QuickTileNames.best(listOf("Internet, On"), QuickTileNames.forSwitch.getValue("mobile_data")))
    }

    @Test
    fun readsState() {
        assertEquals(true, QuickTileNames.stateFrom("Wi-Fi, On, Home"))
        assertEquals(false, QuickTileNames.stateFrom("Off"))
        assertNull(QuickTileNames.stateFrom("Screen record"))
        assertNull(QuickTileNames.stateFrom(null))
    }

    @Test
    fun displayName() {
        assertEquals("Battery Saver", QuickTileNames.display("Battery Saver\nOff"))
        assertEquals("Bluetooth", QuickTileNames.display("Bluetooth."))
        assertEquals("Bluetooth", QuickTileNames.display("Bluetooth., On"))
    }
}
