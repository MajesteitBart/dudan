package nl.bartvandermeeren.aight.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code as CodeIcon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.openui.OpenUiText
import nl.bartvandermeeren.aight.ui.openui.OpenUiBlock
import nl.bartvandermeeren.aight.ui.theme.GoogleSansCode
import nl.bartvandermeeren.aight.ui.theme.LocalAccent
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

/** Renders agent markdown with roomy paragraphs, code on a dark pane and tables in a hairline frame. */
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
        is FencedCodeBlock -> if (OpenUiText.isOpenUiFence(node.info)) {
            // Text fields and buttons inside an OpenUI block don't mix with the reply's text selection.
            DisableSelection { OpenUiBlock(node.literal) }
        } else {
            CodeBlock(node.info?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }, node.literal.trimEnd('\n'))
        }
        is IndentedCodeBlock -> CodeBlock(null, node.literal.trimEnd('\n'))
        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.Outline))
            Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                node.children().forEach { MarkdownBlock(it, style.copy(color = Palette.TextSecondary), depth) }
            }
        }
        is ThematicBreak -> HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(vertical = 4.dp))
        is TableBlock -> TableView(node, style)
        is HtmlBlock -> Text(node.literal.trim(), style = style)
        else -> node.children().forEach { MarkdownBlock(it, style, depth) }
    }
}

@Composable
private fun InlineText(node: Node, style: TextStyle, modifier: Modifier = Modifier) {
    val link = LocalAccent.current.soft
    val annotated = remember(node, link) { inlineString(node, link) }
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

private fun inlineString(node: Node, link: Color): AnnotatedString = buildAnnotatedString { appendChildren(node, link) }

private fun AnnotatedString.Builder.appendChildren(parent: Node, link: Color) {
    var child = parent.firstChild
    while (child != null) {
        appendInline(child, link)
        child = child.next
    }
}

private fun AnnotatedString.Builder.appendInline(node: Node, link: Color) {
    when (node) {
        is MdText -> append(node.literal)
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendChildren(node, link) }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendChildren(node, link) }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendChildren(node, link) }
        is Code -> withStyle(SpanStyle(fontFamily = GoogleSansCode, fontSize = 0.88.em, background = Palette.InlineCode)) {
            append(" ")
            append(node.literal)
            append(" ")
        }
        is Link -> withLink(
            LinkAnnotation.Url(node.destination, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))),
        ) { if (node.firstChild != null) appendChildren(node, link) else append(node.destination) }
        is Image -> withLink(
            LinkAnnotation.Url(node.destination, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))),
        ) { if (node.firstChild != null) appendChildren(node, link) else append(node.destination) }
        is SoftLineBreak -> append(" ")
        is HardLineBreak -> append("\n")
        is HtmlInline -> append(node.literal)
        else -> appendChildren(node, link)
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
    // beautifului.dev's code block: a header with the language and a Copy button, then numbered lines.
    val lines = remember(code) { code.lines() }
    val codeStyle = TextStyle(fontFamily = GoogleSansCode, fontSize = 13.5.sp, lineHeight = 21.sp)
    Column(Modifier.fillMaxWidth().pane(RoundedCornerShape(16.dp), Palette.Code, outline = Palette.Hairline)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CodeIcon, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(language ?: "code", style = codeStyle.copy(fontWeight = FontWeight.Medium), color = Palette.TextPrimary)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { copyToClipboard(context, code); copied = true }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(15.dp))
                Text(stringResource(R.string.action_copy), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
            }
        }
        HorizontalDivider(color = Palette.Hairline)
        Row(Modifier.padding(top = 10.dp, bottom = 14.dp)) {
            Text(
                lines.indices.joinToString("\n") { "${it + 1}" },
                style = codeStyle.copy(textAlign = TextAlign.End),
                color = Palette.TextTertiary,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp),
            )
            Text(
                code,
                style = codeStyle,
                color = Palette.TextPrimary,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 16.dp),
            )
        }
    }
}

private class TableRowData(val header: Boolean, val cells: List<AnnotatedString>)

@Composable
private fun TableView(node: TableBlock, style: TextStyle) {
    val link = LocalAccent.current.soft
    val rows = remember(node, link) {
        node.children().flatMap { section ->
            section.children().map { row ->
                TableRowData(section is TableHead, row.children().filterIsInstance<TableCell>().map { inlineString(it, link) })
            }
        }
    }
    val columns = rows.maxOfOrNull { it.cells.size } ?: 0
    val cellStyle = style.copy(fontSize = 15.sp, lineHeight = 22.sp)
    TableGrid(rows.size, columns) { r, col ->
        val row = rows[r]
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

/**
 * Cells in a hairline frame with a line between rows. Each column is as wide as its widest cell,
 * between 64 and 280 dp, and the table scrolls sideways when it doesn't fit.
 */
@Composable
fun TableGrid(rowCount: Int, columnCount: Int, cell: @Composable (row: Int, column: Int) -> Unit) {
    if (rowCount == 0 || columnCount == 0) return
    val rowBottoms = remember(rowCount) { IntArray(rowCount) }
    Box(
        Modifier
            .horizontalScroll(rememberScrollState())
            .pane(RoundedCornerShape(16.dp), Color.Transparent, outline = Palette.Hairline),
    ) {
        Layout(
            content = {
                repeat(rowCount) { r ->
                    repeat(columnCount) { c ->
                        // Cells are measured at their column's width and their row's height.
                        Box(propagateMinConstraints = true) { cell(r, c) }
                    }
                }
            },
            modifier = Modifier.drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                rowBottoms.dropLast(1).forEach { y ->
                    drawLine(Palette.Hairline, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), stroke)
                }
            },
        ) { measurables, _ ->
            val maxCell = 280.dp.roundToPx()
            val minCell = 64.dp.roundToPx()
            val widths = IntArray(columnCount) { col ->
                (0 until rowCount).maxOf { r -> measurables[r * columnCount + col].maxIntrinsicWidth(Constraints.Infinity) }
                    .coerceIn(minCell, maxCell)
            }
            val heights = IntArray(rowCount) { r ->
                (0 until columnCount).maxOf { c -> measurables[r * columnCount + c].maxIntrinsicHeight(widths[c]) }
            }
            val placeablesFixed = measurables.mapIndexed { i, m ->
                m.measure(Constraints.fixed(widths[i % columnCount], heights[i / columnCount]))
            }
            var y = 0
            heights.forEachIndexed { r, h ->
                y += h
                rowBottoms[r] = y
            }
            layout(widths.sum(), heights.sum()) {
                var top = 0
                repeat(rowCount) { r ->
                    var left = 0
                    repeat(columnCount) { c ->
                        placeablesFixed[r * columnCount + c].placeRelative(left, top)
                        left += widths[c]
                    }
                    top += heights[r]
                }
            }
        }
    }
}
