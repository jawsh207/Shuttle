package app.shuttle.assistant.text

/**
 * Reads the light Markdown the model writes (headings, bullet and numbered lists, **bold**,
 * *italic*, `code`, code blocks, links) into blocks the chat can show, and into plain words
 * for speaking a reply aloud.
 *
 * Small models use Markdown out of habit; shown as-is, answers are full of asterisks and
 * hashes, and read aloud, the voice says them. This handles only the common subset, and
 * anything it doesn't recognise (an unclosed `**`, a lone `*` in "2 * 3") stays as written, so
 * a reply that's still streaming in never loses text. Pure Kotlin, so it's unit-tested.
 */
object Markdown {

    /** A run of text with one style. [url] is set for a link. */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val url: String? = null,
    )

    sealed interface Block {
        data class Paragraph(val spans: List<Span>) : Block
        data class Heading(val level: Int, val spans: List<Span>) : Block
        /** A list item; [marker] is "•" or "3.", [depth] 0 for the outer list. */
        data class Item(val marker: String, val depth: Int, val spans: List<Span>) : Block
        data class Quote(val spans: List<Span>) : Block
        data class Code(val text: String) : Block
        data object Rule : Block
        /** A blank line between paragraphs. */
        data object Gap : Block
    }

    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    blocks += Block.Code(code.toString())
                }
                trimmed.isEmpty() -> if (blocks.isNotEmpty() && blocks.last() != Block.Gap) blocks += Block.Gap
                rule.matches(line) -> blocks += Block.Rule
                heading.matchEntire(trimmed) != null -> {
                    val m = heading.matchEntire(trimmed)!!
                    blocks += Block.Heading(m.groupValues[1].length, inline(m.groupValues[2]))
                }
                numbered.matchEntire(line) != null -> {
                    val m = numbered.matchEntire(line)!!
                    blocks += Block.Item(m.groupValues[2] + ".", depth(m.groupValues[1]), inline(m.groupValues[3]))
                }
                bullet.matchEntire(line) != null -> {
                    val m = bullet.matchEntire(line)!!
                    blocks += Block.Item("•", depth(m.groupValues[1]), inline(m.groupValues[2]))
                }
                trimmed.startsWith(">") -> blocks += Block.Quote(inline(trimmed.removePrefix(">").trim()))
                else -> blocks += Block.Paragraph(inline(trimmed))
            }
            i++
        }
        while (blocks.lastOrNull() == Block.Gap) blocks.removeAt(blocks.size - 1)
        return blocks
    }

    private fun depth(indent: String) = (indent.replace("\t", "    ").length / 2).coerceAtMost(3)

    /** Inline styles within one line. */
    fun inline(text: String, bold: Boolean = false, italic: Boolean = false): List<Span> {
        val spans = ArrayList<Span>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) spans += Span(plain.toString(), bold, italic)
            plain.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // `code`
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    spans += Span(text.substring(i + 1, end), bold, italic, code = true)
                    i = end + 1
                    continue
                }
            }
            // **bold** or __bold__
            if ((c == '*' || c == '_') && text.startsWith("$c$c", i)) {
                val marker = "$c$c"
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2 && !text[i + 2].isWhitespace()) {
                    flush()
                    spans += inline(text.substring(i + 2, end), bold = true, italic = italic)
                    i = end + 2
                    continue
                }
            }
            // *italic* or _italic_ (an underscore only at a word's edge, so snake_case stays)
            if ((c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() &&
                (c == '*' || i == 0 || !text[i - 1].isLetterOrDigit())
            ) {
                var end = text.indexOf(c, i + 1)
                while (end > 0 && c == '_' && end + 1 < text.length && text[end + 1].isLetterOrDigit()) end = text.indexOf(c, end + 1)
                if (end > i + 1 && !text[end - 1].isWhitespace() && !(end + 1 < text.length && text[end + 1] == c)) {
                    flush()
                    spans += inline(text.substring(i + 1, end), bold = bold, italic = true)
                    i = end + 1
                    continue
                }
            }
            // [text](url)
            if (c == '[') {
                val close = text.indexOf("](", i + 1)
                val end = if (close > i) text.indexOf(')', close + 2) else -1
                if (close > i + 1 && end > close + 2) {
                    val url = text.substring(close + 2, end).trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        flush()
                        spans += Span(text.substring(i + 1, close), bold, italic, url = url)
                        i = end + 1
                        continue
                    }
                }
            }
            plain.append(c)
            i++
        }
        flush()
        return spans
    }

    /**
     * The reply as plain text to copy: lines, bullets ("•") and numbers kept, the markup
     * (asterisks, hashes, backticks) gone, links as "text (address)".
     */
    fun copyText(text: String): String = parse(text).joinToString("\n") { block ->
        fun words(spans: List<Span>) = spans.joinToString("") { s -> if (s.url != null && s.url != s.text) "${s.text} (${s.url})" else s.text }.trim()
        when (block) {
            is Block.Paragraph -> words(block.spans)
            is Block.Heading -> words(block.spans)
            is Block.Item -> "  ".repeat(block.depth) + block.marker + " " + words(block.spans)
            is Block.Quote -> words(block.spans)
            is Block.Code -> block.text
            Block.Rule, Block.Gap -> ""
        }
    }.replace(Regex("\n{3,}"), "\n\n").trim()

    /** The reply as words to speak: no markup, list items and headings as sentences. */
    fun plain(text: String): String = parse(text).mapNotNull { block ->
        fun words(spans: List<Span>) = spans.joinToString("") { it.text }.trim()
        fun sentence(s: String) = if (s.isEmpty() || s.last() in ".!?:;,") s else "$s."
        when (block) {
            is Block.Paragraph -> words(block.spans)
            is Block.Heading -> sentence(words(block.spans))
            is Block.Item -> sentence(words(block.spans))
            is Block.Quote -> words(block.spans)
            is Block.Code -> null
            Block.Rule, Block.Gap -> null
        }?.takeIf { it.isNotEmpty() }
    }.joinToString(" ")
}
