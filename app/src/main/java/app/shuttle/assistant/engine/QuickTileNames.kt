package app.shuttle.assistant.engine

/**
 * Finding a Quick Settings tile by name: what each of Shuttle's switches is called on a tile,
 * how a spoken name is matched against the labels the system shows, and how a tile's on/off
 * state is read from its description.
 *
 * Tile labels come from the system's own UI, so they differ between Android versions
 * ("Wi-Fi" became "Internet" in Android 12, which opens a dialog instead of switching) and
 * are in the phone's language. The names here are the English ones; a name the user says
 * themselves ("turn on the Akku-Sparmodus tile") is matched as said, so it works in any
 * language. Pure Kotlin, so it's unit-tested; the tapping itself is in
 * `tools/QuickTiles.kt`.
 */
object QuickTileNames {

    /** Tile labels for each switch tool, most likely first. */
    val forSwitch: Map<String, List<String>> = mapOf(
        "wifi" to listOf("Wi-Fi", "Wifi", "Internet"),
        "bluetooth" to listOf("Bluetooth"),
        "mobile_data" to listOf("Mobile data", "Cellular data", "Internet"),
        "nfc" to listOf("NFC"),
        "airplane_mode" to listOf("Airplane mode", "Flight mode", "Aeroplane mode"),
        "location" to listOf("Location"),
        "hotspot" to listOf("Hotspot", "Mobile hotspot", "Wi-Fi hotspot"),
        "dark_mode" to listOf("Dark theme", "Dark mode"),
    )

    /**
     * Tiles with no switch tool of their own that Shuttle knows by name (the parser's names for
     * them are in `CommandParser`), with what the tile is called on different versions.
     */
    val known: Map<String, List<String>> = linkedMapOf(
        "Battery Saver" to listOf("Battery Saver", "Power saving", "Battery saver mode"),
        "Night Light" to listOf("Night Light", "Eye comfort"),
        "Extra dim" to listOf("Extra dim", "Reduce brightness", "Reduce Bright Colors"),
        "Data Saver" to listOf("Data Saver"),
        "Color inversion" to listOf("Color inversion", "Invert colors"),
        "Color correction" to listOf("Color correction", "Grayscale"),
        "Bedtime mode" to listOf("Bedtime mode", "Bedtime"),
        "Screen record" to listOf("Screen record", "Screen recorder"),
        "Live Caption" to listOf("Live Caption"),
        "One-handed mode" to listOf("One-handed mode"),
        "Camera access" to listOf("Camera access"),
        "Microphone access" to listOf("Microphone access", "Mic access"),
        "Screen cast" to listOf("Screen cast", "Cast"),
        "Work apps" to listOf("Work apps", "Work profile"),
        "Quick Share" to listOf("Quick Share", "Nearby Share"),
    )

    /**
     * Every label worth looking for when the user names a tile: the name as said, plus the
     * other labels of a known tile or switch it matches ("wifi tile" also looks for
     * "Internet").
     */
    fun candidates(name: String): List<String> {
        val n = normalize(name)
        val extra = (known.values + forSwitch.entries.map { (tool, labels) -> labels + tool })
            .filter { labels -> labels.any { normalize(it) == n } }
            .flatten()
        return (listOf(name) + extra).distinctBy(::normalize)
    }

    /**
     * When a tile opens a dialog instead of switching (Internet, and Bluetooth on newer
     * Android), the switch to use inside it.
     */
    val dialogSwitch: Map<String, List<String>> = mapOf(
        "wifi" to listOf("Wi-Fi", "Wifi"),
        "mobile_data" to listOf("Mobile data", "Mobile network", "Cellular data"),
        "bluetooth" to listOf("Bluetooth", "Use Bluetooth"),
    )

    /** Lower case, letters and digits only: "Wi‑Fi" (with its non-breaking hyphen) is "wifi". */
    fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * How well a tile [label] matches a [name]: 3 exact, 2 the label starts with it, 1 it
     * contains it, 0 not at all. Only the label's first part counts: tiles describe
     * themselves as "Wi-Fi, On, HomeNetwork" or "Battery Saver\nOff".
     */
    fun score(label: String, name: String): Int {
        val first = normalize(label.split(',', '\n').first())
        val n = normalize(name)
        if (first.isEmpty() || n.isEmpty()) return 0
        return when {
            first == n -> 3
            first.startsWith(n) || n.startsWith(first) && first.length >= 4 -> 2
            n.length >= 4 && first.contains(n) -> 1
            else -> 0
        }
    }

    /** The best of [labels] for any of [names] (earlier names win ties), or null. */
    fun best(labels: List<String>, names: List<String>): Int? = ranked(labels, names).firstOrNull()

    /**
     * Every label that matches one of [names], best first. [bonus] adds to a label's score:
     * the caller uses it to prefer real tiles (which say whether they're on) over status bar
     * icons with the same name ("Wi-Fi, signal full" is an exact match for "Wi-Fi", but it's
     * an icon, and can't be tapped).
     */
    fun ranked(labels: List<String>, names: List<String>, bonus: (Int) -> Int = { 0 }): List<Int> {
        val scores = IntArray(labels.size)
        names.forEachIndexed { rank, name ->
            labels.forEachIndexed { i, label ->
                val match = score(label, name)
                // An earlier name is the likelier one, so it gets a small edge.
                if (match > 0) scores[i] = maxOf(scores[i], match * 10 - rank)
            }
        }
        return labels.indices.filter { scores[it] > 0 }.sortedByDescending { scores[it] + bonus(it) }
    }

    /** "On"/"Off" in a tile's description or state text; null when it doesn't say. */
    fun stateFrom(text: String?): Boolean? {
        if (text.isNullOrBlank()) return null
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return when {
            "off" in words || "disabled" in words -> false
            "on" in words || "enabled" in words -> true
            else -> null
        }
    }

    /** The label to show: the tile's first line, without its state ("Battery Saver"). */
    fun display(label: String): String = label.split(',', '\n').first().trim()
}
