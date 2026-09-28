package nl.bartvandermeeren.aight.openui

import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * OpenUI blocks as markdown, for everything that handles a reply as text: copy, share, the reply
 * notification and read-aloud. A table stays a table and steps stay a numbered list; buttons, forms
 * and follow-up suggestions only make sense on screen, so they are left out.
 */
object OpenUiText {
    private val fenceLanguages = setOf("openui-lang", "openui", "openuilang")
    private val markdownParser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build()

    /** True for the info string of a fenced block that holds OpenUI Lang. */
    fun isOpenUiFence(info: String?): Boolean =
        info?.trim()?.takeWhile { !it.isWhitespace() }?.lowercase() in fenceLanguages

    fun containsOpenUi(markdown: String): Boolean = openUiBlocks(markdown).isNotEmpty()

    /** [markdown] with every OpenUI block replaced by its markdown equivalent. */
    fun expand(markdown: String): String {
        val blocks = openUiBlocks(markdown)
        if (blocks.isEmpty()) return markdown
        val result = StringBuilder(markdown)
        // Reverse order preserves source offsets as earlier blocks are replaced.
        for (block in blocks.asReversed()) {
            val spans = block.sourceSpans
            if (spans.isEmpty()) continue
            val replacement = runCatching { toMarkdown(block.literal.trimEnd('\r', '\n')) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val start = spans.first().inputIndex
            val end = spans.last().let { it.inputIndex + it.length }
            result.replace(start, end, replacement)
        }
        return result.toString()
    }

    private fun openUiBlocks(markdown: String): List<FencedCodeBlock> {
        val blocks = mutableListOf<FencedCodeBlock>()
        fun visit(node: Node) {
            if (node is FencedCodeBlock && isOpenUiFence(node.info)) blocks += node
            var child = node.firstChild
            while (child != null) {
                visit(child)
                child = child.next
            }
        }
        visit(markdownParser.parse(markdown))
        return blocks
    }

    fun toMarkdown(source: String): String {
        val program = OpenUiParser.parse(source)
        if (program.isEmpty) return ""
        val evaluator = OpenUiEvaluator(program, OpenUiEvaluator.stateDefaults(program))
        val root = evaluator.root()
        if (root !is UiNode && (root !is List<*> || root.isEmpty())) return ""
        val blocks = mutableListOf<String>()
        write(root, blocks)
        return blocks.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun write(value: Any?, out: MutableList<String>) {
        when (val v = unbind(value)) {
            is List<*> -> v.forEach { write(it, out) }
            is UiNode -> node(v, out)
            is String -> if (v.isNotBlank()) out += v
            else -> Unit
        }
    }

    private fun node(n: UiNode, out: MutableList<String>) {
        when (n.type) {
            "Card", "Stack" -> write(n["children"], out)
            "CardHeader" -> {
                n.string("title")?.let { out += "## $it" }
                n.string("subtitle")?.let { out += it }
            }
            "InlineHeader" -> {
                n.string("heading")?.let { out += "### $it" }
                n.string("description")?.let { out += it }
            }
            "TextContent" -> n.string("text")?.let { out += it }
            "MarkDownRenderer" -> n.string("textMarkdown")?.let { out += it }
            "Label" -> n.string("text")?.let { out += it }
            "Callout", "TextCallout" -> {
                val title = n.string("title")
                val description = n.string("description")
                if (title != null || description != null) {
                    out += "> " + listOfNotNull(title?.let { "**$it**" }, description).joinToString(" ")
                }
            }
            "CodeBlock" -> n.string("codeString")?.let { out += "```${n.string("language").orEmpty()}\n$it\n```" }
            "Image", "ImageBlock" -> n.string("src")?.let { out += "![${n.string("alt").orEmpty()}]($it)" }
            "ImageGallery" -> n.list("images").mapNotNull { it as? Map<*, *> }.forEach { image ->
                val src = displayText(image["src"])
                if (src.isNotEmpty()) out += "![${displayText(image["alt"])}]($src)"
            }
            "Separator" -> out += "---"
            "Table" -> table(n)?.let { out += it }
            "BarChart", "LineChart", "AreaChart", "HorizontalBarChart" -> seriesTable(n)?.let { out += it }
            "PieChart", "RadialChart", "SingleStackedBarChart" -> {
                val labels = n.list("labels")
                val values = n.list("values")
                if (labels.isNotEmpty()) out += labels.indices.joinToString("\n") { "- ${cellText(labels[it])}: ${cellText(values.getOrNull(it))}" }
            }
            "Steps" -> n.nodes("items").mapIndexed { i, step ->
                val details = step.string("details")
                "${i + 1}. **${step.string("title").orEmpty()}**" + (details?.let { ": $it" } ?: "")
            }.takeIf { it.isNotEmpty() }?.let { out += it.joinToString("\n") }
            "Tabs", "Accordion" -> n.nodes("items").forEach { section(it, out) }
            "SectionBlock" -> n.nodes("sections").forEach { section(it, out) }
            "Carousel" -> n.list("children").forEach { write(it, out) }
            "TagBlock" -> n.list("tags").map(::cellText).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { out += it.joinToString(", ") }
            "Tag" -> n.string("text")?.let { out += it }
            "EntityList" -> {
                val rows = listOfNotNull(n.map("header")) + n.list("rows").filterIsInstance<Map<*, *>>() + listOfNotNull(n.map("footer"))
                if (rows.isNotEmpty()) out += rows.joinToString("\n") { "- ${cellText(it["left"])}: ${cellText(it["right"])}" }
            }
            "ListBlock" -> n.nodes("items").mapIndexed { i, item ->
                val subtitle = item.string("subtitle")
                "${i + 1}. ${item.string("title").orEmpty()}" + (subtitle?.let { " – $it" } ?: "")
            }.takeIf { it.isNotEmpty() }?.let { out += it.joinToString("\n") }
            // Interactive parts only work on screen.
            "FollowUpBlock", "FollowUpItem", "Buttons", "Button", "Form", "FormControl", "Icon" -> Unit
            in OpenUiLibrary.inputs -> Unit
            else -> n.args.forEach { write(it, out) }
        }
    }

    private fun section(item: UiNode, out: MutableList<String>) {
        item.string("trigger")?.let { out += "### $it" }
        write(item["content"], out)
    }

    private fun table(n: UiNode): String? {
        val columns = n.nodes("columns")
        if (columns.isEmpty()) return null
        val headers = columns.map { it.string("label").orEmpty() }
        val rows: List<List<String>> = if (n.list("rows").isNotEmpty()) {
            n.list("rows").map { row -> (row as? List<*>).orEmpty().map(::cellText) }
        } else {
            val data = columns.map { (it["data"] as? List<*>).orEmpty() }
            val count = data.maxOfOrNull { it.size } ?: 0
            (0 until count).map { r -> data.map { cellText(it.getOrNull(r)) } }
        }
        return markdownTable(headers, rows)
    }

    private fun seriesTable(n: UiNode): String? {
        val labels = n.list("labels")
        val series = n.nodes("series")
        if (labels.isEmpty() || series.isEmpty()) return null
        val headers = listOf(n.string("xLabel").orEmpty()) + series.map { it.string("category").orEmpty() }
        val rows = labels.indices.map { i -> listOf(cellText(labels[i])) + series.map { cellText(it.list("values").getOrNull(i)) } }
        return markdownTable(headers, rows)
    }

    private fun markdownTable(headers: List<String>, rows: List<List<String>>): String {
        fun line(cells: List<String>) = "| " + headers.indices.joinToString(" | ") { cells.getOrElse(it) { "" }.replace("|", "\\|").replace("\n", " ") } + " |"
        return (listOf(line(headers), "| " + headers.joinToString(" | ") { "---" } + " |") + rows.map(::line)).joinToString("\n")
    }

    /** One table cell or list value: a string or number, or the text a component in that spot shows. */
    fun cellText(value: Any?): String = when (val v = unbind(value)) {
        is UiNode -> listOf("text", "label", "title", "heading").firstNotNullOfOrNull { v.string(it) }.orEmpty()
        is Map<*, *> -> listOf("label", "text", "title", "value").firstNotNullOfOrNull { key -> v[key]?.let(::displayText)?.takeIf { it.isNotEmpty() } }.orEmpty()
        else -> displayText(v)
    }
}
