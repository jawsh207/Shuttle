package app.shuttle.assistant.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.shuttle.assistant.text.Markdown

/**
 * An answer from the model, with its Markdown shown as formatting: headings, lists with
 * hanging indents, bold and italic, code, and tappable links. Parsing ([Markdown]) is cheap
 * enough to redo as the reply streams in.
 */
@Composable
fun MarkdownText(text: String, style: TextStyle = MaterialTheme.typography.bodyLarge, modifier: Modifier = Modifier) {
    val blocks = remember(text) { Markdown.parse(text) }
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    // Answers sit on surfaceContainerHighest cards, so code stands out one step lower.
    val codeBackground = colors.surfaceContainerLow
    val link = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))
    fun annotated(spans: List<Markdown.Span>): AnnotatedString = buildAnnotatedString {
        spans.forEach { span ->
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.SemiBold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBackground else Color.Unspecified,
            )
            val url = span.url
            if (url != null) withLink(LinkAnnotation.Url(url, link)) { withStyle(style) { append(span.text) } }
            else withStyle(style) { append(span.text) }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Markdown.Block.Paragraph -> Text(annotated(block.spans), style = style)
                is Markdown.Block.Heading -> Text(
                    annotated(block.spans),
                    style = if (block.level <= 2) type.titleMedium else type.titleSmall,
                    color = colors.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Markdown.Block.Item -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                    // A fixed-width marker column gives wrapped lines a hanging indent.
                    Box(Modifier.widthIn(min = if (block.marker == "•") 16.dp else 24.dp)) {
                        Text(block.marker, style = style, color = colors.primary)
                    }
                    Text(annotated(block.spans), style = style, modifier = Modifier.weight(1f))
                }
                is Markdown.Block.Quote -> Row {
                    Surface(color = colors.outlineVariant, modifier = Modifier.width(3.dp).height(20.dp)) {}
                    Spacer(Modifier.width(8.dp))
                    Text(annotated(block.spans), style = style, color = colors.onSurfaceVariant)
                }
                is Markdown.Block.Code -> Surface(
                    color = codeBackground,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Text(
                        block.text,
                        style = type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
                Markdown.Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Markdown.Block.Gap -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}
