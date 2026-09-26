package nl.bartvandermeeren.aight.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.ui.theme.GoogleSansCode
import nl.bartvandermeeren.aight.ui.theme.Palette
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.node.Text as MdText

private val markdownParser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create()))
    .build()

private fun Node.children(): List<Node> = generateSequence(firstChild) { it.next }.toList()

/** Renders agent markdown with roomy paragraphs, code on dark glass and tables in a glass frame. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val document = remember(text) { markdownParser.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        document.children().forEach { MarkdownBlock(it, style, depth = 0) }
    }
}

@Composable
private fun MarkdownBlock(node: Node, style: TextStyle, depth: Int) {
    when (node) {
        is Paragraph -> InlineText(node, style)
        is Heading -> {
            val headingStyle = when (node.level) {
                1 -> style.copy(fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
                2 -> style.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
                3 -> style.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
                else -> style.copy(fontWeight = FontWeight.SemiBold)
            }
            InlineText(node, headingStyle, Modifier.padding(top = if (node.level <= 2) 6.dp else 2.dp))
        }
        is BulletList -> ListBlock(node, ordered = false, start = 1, style = style, depth = depth)
        is OrderedList -> ListBlock(node, ordered = true, start = node.markerStartNumber ?: 1, style = style, depth = depth)
        is FencedCodeBlock -> CodeBlock(node.info?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }, node.literal.trimEnd('\n'))
        is IndentedCodeBlock -> CodeBlock(null, node.literal.trimEnd('\n'))
        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.OrbIndigo.copy(alpha = 0.7f)))
            Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                node.children().forEach { MarkdownBlock(it, style.copy(color = Palette.TextSecondary), depth) }
            }
        }
        is ThematicBreak -> HorizontalDivider(color = Palette.Outline, modifier = Modifier.padding(vertical = 4.dp))
        is TableBlock -> TableView(node, style)
        is HtmlBlock -> Text(node.literal.trim(), style = style)
        else -> node.children().forEach { MarkdownBlock(it, style, depth) }
    }
}

@Composable
private fun InlineText(node: Node, style: TextStyle, modifier: Modifier = Modifier) {
    val annotated = remember(node) { inlineString(node) }
    Text(annotated, style = style, color = style.color.takeIf { it != Color.Unspecified } ?: Palette.TextPrimary, modifier = modifier)
}

@Composable
private fun ListBlock(node: Node, ordered: Boolean, start: Int, style: TextStyle, depth: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        node.children().filterIsInstance<ListItem>().forEachIndexed { index, item ->
            Row {
                val marker = when {
                    ordered -> "${start + index}."
                    depth == 0 -> "•"
                    else -> "○"
                }
                Text(
                    marker,
                    style = style,
                    color = Palette.TextPrimary,
                    modifier = Modifier.width(if (ordered) 30.dp else 22.dp).padding(start = if (ordered) 0.dp else 4.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.children().forEach { MarkdownBlock(it, style, depth + 1) }
                }
            }
        }
    }
}

private fun inlineString(node: Node): AnnotatedString = buildAnnotatedString { appendChildren(node) }

private fun AnnotatedString.Builder.appendChildren(parent: Node) {
    var child = parent.firstChild
    while (child != null) {
        appendInline(child)
        child = child.next
    }
}

private fun AnnotatedString.Builder.appendInline(node: Node) {
    when (node) {
        is MdText -> append(node.literal)
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendChildren(node) }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendChildren(node) }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendChildren(node) }
        is Code -> withStyle(SpanStyle(fontFamily = GoogleSansCode, fontSize = 0.88.em, background = Palette.InlineCode)) {
            append(" ")
            append(node.literal)
            append(" ")
        }
        is Link -> withLink(
            LinkAnnotation.Url(node.destination, TextLinkStyles(SpanStyle(color = Palette.Link, textDecoration = TextDecoration.Underline))),
        ) { if (node.firstChild != null) appendChildren(node) else append(node.destination) }
        is Image -> withLink(
            LinkAnnotation.Url(node.destination, TextLinkStyles(SpanStyle(color = Palette.Link, textDecoration = TextDecoration.Underline))),
        ) { if (node.firstChild != null) appendChildren(node) else append(node.destination) }
        is SoftLineBreak -> append(" ")
        is HardLineBreak -> append("\n")
        is HtmlInline -> append(node.literal)
        else -> appendChildren(node)
    }
}

fun copyToClipboard(context: Context, text: String, label: String = "aight") {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
fun CodeBlock(language: String?, code: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    GlassSurface(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), Palette.Code) {
        Column {
            Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language ?: "code", style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { copyToClipboard(context, code); copied = true }) {
                    Icon(
                        if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        tint = Palette.Icon,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                code,
                fontFamily = GoogleSansCode,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = Palette.TextPrimary,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 2.dp),
            )
        }
    }
}

private class TableRowData(val header: Boolean, val cells: List<AnnotatedString>)

@Composable
private fun TableView(node: TableBlock, style: TextStyle) {
    val rows = remember(node) {
        node.children().flatMap { section ->
            section.children().map { row ->
                TableRowData(section is TableHead, row.children().filterIsInstance<TableCell>().map { inlineString(it) })
            }
        }
    }
    val columns = rows.maxOfOrNull { it.cells.size } ?: 0
    if (columns == 0) return
    val rowBottoms = remember(node) { IntArray(rows.size) }
    val cellStyle = style.copy(fontSize = 15.sp, lineHeight = 22.sp)
    Box(
        Modifier
            .horizontalScroll(rememberScrollState())
            .glass(RoundedCornerShape(16.dp), Palette.Disabled),
    ) {
        Layout(
            content = {
                rows.forEach { row ->
                    repeat(columns) { col ->
                        Text(
                            row.cells.getOrElse(col) { AnnotatedString("") },
                            style = if (row.header) cellStyle.copy(fontWeight = FontWeight.SemiBold) else cellStyle,
                            color = Palette.TextPrimary,
                            modifier = Modifier
                                .background(if (row.header) Palette.Surface else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            },
            modifier = Modifier.drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                rowBottoms.dropLast(1).forEach { y ->
                    drawLine(Palette.Outline, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), stroke)
                }
            },
        ) { measurables, _ ->
            val maxCell = 280.dp.roundToPx()
            val minCell = 64.dp.roundToPx()
            val widths = IntArray(columns) { col ->
                rows.indices.maxOf { r -> measurables[r * columns + col].maxIntrinsicWidth(Constraints.Infinity) }
                    .coerceIn(minCell, maxCell)
            }
            val heights = IntArray(rows.size) { r ->
                (0 until columns).maxOf { c -> measurables[r * columns + c].maxIntrinsicHeight(widths[c]) }
            }
            val placeablesFixed = measurables.mapIndexed { i, m ->
                m.measure(Constraints.fixed(widths[i % columns], heights[i / columns]))
            }
            var y = 0
            heights.forEachIndexed { r, h ->
                y += h
                rowBottoms[r] = y
            }
            layout(widths.sum(), heights.sum()) {
                var top = 0
                rows.indices.forEach { r ->
                    var left = 0
                    repeat(columns) { c ->
                        placeablesFixed[r * columns + c].placeRelative(left, top)
                        left += widths[c]
                    }
                    top += heights[r]
                }
            }
        }
    }
}
