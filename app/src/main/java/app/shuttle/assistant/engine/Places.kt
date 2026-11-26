package app.shuttle.assistant.engine

import java.text.Normalizer
import kotlin.math.round

/**
 * Finding a place by name on the phone itself: "Portland", "Portland, Maine", "Paris
 * France", "Springfield IL". Reads the place list built from GeoNames (towns of 5,000+
 * people; `app/src/main/assets/places/`, made by `tools/places/make_places.py`), so a named
 * place never goes over the network; only its coordinates do, for the forecast.
 *
 * A region after the name (state, province, country, or its code) narrows it down. Without
 * one, the biggest match wins, with a strong preference for the user's own country, so
 * "Portland" in the US is Oregon's and "Paris" is still France's. Pure Kotlin, so it's
 * unit-tested; reading the file is in `tools/WeatherTool.kt`.
 */
object Places {

    data class Place(
        val name: String,
        val admin1Code: String,
        val admin1: String,
        val country: String,
        val latitude: Double,
        val longitude: Double,
        val population: Long,
        val zone: String,
    ) {
        /** "Portland, Oregon" in the US; "Paris, France" elsewhere. */
        fun display(countries: Map<String, String>): String {
            val countryName = countries[country] ?: country
            return when {
                country == "US" && admin1.isNotBlank() -> "$name, $admin1"
                countryName.isBlank() -> name
                else -> "$name, $countryName"
            }
        }
    }

    /** One line of cities.tsv, or null for the header or a damaged line. */
    fun parse(line: String): Place? {
        if (line.startsWith("#")) return null
        val c = line.split('\t')
        if (c.size < 9) return null
        return Place(
            c[0], c[2], c[3], c[4],
            c[5].toDoubleOrNull() ?: return null, c[6].toDoubleOrNull() ?: return null,
            c[7].toLongOrNull() ?: 0, c[8],
        )
    }

    /** Lower case, no accents or punctuation, "saint"/"st." as "st": "St. Louis" = "saint louis". */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\bsaint\\b"), "st").replace(Regex("\\bsainte\\b"), "ste").replace(Regex("\\bmount\\b"), "mt")
            .replace(Regex("\\s+"), " ").trim()

    /** Nicknames people use for places named otherwise. */
    private val nicknames = mapOf(
        "nyc" to "new york city", "new york" to "new york city", "ny" to "new york city", "la" to "los angeles",
        "sf" to "san francisco", "san fran" to "san francisco", "dc" to "washington", "washington dc" to "washington",
        "washington d c" to "washington", "vegas" to "las vegas", "philly" to "philadelphia", "nola" to "new orleans",
        "kc" to "kansas city", "slc" to "salt lake city", "frisco" to "san francisco",
    )

    /** Country names people say that aren't the list's ("USA", "UK", "England"). */
    private val countryWords = mapOf(
        "usa" to "US", "us" to "US", "america" to "US", "united states of america" to "US", "the us" to "US",
        "uk" to "GB", "britain" to "GB", "great britain" to "GB", "england" to "GB", "scotland" to "GB", "wales" to "GB",
        "uae" to "AE", "holland" to "NL", "south korea" to "KR", "korea" to "KR", "russia" to "RU",
    )

    private fun regionMatches(p: Place, region: String, countries: Map<String, String>): Boolean {
        val r = normalize(region)
        if (r.isEmpty()) return true
        return r == normalize(p.admin1) || r == p.admin1Code.lowercase() || r == p.country.lowercase() ||
            r == normalize(countries[p.country].orEmpty()) || countryWords[r] == p.country
    }

    /**
     * The place [query] names, or null. [lines] is the place list, biggest first; [home] is
     * the user's country code.
     */
    fun find(query: String, lines: Sequence<String>, countries: Map<String, String>, home: String): Place? {
        val q = normalize(query.removePrefix("the ").removePrefix("The "))
        if (q.isEmpty()) return null
        // Ways to read the query: all of it as the name, "name, region", or the last one to
        // three words as the region ("portland maine", "salt lake city utah").
        val readings = buildList {
            if (',' in query) {
                add(normalize(query.substringBefore(',')) to query.substringAfter(','))
            } else {
                add((nicknames[q] ?: q) to "")
                val words = q.split(' ')
                for (k in 1..minOf(3, words.size - 1)) {
                    val name = words.dropLast(k).joinToString(" ")
                    add((nicknames[name] ?: name) to words.takeLast(k).joinToString(" "))
                }
            }
        }.map { (name, region) -> (nicknames[name] ?: name) to region }
        val names = readings.map { it.first }.toSet()
        // A cheap first check (the first letter of the name or its ASCII spelling) skips most
        // lines before the slower normalizing.
        val initials = names.mapNotNull { it.firstOrNull() }.toSet()
        // One pass over the file, keeping every place whose name is one of the readings'.
        val found = ArrayList<Place>()
        for (line in lines) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            if (line[0].lowercaseChar() !in initials && line.getOrNull(tab + 1)?.lowercaseChar() !in initials) continue
            val name = normalize(line.substring(0, tab))
            val ascii = line.indexOf('\t', tab + 1).let { if (it > tab) normalize(line.substring(tab + 1, it)) else name }
            if (name in names || ascii in names) parse(line)?.let { found += it }
        }
        fun score(p: Place) = p.population.toDouble() * (if (p.country == home) 5.0 else 1.0)
        for ((name, region) in readings) {
            val matches = found.filter { p ->
                normalize(p.name) == name && regionMatches(p, region, countries)
            }
            matches.maxByOrNull(::score)?.let { return it }
        }
        return null
    }

    /** Coordinates as sent to the weather services: four decimals (about 10 m) at most. */
    fun round4(v: Double): Double = round(v * 10_000) / 10_000
}
