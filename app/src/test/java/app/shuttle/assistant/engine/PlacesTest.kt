package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesTest {

    private val lines = """
        # name	ascii	admin1 code	admin1	country	lat	lon	population	time zone
        New York City	New York City	NY	New York	US	40.7143	-74.0060	8804190	America/New_York
        Paris	Paris	11	Île-de-France	FR	48.8534	2.3488	2138551	Europe/Paris
        Portland	Portland	OR	Oregon	US	45.5234	-122.6762	652503	America/Los_Angeles
        Saint Louis	Saint Louis	MO	Missouri	US	38.6273	-90.1979	301578	America/Chicago
        Portland	Portland	ME	Maine	US	43.6574	-70.2589	66881	America/New_York
        Paris	Paris	TX	Texas	US	33.6609	-95.5555	24782	America/Chicago
        Portland	Portland	ENG	England	GB	50.5675	-2.4447	12710	Europe/London
        Zürich	Zurich	ZH	Zurich	CH	47.3667	8.5500	341730	Europe/Zurich
    """.trimIndent().lines()

    private val countries = mapOf("US" to "United States", "FR" to "France", "GB" to "United Kingdom", "CH" to "Switzerland")

    private fun find(q: String, home: String = "US") = Places.find(q, lines.asSequence(), countries, home)?.display(countries)

    @Test fun biggestFirstPreferringHome() {
        assertEquals("Portland, Oregon", find("Portland"))
        assertEquals("Paris, France", find("paris"))
        assertEquals("New York City, New York", find("NYC"))
        assertEquals("New York City, New York", find("new york"))
    }

    @Test fun regionsNarrowItDown() {
        assertEquals("Portland, Maine", find("Portland, Maine"))
        assertEquals("Portland, Maine", find("portland me"))
        assertEquals("Paris, Texas", find("Paris Texas"))
        assertEquals("Portland, United Kingdom", find("Portland England"))
        assertEquals("Portland, United Kingdom", find("portland uk"))
    }

    @Test fun spellingsAndAccents() {
        assertEquals("Saint Louis, Missouri", find("St. Louis"))
        assertEquals("Zürich, Switzerland", find("zurich"))
        assertNull(find("Narnia"))
    }
}
