package app.shuttle.assistant.search

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale

/**
 * Looking things up online, with no account, key or server of our own.
 *
 * Two public sources, asked in turn:
 * - **DuckDuckGo's plain-HTML results** (html.duckduckgo.com). Broad web results. It sometimes
 *   answers with a "prove you're not a robot" page instead, especially to unusual networks; that
 *   counts as no results, never as an error.
 * - **Wikipedia's search API**, in the phone's language when there is one. Always answers, and
 *   its article introductions are good for "who is…" and "what is…" questions.
 *
 * Only the search words are sent, nothing else from the chat. Runs on a background thread.
 * A phone that's online but can't reach either is assumed to have Shuttle's Network permission
 * off (GrapheneOS), which is reported as [Blocked].
 */
object WebSearch {

    class Blocked : IOException("network access is blocked")

    private const val UA = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    /** Up to about six results, DuckDuckGo first, then Wikipedia. Throws [Blocked] or IOException. */
    fun search(query: String): List<SearchResult> {
        var networkErrors = 0
        val web = try {
            duckDuckGo(query)
        } catch (e: IOException) {
            if (blocked(e)) networkErrors++
            emptyList()
        }
        val wiki = try {
            wikipedia(query, if (web.isEmpty()) 3 else 2)
        } catch (e: IOException) {
            if (blocked(e)) networkErrors++
            if (web.isEmpty()) {
                if (networkErrors == 2) throw Blocked()
                throw e
            }
            emptyList()
        }
        return (web.take(5) + wiki).distinctBy { it.url }
    }

    /**
     * A shared link's readable text ([PageText]). Throws [Blocked] when the network permission
     * is off; an empty page when the site sent nothing readable.
     */
    fun page(url: String): PageText.Page {
        val (code, html) = try {
            get(url)
        } catch (e: IOException) {
            if (blocked(e)) throw Blocked() else throw e
        }
        if (code !in 200..299) throw IOException("The page answered $code")
        return PageText.read(html.take(2_000_000))
    }

    private fun blocked(e: IOException) =
        e is UnknownHostException || (e is SocketException && (e.message?.contains("EACCES") == true || e.message?.contains("Permission denied") == true))

    private fun get(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag() + ",en;q=0.5")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun duckDuckGo(query: String): List<SearchResult> {
        val (code, html) = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
        if (code != 200 || SearchResults.isChallenge(html)) return emptyList()
        return SearchResults.parseDuckDuckGo(html)
    }

    private fun wikipedia(query: String, max: Int): List<SearchResult> {
        val lang = Locale.getDefault().language.takeIf { it.length == 2 } ?: "en"
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&format=json&formatversion=2" +
            "&generator=search&gsrlimit=$max&gsrsearch=" + URLEncoder.encode(query, "UTF-8") +
            "&prop=extracts%7Cinfo&inprop=url&exintro=1&explaintext=1&exchars=700&exlimit=$max"
        val (code, body) = get(url)
        if (code != 200) throw IOException("Wikipedia HTTP $code")
        val pages = JSONObject(body).optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length()).map { pages.getJSONObject(it) }
            .sortedBy { it.optInt("index", 99) }
            .map { p ->
                SearchResult(
                    title = p.optString("title"),
                    url = p.optString("fullurl"),
                    snippet = p.optString("extract").replace(Regex("\\s+"), " ").trim(),
                    source = "Wikipedia",
                )
            }
            .filter { it.url.startsWith("https://") }
    }
}
