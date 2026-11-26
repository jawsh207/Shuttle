package app.shuttle.assistant.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextTest {

    private val html = """
        <html><head><title>Why the sky is blue &amp; sunsets are red</title><script>var x = 1;</script></head>
        <body><nav><a href="/">Home</a><a href="/about">About us and our very long menu</a></nav>
        <article><h1>Why the sky is blue</h1>
        <p>Sunlight is made of many colours, and air scatters the short blue wavelengths far more than red ones.</p>
        <p>Share</p>
        <ul><li>Rayleigh scattering grows sharply as the wavelength gets shorter.</li></ul>
        </article><footer><p>Copyright 2026 Example News, all rights reserved here.</p></footer></body></html>
    """.trimIndent()

    @Test fun readsTheArticle() {
        val page = PageText.read(html)
        assertEquals("Why the sky is blue & sunsets are red", page.title)
        assertEquals(
            "## Why the sky is blue\n" +
                "Sunlight is made of many colours, and air scatters the short blue wavelengths far more than red ones.\n" +
                "Rayleigh scattering grows sharply as the wavelength gets shorter.",
            page.text,
        )
    }

    @Test fun links() {
        assertTrue(PageText.isLink("https://example.com/story?id=3"))
        assertFalse(PageText.isLink("Read this: https://example.com"))
        assertEquals("https://example.com/a", PageText.linkIn("Great read https://example.com/a."))
    }
}
