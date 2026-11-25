package app.shuttle.assistant.text

import app.shuttle.assistant.text.Markdown.Block
import app.shuttle.assistant.text.Markdown.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {

    @Test
    fun boldItalicAndCode() {
        assertEquals(
            listOf(Span("Mix "), Span("flour", bold = true), Span(" and "), Span("water", italic = true), Span(" in "), Span("bowl", code = true)),
            Markdown.inline("Mix **flour** and *water* in `bowl`"),
        )
    }

    @Test
    fun unclosedAndLoneMarkersStayAsWritten() {
        assertEquals(listOf(Span("2 * 3 = 6")), Markdown.inline("2 * 3 = 6"))
        assertEquals(listOf(Span("Still **stream")), Markdown.inline("Still **stream"))
        assertEquals(listOf(Span("use snake_case_names here")), Markdown.inline("use snake_case_names here"))
    }

    @Test
    fun links() {
        assertEquals(
            listOf(Span("See "), Span("GrapheneOS", url = "https://grapheneos.org")),
            Markdown.inline("See [GrapheneOS](https://grapheneos.org)"),
        )
    }

    @Test
    fun blocks() {
        val blocks = Markdown.parse("## Dough\n\n1. Mix **flour**\n2. Knead\n   - ten minutes\n\nBake it.")
        assertEquals(
            listOf(
                Block.Heading(2, listOf(Span("Dough"))),
                Block.Gap,
                Block.Item("1.", 0, listOf(Span("Mix "), Span("flour", bold = true))),
                Block.Item("2.", 0, listOf(Span("Knead"))),
                Block.Item("•", 1, listOf(Span("ten minutes"))),
                Block.Gap,
                Block.Paragraph(listOf(Span("Bake it."))),
            ),
            blocks,
        )
    }

    @Test
    fun codeBlocksKeepTheirText() {
        assertEquals(listOf(Block.Code("val x = **1**\n  y")), Markdown.parse("```kotlin\nval x = **1**\n  y\n```"))
    }

    @Test
    fun plainForSpeech() {
        assertEquals(
            "Quick dough. Mix flour and water. Knead it. Rest for an hour.",
            Markdown.plain("### Quick dough\n- Mix **flour** and water\n- Knead it\n\nRest for *an hour*."),
        )
    }
}
