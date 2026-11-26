package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WeatherTest {

    private val today = LocalDate.of(2026, 11, 25)

    private val nwsForecast = """
        {"properties":{"periods":[
          {"startTime":"2026-11-25T06:00:00-05:00","isDaytime":true,"temperature":51,"temperatureUnit":"F","shortForecast":"Mostly Cloudy","probabilityOfPrecipitation":{"value":10}},
          {"startTime":"2026-11-25T18:00:00-05:00","isDaytime":false,"temperature":40,"temperatureUnit":"F","shortForecast":"Cloudy","probabilityOfPrecipitation":{"value":20}},
          {"startTime":"2026-11-26T06:00:00-05:00","isDaytime":true,"temperature":47,"temperatureUnit":"F","shortForecast":"Light Rain","probabilityOfPrecipitation":{"value":80}},
          {"startTime":"2026-11-26T18:00:00-05:00","isDaytime":false,"temperature":41,"temperatureUnit":"F","shortForecast":"Rain","probabilityOfPrecipitation":{"value":70}}
        ]}}
    """.trimIndent()
    private val nwsHourly = """{"properties":{"periods":[{"startTime":"2026-11-25T14:00:00-05:00","isDaytime":true,"temperature":48,"temperatureUnit":"F","windSpeed":"5 to 10 mph","shortForecast":"Cloudy"}]}}"""

    private val met = """
        {"properties":{"timeseries":[
          {"time":"2026-11-25T12:00:00Z","data":{"instant":{"details":{"air_temperature":9.0,"wind_speed":2.0}},
            "next_1_hours":{"summary":{"symbol_code":"cloudy"}},"next_6_hours":{"summary":{"symbol_code":"cloudy"},"details":{"precipitation_amount":0.0}}}},
          {"time":"2026-11-25T18:00:00Z","data":{"instant":{"details":{"air_temperature":6.0}},
            "next_6_hours":{"summary":{"symbol_code":"lightrain"},"details":{"precipitation_amount":1.2}}}},
          {"time":"2026-11-26T12:00:00Z","data":{"instant":{"details":{"air_temperature":11.0}},
            "next_6_hours":{"summary":{"symbol_code":"rainshowersandthunder_day"},"details":{"precipitation_amount":4.0}}}},
          {"time":"2026-11-26T18:00:00Z","data":{"instant":{"details":{"air_temperature":4.0}},
            "next_6_hours":{"summary":{"symbol_code":"partlycloudy_night"},"details":{"precipitation_amount":0.0}}}}
        ]}}
    """.trimIndent()

    @Test fun parsesJson() {
        val v = Json.parse("""{"a":[1,2.5,"x\né"],"b":{"c":true,"d":null}}""")
        assertEquals(listOf(1.0, 2.5, "x\né"), v.field("a"))
        assertEquals(true, v.field("b").field("c"))
        assertNull(Json.parse("{bad"))
    }

    @Test fun nationalWeatherService() {
        assertEquals(
            "https://api.weather.gov/gridpoints/BOX/71,90/forecast" to "https://api.weather.gov/gridpoints/BOX/71,90/forecast/hourly",
            Weather.nwsLinks("""{"properties":{"forecast":"https://api.weather.gov/gridpoints/BOX/71,90/forecast","forecastHourly":"https://api.weather.gov/gridpoints/BOX/71,90/forecast/hourly"}}"""),
        )
        val f = Weather.nws(nwsForecast, nwsHourly)!!
        assertEquals(
            "It's 48°F and cloudy in Boston, Massachusetts. Today: mostly cloudy, high 51°F, low 40°F, 20% chance of rain.",
            Weather.report(f, "Boston, Massachusetts", "today", today, fahrenheit = true),
        )
        assertEquals(
            "Tomorrow in Boston, Massachusetts: light rain, high 47°F, low 41°F, 80% chance of rain.",
            Weather.report(f, "Boston, Massachusetts", "2026-11-26", today, fahrenheit = true),
        )
        assertEquals("National Weather Service", f.sourceName)
    }

    @Test fun metNorway() {
        val f = Weather.met(met, ZoneId.of("Europe/London"))!!
        assertEquals(
            "It's 9°C and cloudy in London, United Kingdom. Today: cloudy, high 9°C, low 6°C, about 1 mm of rain.",
            Weather.report(f, "London, United Kingdom", "today", today, fahrenheit = false),
        )
        val week = Weather.report(f, "London, United Kingdom", "week", today, fahrenheit = false)
        assertTrue(week, week.contains("- **Tomorrow:** rain showers and thunder, high 11°C, low 4°C, about 4 mm of rain"))
        assertEquals("I only have the forecast for the next 2 days.", Weather.report(f, "London", "2026-12-20", today, false))
    }

    @Test fun symbols() {
        assertEquals("light snow showers", Weather.metDescribe("lightsnowshowers_night"))
        assertEquals("heavy rain and thunder", Weather.metDescribe("heavyrainandthunder"))
        assertEquals("mostly clear", Weather.metDescribe("fair_day"))
    }

    @Test fun whereTheNwsForecasts() {
        assertTrue(Weather.inUs(40.71, -74.0))
        assertTrue(Weather.inUs(61.2, -149.9))
        assertEquals(false, Weather.inUs(51.5, -0.12))
    }
}
