package app.shuttle.assistant.tools

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import app.shuttle.assistant.engine.Access
import app.shuttle.assistant.engine.ToolCall
import app.shuttle.assistant.engine.ToolResult
import app.shuttle.assistant.engine.Weather
import app.shuttle.assistant.engine.When
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The weather tool: fetches the forecast from Open-Meteo ([Weather] builds the request and
 * the report) on a background thread, for a named place (looked up with Open-Meteo's
 * geocoding) or, when none is named, where the phone is.
 *
 * The phone's location comes from Android's own location service: no Google services, and
 * only approximate location is asked for, only the first time it's needed. Only the
 * coordinates, rounded to two decimals (about a kilometre), are sent. On GrapheneOS the
 * Network permission has to be on, as for web search.
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

    private fun fetch(context: Context, placeName: String?, dayArg: String): ToolResult {
        val today = LocalDate.now()
        val day = when (dayArg) {
            "today", "week" -> dayArg
            else -> When.day(dayArg, today)?.let { if (it == today) "today" else it.toString() } ?: "today"
        }
        val place = if (placeName != null) {
            Weather.place(get(Weather.geocodeUrl(placeName, Locale.getDefault().language.ifEmpty { "en" })))
                ?: return ToolResult.Failed("I couldn't find a place called $placeName.")
        } else {
            val here = locate(context)
                ?: return ToolResult.Failed("I couldn't tell where you are. Check that Location is on, or name a place: \"weather in Boston\".")
            // Rounded: the forecast doesn't need more, and less goes over the network.
            Weather.Place("your area", Math.round(here.latitude * 100) / 100.0, Math.round(here.longitude * 100) / 100.0)
        }
        val fahrenheit = Weather.fahrenheit()
        val json = get(Weather.forecastUrl(place.latitude, place.longitude, fahrenheit))
        val report = Weather.report(json, place.name, day, today, fahrenheit)
            ?: return ToolResult.Failed("The weather service sent something I couldn't read.")
        return ToolResult.Done(report)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "Shuttle (Android assistant)")
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
