package app.shuttle.assistant.engine

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Questions answered straight away, without the model: arithmetic ("what's 15% of 80",
 * "12 times 7", "square root of 144"), unit conversions ("5 miles in km", "how many cups in a
 * litre", "350 F to C") and the time elsewhere ("what time is it in Tokyo").
 *
 * The model would answer these slowly and, for arithmetic, sometimes wrongly. Each part only
 * answers when the whole question is one it understands, so anything else still goes to the
 * model. Pure Kotlin, so it's unit-tested.
 */
object InstantAnswers {

    /** The answer, or null when this isn't such a question. [use24h] follows the phone. */
    fun answer(input: String, now: ZonedDateTime = ZonedDateTime.now(), use24h: Boolean = false): String? {
        val s = clean(input)
        if (s.isEmpty()) return null
        return worldClock(s, now, use24h) ?: convert(s) ?: calculate(s)
    }

    private fun clean(input: String): String = Quantities.wordsToDigits(
        input.lowercase().trim()
            .replace(Regex("[?!]+$"), "")
            .replace(Regex("^(?:hey shuttle,?\\s*|ok(?:ay)?,?\\s*|so,?\\s*)"), "")
            .replace(Regex("^(?:can you |could you |please |tell me |do you know )+"), "")
            .replace(Regex("\\s+please$"), "")
            .trim(),
    ).replace(Regex("(?<=\\d),(?=\\d{3}\\b)"), "")

    // --- Arithmetic ---------------------------------------------------------------------------

    private val words = listOf(
        "to the power of" to "^", "raised to" to "^", "multiplied by" to "*", "divided by" to "/",
        "times" to "*", "plus" to "+", "minus" to "-", "over" to "/", "x" to "*", "×" to "*", "÷" to "/",
        "squared" to "^2", "cubed" to "^3", "and" to "+",
    )

    /** "15% of 80", "12 times 7", "(3 + 4) * 2", "square root of 2", "20 percent off 50". */
    fun calculate(question: String): String? {
        var s = question
            .replace(Regex("^(?:what(?:'s| is)|whats|calculate|compute|how much is|what does|work out|solve)\\s+"), "")
            .replace(Regex("\\s+(?:equal|equals|make|come to)$"), "")
            .replace(Regex("^=\\s*|\\s*=$"), "")
            .trim()
        // Percentages first: "15% of 80", "20 percent off 50", "a 20% tip on 45".
        Regex("^(?:an?\\s+)?(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+(of|off|tip on|tip for|more than|on top of)\\s+(\\d+(?:\\.\\d+)?)$").find(s)?.let { m ->
            val p = m.groupValues[1].toDouble() / 100
            val base = m.groupValues[3].toDouble()
            val value = when (m.groupValues[2]) {
                "of", "tip on", "tip for" -> base * p
                "off" -> base * (1 - p)
                else -> base * (1 + p)
            }
            return format(value)
        }
        s = s.replace(Regex("(?:the\\s+)?square root of\\s+"), "√").replace(Regex("(\\d)\\s*%"), "$1/100")
        for ((w, op) in words) s = s.replace(Regex("(?<![a-z])${Regex.escape(w)}(?![a-z])"), " $op ")
        s = s.replace(Regex("\\s+"), "")
        if (!Regex("^[0-9.+\\-*/^()√]+$").matches(s)) return null
        // Something has to be worked out: "42" alone isn't a sum.
        if (!Regex("[+\\-*/^√]").containsMatchIn(s.removePrefix("-"))) return null
        val value = runCatching { Expression(s).parse() }.getOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return if (Regex("/0(?![.\\d])").containsMatchIn(s)) "You can't divide by zero." else null
        return format(value)
    }

    /** A small recursive-descent evaluator: + - * / ^ √, brackets, unary minus. */
    private class Expression(val s: String) {
        var i = 0
        fun parse(): Double {
            val v = sum()
            require(i == s.length)
            return v
        }
        private fun sum(): Double {
            var v = product()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) v = if (s[i++] == '+') v + product() else v - product()
            return v
        }
        private fun product(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '(' || s[i] == '√')) {
                v = when (s[i]) {
                    '*' -> { i++; v * power() }
                    '/' -> { i++; v / power() }
                    else -> v * power() // "2(3+4)", "2√9"
                }
            }
            return v
        }
        private fun power(): Double {
            val base = unary()
            if (i < s.length && s[i] == '^') { i++; return base.pow(power()) }
            return base
        }
        private fun unary(): Double = when {
            i < s.length && s[i] == '-' -> { i++; -unary() }
            i < s.length && s[i] == '√' -> { i++; sqrt(unary()) }
            else -> atom()
        }
        private fun atom(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = sum()
                require(i < s.length && s[i] == ')')
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start)
            return s.substring(start, i).toDouble()
        }
    }

    /** 12.0 → "12", 0.1+0.2 → "0.3", 1234567 → "1,234,567". */
    fun format(value: Double): String {
        val rounded = round(value * 1e6) / 1e6
        if (abs(rounded) >= 1e15) return String.format(Locale.US, "%.6g", value)
        return if (rounded == rounded.toLong().toDouble()) {
            String.format(Locale.US, "%,d", rounded.toLong())
        } else {
            String.format(Locale.US, "%,.6f", rounded).trimEnd('0').trimEnd('.')
        }
    }

    // --- Units ----------------------------------------------------------------------------------

    private class Unit(val kind: String, val toBase: Double, val names: List<String>, val shown: String)

    private val units = listOf(
        // Length (metres)
        Unit("length", 1000.0, listOf("km", "kms", "kilometer", "kilometers", "kilometre", "kilometres"), "km"),
        Unit("length", 1.0, listOf("m", "meter", "meters", "metre", "metres"), "m"),
        Unit("length", 0.01, listOf("cm", "centimeter", "centimeters", "centimetre", "centimetres"), "cm"),
        Unit("length", 0.001, listOf("mm", "millimeter", "millimeters", "millimetre", "millimetres"), "mm"),
        Unit("length", 1609.344, listOf("mi", "mile", "miles"), "miles"),
        Unit("length", 0.9144, listOf("yd", "yard", "yards"), "yards"),
        Unit("length", 0.3048, listOf("ft", "foot", "feet"), "feet"),
        Unit("length", 0.0254, listOf("in", "inch", "inches"), "inches"),
        // Mass (grams)
        Unit("mass", 1000.0, listOf("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms"), "kg"),
        Unit("mass", 1.0, listOf("g", "gram", "grams"), "g"),
        Unit("mass", 453.59237, listOf("lb", "lbs", "pound", "pounds"), "pounds"),
        Unit("mass", 28.349523125, listOf("oz", "ounce", "ounces"), "ounces"),
        Unit("mass", 6350.29318, listOf("st", "stone", "stones"), "stone"),
        // Volume (millilitres; US cups, pints and gallons)
        Unit("volume", 1000.0, listOf("l", "liter", "liters", "litre", "litres"), "litres"),
        Unit("volume", 1.0, listOf("ml", "milliliter", "milliliters", "millilitre", "millilitres"), "ml"),
        Unit("volume", 236.588, listOf("cup", "cups"), "cups"),
        Unit("volume", 3785.41, listOf("gal", "gallon", "gallons"), "gallons"),
        Unit("volume", 946.353, listOf("qt", "quart", "quarts"), "quarts"),
        Unit("volume", 473.176, listOf("pt", "pint", "pints"), "pints"),
        Unit("volume", 29.5735, listOf("fl oz", "fluid ounce", "fluid ounces"), "fl oz"),
        Unit("volume", 14.7868, listOf("tbsp", "tablespoon", "tablespoons"), "tbsp"),
        Unit("volume", 4.92892, listOf("tsp", "teaspoon", "teaspoons"), "tsp"),
        // Speed (metres per second)
        Unit("speed", 0.44704, listOf("mph", "miles per hour"), "mph"),
        Unit("speed", 1 / 3.6, listOf("kph", "kmh", "km/h", "kilometers per hour", "kilometres per hour"), "km/h"),
        Unit("speed", 0.514444, listOf("knot", "knots"), "knots"),
        // Data (bytes)
        Unit("data", 1e3, listOf("kb", "kilobyte", "kilobytes"), "KB"),
        Unit("data", 1e6, listOf("mb", "megabyte", "megabytes"), "MB"),
        Unit("data", 1e9, listOf("gb", "gigabyte", "gigabytes"), "GB"),
        Unit("data", 1e12, listOf("tb", "terabyte", "terabytes"), "TB"),
        // Time (seconds)
        Unit("time", 1.0, listOf("second", "seconds", "sec", "secs"), "seconds"),
        Unit("time", 60.0, listOf("minute", "minutes", "min", "mins"), "minutes"),
        Unit("time", 3600.0, listOf("hour", "hours", "hr", "hrs"), "hours"),
        Unit("time", 86400.0, listOf("day", "days"), "days"),
        Unit("time", 604800.0, listOf("week", "weeks"), "weeks"),
        // Temperature (handled apart)
        Unit("temperature", 0.0, listOf("c", "°c", "celsius", "centigrade", "degrees c", "degrees celsius"), "°C"),
        Unit("temperature", 0.0, listOf("f", "°f", "fahrenheit", "degrees f", "degrees fahrenheit"), "°F"),
        Unit("temperature", 0.0, listOf("k", "kelvin"), "K"),
        // Area (square metres)
        Unit("area", 4046.8564224, listOf("acre", "acres"), "acres"),
        Unit("area", 10000.0, listOf("hectare", "hectares", "ha"), "hectares"),
        Unit("area", 0.09290304, listOf("sq ft", "square foot", "square feet"), "sq ft"),
        Unit("area", 1.0, listOf("sq m", "square meter", "square meters", "square metre", "square metres"), "m²"),
    )

    private fun unit(name: String): Unit? {
        val n = name.trim().removePrefix("a ").removePrefix("an ").removePrefix("one ").trim()
        return units.firstOrNull { n in it.names }
    }

    private const val NUM = "(-?\\d+(?:\\.\\d+)?|an?|one)"

    /** "5 miles in km", "convert 2 cups to ml", "how many ounces in a pound", "70 f to c". */
    fun convert(question: String): String? {
        val s = question.replace(Regex("^(?:what(?:'s| is| are)|whats|convert)\\s+"), "").trim()
        val (amount, from, to) = Regex("^how many\\s+(.+?)\\s+(?:are\\s+)?(?:in|per)\\s+$NUM?\\s*(.+?)(?:\\s+are there)?$").find(s)?.let { m ->
            Triple(m.groupValues[2].ifEmpty { "1" }, m.groupValues[3], m.groupValues[1])
        } ?: Regex("^$NUM\\s*(.+?)\\s+(?:in|to|into|as|in to)\\s+(.+)$").find(s)?.let { m ->
            Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        } ?: return null
        val value = amount.toDoubleOrNull() ?: 1.0
        val a = unit(from.removePrefix("degrees ").let { if (unit(from) != null) from else it }) ?: return null
        val b = unit(to.removePrefix("degrees ").let { if (unit(to) != null) to else it }) ?: return null
        if (a.kind != b.kind || a === b) return null
        val result = if (a.kind == "temperature") {
            val celsius = when (a.shown) { "°F" -> (value - 32) * 5 / 9; "K" -> value - 273.15; else -> value }
            when (b.shown) { "°F" -> celsius * 9 / 5 + 32; "K" -> celsius + 273.15; else -> celsius }
        } else {
            value * a.toBase / b.toBase
        }
        val shownFrom = if (a.kind == "temperature") "${format(value)}${a.shown}" else "${format(value)} ${named(a.shown, value)}"
        val shownTo = if (b.kind == "temperature") "${format(round(result * 10) / 10)}${b.shown}" else "${format(significant(result))} ${named(b.shown, significant(result))}"
        return "$shownFrom is $shownTo."
    }

    /** "1 pound", "1 inch", "2 pounds". */
    private fun named(plural: String, amount: Double): String = if (amount != 1.0) plural else when (plural) {
        "inches" -> "inch"
        "feet" -> "foot"
        "miles", "yards", "pounds", "ounces", "litres", "cups", "gallons", "quarts", "pints",
        "seconds", "minutes", "hours", "days", "weeks", "acres", "hectares" -> plural.removeSuffix("s")
        else -> plural
    }

    /** Keeps 4 significant figures for small results, so 1 inch is 2.54 cm, not 2.54000. */
    private fun significant(v: Double): Double {
        if (v == 0.0 || abs(v) >= 1000) return round(v * 100) / 100
        val digits = 4 - kotlin.math.floor(kotlin.math.log10(abs(v))).toInt() - 1
        val f = 10.0.pow(digits.coerceIn(0, 8))
        return round(v * f) / f
    }

    // --- Time elsewhere -------------------------------------------------------------------------

    /** Places people ask about whose zone isn't named after them. */
    private val places = mapOf(
        "new york" to "America/New_York", "nyc" to "America/New_York", "boston" to "America/New_York",
        "washington" to "America/New_York", "dc" to "America/New_York", "miami" to "America/New_York",
        "atlanta" to "America/New_York", "philadelphia" to "America/New_York", "eastern" to "America/New_York",
        "chicago" to "America/Chicago", "dallas" to "America/Chicago", "houston" to "America/Chicago", "central" to "America/Chicago",
        "denver" to "America/Denver", "mountain" to "America/Denver", "phoenix" to "America/Phoenix",
        "los angeles" to "America/Los_Angeles", "la" to "America/Los_Angeles", "san francisco" to "America/Los_Angeles",
        "seattle" to "America/Los_Angeles", "las vegas" to "America/Los_Angeles", "pacific" to "America/Los_Angeles",
        "california" to "America/Los_Angeles", "anchorage" to "America/Anchorage", "alaska" to "America/Anchorage",
        "hawaii" to "Pacific/Honolulu", "honolulu" to "Pacific/Honolulu", "toronto" to "America/Toronto",
        "montreal" to "America/Toronto", "vancouver" to "America/Vancouver", "mexico city" to "America/Mexico_City",
        "mexico" to "America/Mexico_City", "brazil" to "America/Sao_Paulo", "rio" to "America/Sao_Paulo",
        "uk" to "Europe/London", "england" to "Europe/London", "britain" to "Europe/London", "scotland" to "Europe/London",
        "ireland" to "Europe/Dublin", "france" to "Europe/Paris", "germany" to "Europe/Berlin", "spain" to "Europe/Madrid",
        "italy" to "Europe/Rome", "netherlands" to "Europe/Amsterdam", "sweden" to "Europe/Stockholm",
        "norway" to "Europe/Oslo", "poland" to "Europe/Warsaw", "greece" to "Europe/Athens", "russia" to "Europe/Moscow",
        "turkey" to "Europe/Istanbul", "israel" to "Asia/Jerusalem", "egypt" to "Africa/Cairo",
        "south africa" to "Africa/Johannesburg", "kenya" to "Africa/Nairobi", "nigeria" to "Africa/Lagos",
        "uae" to "Asia/Dubai", "india" to "Asia/Kolkata", "delhi" to "Asia/Kolkata", "new delhi" to "Asia/Kolkata",
        "mumbai" to "Asia/Kolkata", "bangalore" to "Asia/Kolkata", "pakistan" to "Asia/Karachi", "china" to "Asia/Shanghai",
        "beijing" to "Asia/Shanghai", "japan" to "Asia/Tokyo", "korea" to "Asia/Seoul", "south korea" to "Asia/Seoul",
        "thailand" to "Asia/Bangkok", "vietnam" to "Asia/Ho_Chi_Minh", "philippines" to "Asia/Manila",
        "indonesia" to "Asia/Jakarta", "australia" to "Australia/Sydney", "new zealand" to "Pacific/Auckland",
        "nz" to "Pacific/Auckland", "utc" to "UTC", "gmt" to "GMT",
    )

    /** Every zone by its city, from the zone database: "Asia/Tokyo" → "tokyo". */
    private val cities: Map<String, String> by lazy {
        ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .associateBy { it.substringAfterLast('/').replace('_', ' ').lowercase() }
    }

    private fun zone(place: String): ZoneId? {
        val p = place.trim().removePrefix("the ").removeSuffix(" time").removeSuffix(" right now").removeSuffix(" now").trim()
        val id = places[p] ?: cities[p] ?: return null
        return runCatching { ZoneId.of(id) }.getOrNull()
    }

    /** "what time is it in Tokyo", "time in London", "what's the time in Sydney". */
    fun worldClock(question: String, now: ZonedDateTime, use24h: Boolean): String? {
        val m = Regex("^(?:what(?:'s| is)\\s+the\\s+time|what\\s+time\\s+is\\s+it|whats\\s+the\\s+time|the\\s+time|time|current\\s+time|what\\s+time's\\s+it)\\s+(?:in|over in|at)\\s+(.+)$")
            .find(question) ?: return null
        val place = m.groupValues[1]
        val zone = zone(place) ?: return null
        val there = now.withZoneSameInstant(zone)
        val time = there.format(DateTimeFormatter.ofPattern(if (use24h) "H:mm" else "h:mm a", Locale.US))
        val day = when (there.toLocalDate().compareTo(now.toLocalDate())) {
            0 -> ""
            else -> " " + there.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))
        }
        val name = place.trim().removePrefix("the ").split(' ').joinToString(" ") { w ->
            if (w.length <= 3 && w in setOf("uk", "uae", "nyc", "la", "dc", "nz", "utc", "gmt")) w.uppercase() else w.replaceFirstChar { it.uppercase() }
        }
        return "It's $time$day in $name."
    }
}
