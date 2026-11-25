package app.shuttle.assistant.engine

/**
 * A minimal JSON reader: objects become maps, arrays lists, numbers doubles. Enough for the
 * small keyless APIs Shuttle reads (the weather), in pure Kotlin so their parsing can be
 * unit-tested; Android's org.json isn't available to plain JVM tests.
 */
object Json {

    /** The parsed value, or null for malformed JSON. */
    fun parse(text: String): Any? = runCatching { Reader(text).run { value().also { space(); require(i == s.length) } } }.getOrNull()

    private class Reader(val s: String) {
        var i = 0
        fun space() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            space()
            require(i < s.length)
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }
        fun word(w: String, v: Any?): Any? { require(s.startsWith(w, i)); i += w.length; return v }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; space()
            if (s[i] == '}') { i++; return m }
            while (true) {
                space(); val k = str(); space(); require(s[i] == ':'); i++
                m[k] = value(); space()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}'); i++; return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; space()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); space()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']'); i++; return l
            }
        }
        fun str(): String {
            require(s[i] == '"'); i++
            val b = StringBuilder()
            while (s[i] != '"') {
                val c = s[i++]
                if (c != '\\') { b.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                    'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
            }
            i++
            return b.toString()
        }
        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}

/** A field of a parsed JSON object ([Json.parse]), or null. */
@Suppress("UNCHECKED_CAST")
fun Any?.field(name: String): Any? = (this as? Map<String, Any?>)?.get(name)
