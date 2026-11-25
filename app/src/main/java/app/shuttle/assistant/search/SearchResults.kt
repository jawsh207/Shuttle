package app.shuttle.assistant.search

import java.net.URLDecoder

/**
 * Search results as data, and the plain-text parts of searching: reading DuckDuckGo's HTML
 * results page, and writing results out for the model.
 *
 * Kept free of Android types so it's covered by JVM tests. DuckDuckGo's HTML page has no
 * official format, so the parser looks only for the few class names its results have used for
 * years (`result__a`, `result__snippet`) and skips ads; if those ever change it returns
 * nothing and [WebSearch] falls back to Wikipedia.
 */
data class SearchResult(val title: String, val url: String, val snippet: String, val source: String) {
    /** "en.wikipedia.org" */
    val site: String get() = url.substringAfter("://").substringBefore('/').removePrefix("www.")
}

object SearchResults {

    private val block = Regex("<div[^>]+class=\"[^\"]*\\bresult\\b[^\"]*\"[^>]*>", RegexOption.IGNORE_CASE)
    private val link = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>|href=\"([^\"]+)\"[^>]*class=\"result__a\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val snippet = Regex("class=\"result__snippet\"[^>]*>(.*?)</(?:a|div|td)>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** True when DuckDuckGo answered with its "are you a robot?" page instead of results. */
    fun isChallenge(html: String): Boolean = html.contains("anomaly.js") || html.contains("challenge-form")

    fun parseDuckDuckGo(html: String, max: Int = 6): List<SearchResult> {
        val starts = block.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        val out = mutableListOf<SearchResult>()
        for ((i, start) in starts.withIndex()) {
            val end = starts.getOrNull(i + 1) ?: html.length
            val chunk = html.substring(start, end)
            if (chunk.contains("result--ad") || chunk.contains("badge--ad")) continue
            val m = link.find(chunk) ?: continue
            val href = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val title = text(m.groupValues[2].ifEmpty { m.groupValues[4] })
            val url = resolve(href) ?: continue
            if (title.isEmpty() || out.any { it.url == url }) continue
            out += SearchResult(title, url, snippet.find(chunk)?.let { text(it.groupValues[1]) } ?: "", "DuckDuckGo")
            if (out.size >= max) break
        }
        return out
    }

    /** DuckDuckGo wraps result links in a redirect (`//duckduckgo.com/l/?uddg=<real url>`). */
    fun resolve(href: String): String? {
        val raw = decodeEntities(href)
        val real = Regex("[?&]uddg=([^&]+)").find(raw)?.let { URLDecoder.decode(it.groupValues[1], "UTF-8") } ?: raw
        val url = if (real.startsWith("//")) "https:$real" else real
        return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.takeUnless { it.contains("duckduckgo.com/y.js") }
    }

    /** Tags removed, entities decoded, whitespace collapsed. */
    fun text(html: String): String =
        decodeEntities(html.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()

    fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }

    /**
     * What the model reads back from a search: numbered results with their site, kept to
     * about [budget] characters, with a reminder to answer from them and mention sources.
     */
    fun forModel(query: String, results: List<SearchResult>, budget: Int = 3000): String {
        if (results.isEmpty()) return "No results were found for \"$query\". Say you couldn't find anything online."
        val sb = StringBuilder("Web results for \"$query\":\n")
        for ((i, r) in results.withIndex()) {
            val entry = "[${i + 1}] ${r.title} (${r.site}): ${r.snippet}\n"
            if (sb.length + entry.length > budget) break
            sb.append(entry)
        }
        sb.append("Answer from these results, briefly. If they don't answer the question, say so.")
        return sb.toString()
    }
}
