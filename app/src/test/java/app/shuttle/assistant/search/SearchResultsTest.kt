package app.shuttle.assistant.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsTest {

    // Trimmed from the layout of html.duckduckgo.com: one ad, two results.
    private val page = """
        <div class="result results_links results_links_deep result--ad ">
          <h2 class="result__title"><a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad_provider=x">Buy pizza</a></h2>
        </div>
        <div class="result results_links results_links_deep web-result ">
          <div class="links_main links_deep result__body">
            <h2 class="result__title">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.example.com%2Fpizza%3Fa%3D1%26b%3D2&amp;rut=abc">Quick <b>Pizza</b> Dough &amp; Sauce</a>
            </h2>
            <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Mix flour, yeast &#x27;n&#x27; water. Rest <b>30</b> minutes.</a>
          </div>
        </div>
        <div class="result results_links results_links_deep web-result ">
          <h2 class="result__title"><a rel="nofollow" class="result__a" href="https://en.wikipedia.org/wiki/Pizza">Pizza - Wikipedia</a></h2>
          <a class="result__snippet">Pizza is an Italian dish.</a>
        </div>
    """.trimIndent()

    @Test fun parsesResultsAndSkipsAds() {
        val results = SearchResults.parseDuckDuckGo(page)
        assertEquals(2, results.size)
        assertEquals(SearchResult("Quick Pizza Dough & Sauce", "https://www.example.com/pizza?a=1&b=2", "Mix flour, yeast 'n' water. Rest 30 minutes.", "DuckDuckGo"), results[0])
        assertEquals("en.wikipedia.org", results[1].site)
        assertEquals("example.com", results[0].site)
    }

    @Test fun spotsTheRobotCheck() {
        assertTrue(SearchResults.isChallenge("<form id=\"challenge-form\" action=\"//duckduckgo.com/anomaly.js?sv=html\">"))
        assertEquals(emptyList<SearchResult>(), SearchResults.parseDuckDuckGo("<html>nothing</html>"))
    }

    @Test fun writesResultsForTheModel() {
        val text = SearchResults.forModel("pizza", SearchResults.parseDuckDuckGo(page))
        assertTrue(text, text.startsWith("Web results for \"pizza\":\n[1] Quick Pizza Dough & Sauce (example.com): Mix flour"))
        assertTrue(text, text.contains("[2] Pizza - Wikipedia (en.wikipedia.org)"))
        assertTrue(SearchResults.forModel("x", emptyList()).contains("No results"))
    }
}
