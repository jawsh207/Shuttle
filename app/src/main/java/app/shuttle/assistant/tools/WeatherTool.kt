package app.shuttle.assistant.tools

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.Places
import app.shuttle.assistant.engine.Source
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.engine.Weather
import app.shuttle.assistant.engine.When
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The weather tool, on a background thread: works out the place, fetches the forecast and
 * writes the report ([Weather]), crediting the source under it.
 *
 * - **The place.** A named place is looked up in the offline place list ([Places], from
 *   GeoNames), so its name never leaves the phone. With no place named, the phone's own
 *   location is used: Android's location service, no Google services, approximate location
 *   only, asked for the first time it's needed, and rounded to two decimals (about a
 *   kilometre).
 * - **The forecast.** Places in the US use the National Weather Service; everywhere else, or
 *   if it doesn't answer, MET Norway. Only the coordinates are sent. On GrapheneOS the Network
 *   permission has to be on, as for web search.
 */
internal object WeatherTool {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "shuttle-weather") }
    private val main = Handler(Looper.getMainLooper())

    fun run(context: Context, call: ToolCall, done: (ToolResult) -> Unit) {
        val place = call.arg("place")
        if (place == null && !context.hasAccess(Access.LOCATION)) {
            return done(needs(Access.LOCATION, call, "To check the weather where you are (or name a place: \"weather in Boston\"),"))
        }
        val app = context.applicationContext
        worker.execute {
            val result = try {
                fetch(app, place, call.arg("day") ?: "today")
            } catch (e: java.io.IOException) {
                ToolResult.Failed("I couldn't reach the weather service. Check your connection (on GrapheneOS, Shuttle's Network permission).")
            } catch (e: Exception) {
                ToolResult.Failed("The weather didn't come through (${e.message ?: e.javaClass.simpleName}).")
            }
            main.post { done(result) }
        }
    }

    private fun countries(context: Context): Map<String, String> =
        context.assets.open("places/countries.tsv").bufferedReader().useLines { lines ->
            lines.filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
        }

    private fun findPlace(context: Context, name: String): Places.Place? =
        context.assets.open("places/cities.tsv").bufferedReader().useLines { lines ->
            Places.find(name, lines, countries(context), Locale.getDefault().country.ifEmpty { "US" })
        }

    private fun fetch(context: Context, placeName: String?, dayArg: String): ToolResult {
        // Where: a named place from the offline list, or the phone's location.
        val lat: Double
        val lon: Double
        val shownName: String
        val zone: ZoneId
        val inUs: Boolean
        if (placeName != null) {
            val p = findPlace(context, placeName) ?: return ToolResult.Failed("I couldn't find a place called $placeName.")
            lat = p.latitude
            lon = p.longitude
            shownName = p.display(countries(context))
            zone = runCatching { ZoneId.of(p.zone) }.getOrDefault(ZoneId.systemDefault())
            inUs = p.country == "US" || p.country in setOf("PR", "GU", "VI", "AS", "MP")
        } else {
            val here = locate(context)
                ?: return ToolResult.Failed("I couldn't tell where you are. Check that Location is on, or name a place: \"weather in Boston\".")
            // Rounded: the forecast doesn't need more, and less goes over the network.
            lat = Math.round(here.latitude * 100) / 100.0
            lon = Math.round(here.longitude * 100) / 100.0
            shownName = "your area"
            zone = ZoneId.systemDefault()
            inUs = Weather.inUs(lat, lon)
        }
        val today = LocalDate.now(zone)
        val day = when (dayArg) {
            "today", "week" -> dayArg
            else -> When.day(dayArg, today)?.let { if (it == today) "today" else it.toString() } ?: "today"
        }
        // Which service: the National Weather Service in the US, MET Norway elsewhere (and
        // as the fallback if the NWS doesn't answer).
        val forecast = (if (inUs) runCatching { nws(lat, lon) }.getOrNull() else null)
            ?: Weather.met(get(Weather.metUrl(lat, lon)), zone)
            ?: return ToolResult.Failed("The weather service sent something I couldn't read.")
        return ToolResult.Done(
            Weather.report(forecast, shownName, day, today, Weather.fahrenheit()),
            sources = listOf(Source(forecast.sourceName, forecast.sourceUrl)),
        )
    }

    private fun nws(lat: Double, lon: Double): Weather.Forecast? {
        val (forecastUrl, hourlyUrl) = Weather.nwsLinks(get(Weather.nwsPointsUrl(lat, lon), geoJson = true)) ?: return null
        val forecast = get(forecastUrl, geoJson = true)
        val hourly = runCatching { get(hourlyUrl, geoJson = true) }.getOrNull()
        return Weather.nws(forecast, hourly)
    }

    /** Both services ask for a User-Agent naming the app and where to reach its makers. */
    private fun get(url: String, geoJson: Boolean = false): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", Weather.USER_AGENT)
        c.setRequestProperty("Accept", if (geoJson) "application/geo+json" else "application/json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** A recent known location, or a fresh one (waiting up to 10 seconds). On the worker thread. */
    @Suppress("MissingPermission")
    private fun locate(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (!lm.isLocationEnabled) return null
        val providers = listOfNotNull(
            if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER,
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 60 * 60 * 1000 }
            .maxByOrNull { it.time }
        if (recent != null) return recent
        for (provider in providers.filter { it != LocationManager.PASSIVE_PROVIDER }) {
            val latch = CountDownLatch(1)
            var found: Location? = null
            val cancel = CancellationSignal()
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, cancel, worker) { l -> found = l; latch.countDown() }
            } else {
                // Every method spelled out: on Android 10 they aren't defaults yet.
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(l: Location) { found = l; latch.countDown() }
                    @Deprecated("Android 10") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
            if (!latch.await(10, TimeUnit.SECONDS)) cancel.cancel()
            found?.let { return it }
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }
}
