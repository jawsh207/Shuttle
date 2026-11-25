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
    fun readsState() {
        assertEquals(true, QuickTileNames.stateFrom("Wi-Fi, On, Home"))
        assertEquals(false, QuickTileNames.stateFrom("Off"))
        assertNull(QuickTileNames.stateFrom("Screen record"))
        assertNull(QuickTileNames.stateFrom(null))
    }

    @Test
    fun displayName() {
        assertEquals("Battery Saver", QuickTileNames.display("Battery Saver\nOff"))
    }
}
