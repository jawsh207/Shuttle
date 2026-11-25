package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeatherTest {

    private val today = LocalDate.of(2026, 11, 25)

    private val forecast = """
        {"latitude":40.7,"longitude":-74.0,"current":{"time":"2026-11-25T14:30","temperature_2m":48.2,
        "apparent_temperature":43.9,"weather_code":3,"wind_speed_10m":9.1,"relative_humidity_2m":60},
        "daily":{"time":["2026-11-25","2026-11-26","2026-11-27"],"weather_code":[3,61,0],
        "temperature_2m_max":[51.3,47.0,40.2],"temperature_2m_min":[39.8,41.1,30.0],
        "precipitation_probability_max":[5,80,0]}}
    """.trimIndent()

    @Test fun parsesJson() {
        val v = Json.parse("""{"a":[1,2.5,"x\né"],"b":{"c":true,"d":null}}""")
        assertEquals(listOf(1.0, 2.5, "x\né"), v.field("a"))
        assertEquals(true, v.field("b").field("c"))
        assertNull(Json.parse("{bad"))
    }

    @Test fun today() {
        assertEquals(
            "It's 48°F and cloudy in New York, feels like 44°F. Today: cloudy, high 51°F, low 40°F.",
            Weather.report(forecast, "New York", "today", today, fahrenheit = true),
        )
    }

    @Test fun tomorrowAndWeek() {
        assertEquals(
            "Tomorrow in New York: light rain, high 47°F, low 41°F, 80% chance of rain.",
            Weather.report(forecast, "New York", "2026-11-26", today, fahrenheit = true),
        )
        val week = Weather.report(forecast, "New York", "week", today, fahrenheit = true)!!
        assertTrue(week, week.contains("- **Friday:** clear, high 40°F, low 30°F"))
        assertEquals("I only have the forecast for the next 7 days.", Weather.report(forecast, "New York", "2026-12-20", today, true))
    }

    @Test fun geocoding() {
        val json = """{"results":[{"name":"Portland","latitude":43.66,"longitude":-70.25,"country":"United States","admin1":"Maine"}]}"""
        assertEquals(Weather.Place("Portland, Maine", 43.66, -70.25), Weather.place(json))
        assertNull(Weather.place("""{"generationtime_ms":0.5}"""))
        assertTrue(Weather.geocodeUrl("São Paulo").endsWith("name=S%C3%A3o%20Paulo"))
    }
}
