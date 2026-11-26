package app.shuttle.assistant.engine

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The weather, from two free services that allow any use, commercial included:
 *
 * - **US National Weather Service** (api.weather.gov) for places in the United States: the
 *   official forecast, public domain.
 * - **MET Norway** (api.met.no, the forecasts behind Yr) everywhere else: worldwide, under
 *   CC BY 4.0 (credited under each answer).
 *
 * Neither needs a key; both ask for a User-Agent naming the app. This file reads their
 * answers into one [Forecast] and writes the report; fetching is in `tools/WeatherTool.kt`.
 * Temperatures are kept in °C and wind in km/h, and shown in °F and mph where the region
 * uses them. Pure Kotlin, so it's unit-tested.
 */
object Weather {

    const val USER_AGENT = "Shuttle/0.1 (https://github.com/jawsh207/Shuttle)"

    data class Now(val celsius: Double, val description: String, val windKmh: Double?)
    data class Day(
        val date: LocalDate,
        val description: String,
        val highC: Double?,
        val lowC: Double?,
        val rainChance: Int? = null,
        val rainMm: Double? = null,
    )
    data class Forecast(val now: Now?, val days: List<Day>, val sourceName: String, val sourceUrl: String)

    /** Whether the region uses Fahrenheit (and mph). */
    fun fahrenheit(locale: Locale = Locale.getDefault()): Boolean =
        locale.country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

    /** Roughly the US and its territories, where the National Weather Service forecasts. */
    fun inUs(latitude: Double, longitude: Double): Boolean =
        (latitude in 24.0..50.0 && longitude in -125.0..-66.0) || // lower 48
            (latitude in 51.0..72.0 && longitude in -170.0..-129.0) || // Alaska
            (latitude in 18.5..22.5 && longitude in -161.0..-154.0) || // Hawaii
            (latitude in 17.5..18.6 && longitude in -67.5..-64.5) || // Puerto Rico, Virgin Islands
            (latitude in 13.0..15.5 && longitude in 144.5..146.0) // Guam

    fun nwsPointsUrl(lat: Double, lon: Double) = "https://api.weather.gov/points/${Places.round4(lat)},${Places.round4(lon)}"

    fun metUrl(lat: Double, lon: Double) =
        "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${Places.round4(lat)}&lon=${Places.round4(lon)}"

    private fun fToC(f: Double) = (f - 32) * 5 / 9

    // --- National Weather Service ------------------------------------------------------------

    /** The forecast and hourly forecast addresses for a point, from /points. */
    fun nwsLinks(json: String): Pair<String, String>? {
        val p = Json.parse(json).field("properties")
        val forecast = p.field("forecast") as? String ?: return null
        val hourly = p.field("forecastHourly") as? String ?: return null
        return forecast to hourly
    }

    private fun periods(json: String): List<Any?> = (Json.parse(json).field("properties").field("periods") as? List<*>).orEmpty()

    private fun nwsCelsius(period: Any?): Double? {
        val t = period.field("temperature") as? Double ?: return null
        return if ((period.field("temperatureUnit") as? String) == "C") t else fToC(t)
    }

    /** "5 to 10 mph" → the higher figure, in km/h. */
    private fun nwsWind(text: String?): Double? =
        text?.let { Regex("(\\d+)").findAll(it).mapNotNull { m -> m.value.toDoubleOrNull() }.maxOrNull() }?.let { it * 1.609344 }

    /** The 7-day forecast (day and night periods) and the hourly one (for "now"). */
    fun nws(forecastJson: String, hourlyJson: String?): Forecast? {
        val list = periods(forecastJson)
        if (list.isEmpty()) return null
        data class P(val date: LocalDate, val day: Boolean, val celsius: Double?, val text: String, val rain: Int?)
        val ps = list.mapNotNull { p ->
            val start = (p.field("startTime") as? String)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
            P(
                start.toLocalDate(), p.field("isDaytime") == true, nwsCelsius(p),
                (p.field("shortForecast") as? String).orEmpty(),
                (p.field("probabilityOfPrecipitation").field("value") as? Double)?.roundToInt(),
            )
        }
        val days = ps.groupBy { it.date }.map { (date, group) ->
            val day = group.firstOrNull { it.day }
            val night = group.firstOrNull { !it.day }
            Day(
                date,
                (day ?: night)?.text?.lowercase().orEmpty(),
                day?.celsius, night?.celsius,
                rainChance = listOfNotNull(day?.rain, night?.rain).maxOrNull(),
            )
        }.sortedBy { it.date }
        val hour = hourlyJson?.let { periods(it).firstOrNull() }
        val now = hour?.let { h ->
            nwsCelsius(h)?.let { Now(it, (h.field("shortForecast") as? String).orEmpty().lowercase(), nwsWind(h.field("windSpeed") as? String)) }
        }
        return Forecast(now, days, "National Weather Service", "https://www.weather.gov/")
    }

    // --- MET Norway --------------------------------------------------------------------------

