package app.shuttle.assistant.engine

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The weather, from Open-Meteo (open-meteo.com): free, keyless, open source and run without
 * ads or tracking. This file builds its request addresses and turns its answers into a short
 * report; fetching (and the phone's location, when no place is named) is in
 * `tools/WeatherTool.kt`. Pure Kotlin, so it's unit-tested.
 *
 * Units follow the phone's region: Fahrenheit and mph in the US (and the few other places
 * that use them), Celsius and km/h elsewhere.
 */
object Weather {

    data class Place(val name: String, val latitude: Double, val longitude: Double)

    /** Whether the region uses Fahrenheit. */
    fun fahrenheit(locale: Locale = Locale.getDefault()): Boolean =
        locale.country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

    fun geocodeUrl(place: String, language: String = "en"): String =
        "https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&language=$language&name=" + encode(place)

    fun forecastUrl(latitude: Double, longitude: Double, fahrenheit: Boolean): String =
        "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7" +
            if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""

    private fun encode(s: String): String = buildString {
        for (b in s.trim().toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() && b >= 0 || c in "-_.~") append(c) else append("%%%02X".format(b.toInt() and 0xff))
        }
    }

    /** The first place in a geocoding answer, named with its region ("Portland, Maine"). */
    fun place(json: String): Place? {
        val first = (Json.parse(json).field("results") as? List<*>)?.firstOrNull() ?: return null
        val name = first.field("name") as? String ?: return null
        val region = (first.field("admin1") as? String) ?: (first.field("country") as? String)
        val lat = first.field("latitude") as? Double ?: return null
        val lon = first.field("longitude") as? Double ?: return null
        return Place(if (region != null && region != name) "$name, $region" else name, lat, lon)
    }

    /** What WMO weather codes mean, in words. */
    fun describe(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80, 81 -> "rain showers"
        82 -> "heavy rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "unsettled"
    }

    /**
     * A report from a forecast answer for [day]: "today" (now and the day's range), a date
     * (that day's forecast), or "week" (the next days in brief). Null for a malformed answer.
     */
    fun report(json: String, placeName: String, day: String, today: LocalDate, fahrenheit: Boolean): String? {
        val root = Json.parse(json) ?: return null
        val deg = if (fahrenheit) "°F" else "°C"
        val wind = if (fahrenheit) "mph" else "km/h"
        val daily = root.field("daily")
        val dates = (daily.field("time") as? List<*>)?.mapNotNull { (it as? String)?.let(LocalDate::parse) } ?: return null
        fun list(name: String) = (daily.field(name) as? List<*>)?.map { (it as? Double) }.orEmpty()
        val codes = list("weather_code")
        val highs = list("temperature_2m_max")
        val lows = list("temperature_2m_min")
        val rain = list("precipitation_probability_max")
        fun t(v: Double?) = v?.roundToInt()?.let { "$it$deg" } ?: "?"
        fun dayLine(i: Int): String {
            val chance = rain.getOrNull(i)?.roundToInt()?.takeIf { it >= 20 }?.let { ", $it% chance of rain" }.orEmpty()
            return "${describe(codes.getOrNull(i)?.toInt() ?: -1)}, high ${t(highs.getOrNull(i))}, low ${t(lows.getOrNull(i))}$chance"
        }
        fun dayName(d: LocalDate) = when (d) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        }
        return when (day) {
            "week" -> "This week in $placeName:\n" + dates.indices.joinToString("\n") { i -> "- **${dayName(dates[i])}:** ${dayLine(i)}" }
            "today" -> {
                val current = root.field("current")
                val temp = current.field("temperature_2m") as? Double
                val feels = current.field("apparent_temperature") as? Double
                val code = (current.field("weather_code") as? Double)?.toInt()
                val windSpeed = (current.field("wind_speed_10m") as? Double)?.roundToInt()
                val i = dates.indexOf(today).coerceAtLeast(0)
                val now = if (temp != null && code != null) {
                    val feelsText = if (feels != null && (feels - temp).let { kotlin.math.abs(it) } >= 3) ", feels like ${t(feels)}" else ""
                    "It's ${t(temp)} and ${describe(code)} in $placeName$feelsText" + (windSpeed?.takeIf { it >= 15 }?.let { ", wind $it $wind" } ?: "") + ". "
                } else ""
                now + "Today: ${dayLine(i)}."
            }
            else -> {
                val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return null
                val i = dates.indexOf(date)
                if (i < 0) return "I only have the forecast for the next 7 days."
                "${dayName(date)} in $placeName: ${dayLine(i)}."
            }
        }
    }
}
