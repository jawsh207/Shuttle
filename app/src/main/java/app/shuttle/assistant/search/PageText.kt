package app.shuttle.assistant.search

/**
 * The readable text of a web page, for "summarise this" on a shared link: its title and the
 * words of its headings, paragraphs, list items and quotes, without scripts, menus, headers,
 * footers or forms. Rough by design (no full HTML parser), but enough for an article or a
 * post. Pure Kotlin, so it's unit-tested; fetching is in [WebSearch.page].
 */
object PageText {

    data class Page(val title: String, val text: String)

    private val drop = Regex(
        "(?is)<(script|style|noscript|svg|nav|header|footer|form|aside|button|select|iframe|template)\\b[^>]*>.*?</\\1>|<!--.*?-->",
    )
    private val blocks = Regex("(?is)<(h[1-4]|p|li|blockquote|pre|td|figcaption)\\b[^>]*>(.*?)</\\1>")

    fun read(html: String, budget: Int = 8000): Page {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?.let { SearchResults.text(it) }?.trim().orEmpty()
        // Prefer the article itself when the page marks it.
        val body = Regex("(?is)<article\\b[^>]*>(.*)</article>").find(html)?.groupValues?.get(1)
            ?: Regex("(?is)<main\\b[^>]*>(.*)</main>").find(html)?.groupValues?.get(1)
            ?: html
        val cleaned = drop.replace(body, " ")
        val out = StringBuilder()
        for (m in blocks.findAll(cleaned)) {
            val t = SearchResults.text(m.groupValues[2]).replace(Regex("\\s+"), " ").trim()
            // Skip crumbs: one-word links, cookie notices and the like.
            if (t.length < 25 && !m.groupValues[1].lowercase().startsWith("h")) continue
            if (out.length + t.length > budget) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(if (m.groupValues[1].lowercase().startsWith("h")) "## $t" else t)
        }
        return Page(title, out.toString().ifBlank { SearchResults.text(cleaned).replace(Regex("\\s+"), " ").trim().take(budget) })
    }

    /** Whether shared text is just a link (as browsers share pages). */
    fun isLink(text: String): Boolean = Regex("^https?://\\S+$").matches(text.trim())

    /** A link at the end of shared text ("Title https://…", as some apps share). */
    fun linkIn(text: String): String? = Regex("https?://\\S+").findAll(text).lastOrNull()?.value?.trimEnd('.', ',', ')')
}