    /** MET Norway's symbol codes ("lightrainshowers_day") in words. */
    fun metDescribe(code: String?): String {
        if (code.isNullOrBlank()) return "unsettled"
        val base = code.substringBefore('_')
        val thunder = "andthunder" in base
        val c = base.replace("andthunder", "")
        val words = when (c) {
            "clearsky" -> "clear"
            "fair" -> "mostly clear"
            "partlycloudy" -> "partly cloudy"
            "cloudy" -> "cloudy"
            "fog" -> "foggy"
            else -> {
                val intensity = when {
                    c.startsWith("light") -> "light "
                    c.startsWith("heavy") -> "heavy "
                    else -> ""
                }
                val rest = c.removePrefix("light").removePrefix("heavy")
                when {
                    rest.startsWith("rainshowers") -> "${intensity}rain showers"
                    rest.startsWith("sleetshowers") -> "${intensity}sleet showers"
                    rest.startsWith("snowshowers") -> "${intensity}snow showers"
                    rest.startsWith("rain") -> "${intensity}rain"
                    rest.startsWith("sleet") -> "${intensity}sleet"
                    rest.startsWith("snow") -> "${intensity}snow"
                    else -> "unsettled"
                }
            }
        }
        return if (thunder) "$words and thunder" else words
    }

    /** The locationforecast "compact" answer, with days in the place's time [zone]. */
    fun met(json: String, zone: ZoneId): Forecast? {
        val series = (Json.parse(json).field("properties").field("timeseries") as? List<*>) ?: return null
        data class E(val at: Instant, val celsius: Double?, val windKmh: Double?, val symbol1: String?, val symbol6: String?, val rain6: Double?)
        val es = series.mapNotNull { e ->
            val at = (e.field("time") as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val data = e.field("data")
            val details = data.field("instant").field("details")
            E(
                at,
                details.field("air_temperature") as? Double,
                (details.field("wind_speed") as? Double)?.let { it * 3.6 },
                data.field("next_1_hours").field("summary").field("symbol_code") as? String,
                data.field("next_6_hours").field("summary").field("symbol_code") as? String,
                data.field("next_6_hours").field("details").field("precipitation_amount") as? Double,
            )
        }
        if (es.isEmpty()) return null
        val first = es.first()
        val now = first.celsius?.let { Now(it, metDescribe(first.symbol1 ?: first.symbol6), first.windKmh) }
        val days = es.groupBy { it.at.atZone(zone).toLocalDate() }.map { (date, group) ->
            val temps = group.mapNotNull { it.celsius }
            // The day's look: the 6-hour summary nearest midday.
            val midday = group.filter { it.symbol6 != null }.minByOrNull { abs(it.at.atZone(zone).hour - 12) }
            // Rain: the 6-hour amounts that start on the six-hour marks, so none is counted twice.
            val rain = group.filter { it.rain6 != null && it.at.atZone(ZoneId.of("UTC")).hour % 6 == 0 }.sumOf { it.rain6!! }
            Day(date, metDescribe(midday?.symbol6 ?: group.firstNotNullOfOrNull { it.symbol1 }), temps.maxOrNull(), temps.minOrNull(), rainMm = rain)
        }.sortedBy { it.date }
        return Forecast(now, days, "MET Norway (Yr)", "https://www.yr.no/")
    }

    // --- The report ------------------------------------------------------------------------------

    /**
     * A report for [day]: "today" (now and today's range), "week" (each day in brief) or an
     * ISO date (that day's forecast). [today] is the date where the place is.
     */
    fun report(f: Forecast, placeName: String, day: String, today: LocalDate, fahrenheit: Boolean): String {
        fun t(c: Double?) = c?.let { (if (fahrenheit) it * 9 / 5 + 32 else it).roundToInt().toString() + if (fahrenheit) "°F" else "°C" }
        fun line(d: Day): String {
            val parts = mutableListOf(d.description.ifBlank { "no summary" })
            t(d.highC)?.let { parts += "high $it" }
            t(d.lowC)?.let { parts += "low $it" }
            d.rainChance?.takeIf { it >= 20 }?.let { parts += "$it% chance of rain" }
            d.rainMm?.takeIf { it >= 0.5 }?.let { mm ->
                parts += if (fahrenheit) "about ${"%.1f".format(Locale.US, mm / 25.4).removeSuffix(".0")} in of rain" else "about ${mm.roundToInt()} mm of rain"
            }
            return parts.joinToString(", ")
        }
        fun name(d: LocalDate) = when (d) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        }
        val upcoming = f.days.filter { !it.date.isBefore(today) }
        return when (day) {
            "week" -> "This week in $placeName:\n" + upcoming.take(7).joinToString("\n") { "- **${name(it.date)}:** ${line(it)}" }
            "today" -> {
                val now = f.now?.let { n ->
                    val windy = n.windKmh?.let { if (fahrenheit) it / 1.609344 else it }?.roundToInt()?.takeIf { it >= 15 }
                        ?.let { ", wind $it ${if (fahrenheit) "mph" else "km/h"}" }.orEmpty()
                    "It's ${t(n.celsius)} and ${n.description.ifBlank { "calm" }} in $placeName$windy. "
                }.orEmpty()
                val todayLine = upcoming.firstOrNull { it.date == today }?.let { "Today: ${line(it)}." }.orEmpty()
                (now + todayLine).trim().ifEmpty { "No forecast for $placeName right now." }
            }
            else -> {
                val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return "Which day?"
                val d = f.days.firstOrNull { it.date == date }
                    ?: return "I only have the forecast for the next ${upcoming.size} days."
                "${name(date)} in $placeName: ${line(d)}."
            }
        }
    }
}
