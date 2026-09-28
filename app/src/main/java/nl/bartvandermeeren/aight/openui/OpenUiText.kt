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

    /**
     * True when the on-screen dispatcher would draw something for [value]. Components are checked
     * down to their contents, so `Card([missing])` counts as empty and its block shows the source.
     * [hasIcon] says which Icon names the screen can draw. The default accepts every name, which
     * suits text export: it leaves icons out anyway.
     */
    fun hasRenderableRoot(value: Any?, hasIcon: (String) -> Boolean = { true }): Boolean = when (val v = unbind(value)) {
        is UiNode -> drawsSomething(v, hasIcon)
        is List<*> -> v.any { hasRenderableRoot(it, hasIcon) }
        is String -> v.isNotBlank()
        is Double, is Boolean -> true
        else -> false
    }

    /** Mirrors the early returns of each component's view in ui/openui. [hasIcon] is the screen's icon set. */
    private fun drawsSomething(n: UiNode, hasIcon: (String) -> Boolean): Boolean {
        val visible = { value: Any? -> hasRenderableRoot(value, hasIcon) }
        return when (n.type) {
            "Card" -> visible(n["children"]) || cardSources(n).isNotEmpty()
            "Stack" -> visible(n["children"])
            "CardHeader" -> n.hasText("title") || n.hasText("subtitle")
            "InlineHeader" -> n.hasText("heading") || n.hasText("description")
            "TextContent", "Label", "Tag" -> n.hasText("text")
            "MarkDownRenderer" -> n.hasText("textMarkdown")
            "Callout", "TextCallout" -> calloutVisible(n)
            "CodeBlock", "Separator", "Form" -> true
            "Image", "ImageBlock" -> n.hasText("src")
            "ImageGallery" -> n.list("images").any { displayText((unbind(it) as? Map<*, *>)?.get("src")).isNotBlank() }
            "Table" -> n.nodes("columns").isNotEmpty()
            "BarChart", "LineChart", "AreaChart", "HorizontalBarChart" -> {
                val series = n.list("series")
                n.list("labels").isNotEmpty() && (series.all { unbind(it) is Number } || series.any { unbind(it) is UiNode })
            }
            "PieChart", "RadialChart", "SingleStackedBarChart" -> n.list("labels").isNotEmpty() && n.list("values").isNotEmpty()
            "Steps", "Tabs", "ListBlock" -> n.nodes("items").isNotEmpty()
            "Accordion" -> n.nodes("items").any { hasSectionContent(it, hasIcon) }
            "SectionBlock" -> n.nodes("sections").any { hasSectionContent(it, hasIcon) }
            "Carousel" -> n.list("children").any { slide -> (unbind(slide) as? List<*>)?.let(visible) == true }
            "TagBlock" -> n.list("tags").any { tag ->
                when (val t = unbind(tag)) {
                    is UiNode -> drawsSomething(t, hasIcon)
                    else -> cellText(t).isNotBlank()
                }
            }
            "EntityList" -> {
                val rows = n.list("rows").mapNotNull { unbind(it) as? Map<*, *> }
                val header = n.map("header")
                (rows.isNotEmpty() || header != null) &&
                    (listOfNotNull(header, n.map("footer")) + rows).any { cellText(it["left"]).isNotBlank() || cellText(it["right"]).isNotBlank() }
            }
            "FollowUpBlock" -> n.nodes("items").any { it.hasText("text") }
            "Buttons" -> n.nodes("buttons").any { it.string("label") != null }
            "Button" -> n.string("label") != null
            "FormControl" -> n.hasText("label") || n.hasText("hint") ||
                n.node("input")?.let { it.type in OpenUiLibrary.inputs && drawsSomething(it, hasIcon) } == true
            "Icon" -> n.string("name")?.let(hasIcon) == true
            // These draw one control per item; the other inputs always draw their field.
            "RadioGroup", "CheckBoxGroup", "SwitchGroup", "Chips", "OptionCards" -> n.nodes("items").isNotEmpty()
            in OpenUiLibrary.inputs -> true
            // Components aight doesn't draw show their text and child components; ids stay hidden.
            else -> n.args.any { arg ->
                when (val part = unbind(arg)) {
                    is String -> part.isNotBlank() && !looksLikeId(part)
                    is UiNode, is List<*> -> visible(part)
                    else -> false
                }
            }
        }
    }

    private fun UiNode.hasText(name: String): Boolean = string(name)?.isNotBlank() == true

    /** True when an Accordion or SectionBlock item has a title or content; without either it is an empty row. */
    fun hasSectionContent(item: UiNode, hasIcon: (String) -> Boolean = { true }): Boolean =
        item.hasText("trigger") || item.hasText("value") || hasRenderableRoot(item["content"], hasIcon)

    /** A Callout shows unless its `visible` prop is given and false. */
    fun calloutVisible(n: UiNode): Boolean = n.props["visible"] == null || truthy(n["visible"])

    /** The sources a Card lists under its content: the ones with a link. */
    fun cardSources(n: UiNode): List<Map<*, *>> =
        n.list("sources").mapNotNull { unbind(it) as? Map<*, *> }.filter { displayText(it["url"]).isNotBlank() }

    /** Ids such as "revenue" or "art-museums" in a card item's first slot are for the program, not the reader. */
    fun looksLikeId(text: String): Boolean = text.length <= 40 && !text.contains(' ') && text.all { it.isLowerCase() || it.isDigit() || it == '-' || it == '_' }

    fun toMarkdown(source: String): String {
        val program = OpenUiParser.parse(source)
        if (program.isEmpty) return ""
        val evaluator = OpenUiEvaluator(program, OpenUiEvaluator.stateDefaults(program))
        val root = evaluator.root()
        if ((root !is UiNode && root !is List<*>) || !hasRenderableRoot(root)) return ""
        val blocks = mutableListOf<String>()
        write(root, blocks)
        return blocks.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun write(value: Any?, out: MutableList<String>) {
        when (val v = unbind(value)) {
            is List<*> -> v.forEach { write(it, out) }
            is UiNode -> node(v, out)
            is String -> if (v.isNotBlank()) out += v
            is Double, is Boolean -> out += displayText(v)
            else -> Unit
        }
    }

    private fun node(n: UiNode, out: MutableList<String>) {
        when (n.type) {
            "Card" -> {
                write(n["children"], out)
                // Numbered like on screen, so citations such as [1] still point somewhere.
                cardSources(n).mapIndexed { i, source ->
                    val url = displayText(source["url"])
                    val name = displayText(source["sourceName"])
                    "${i + 1}. [${displayText(source["title"]).ifBlank { url }}]($url)" + (if (name.isNotBlank()) " – $name" else "")
                }.takeIf { it.isNotEmpty() }?.let { out += it.joinToString("\n") }
            }
            "Stack" -> write(n["children"], out)
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
            "Callout", "TextCallout" -> if (calloutVisible(n)) {
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
                // As the chart draws them: missing or negative values count as 0, and only paired entries show.
                val labels = n.list("labels")
                val values = n.list("values").map { maxOf(0.0, toNumberOrNull(it) ?: 0.0) }
                val count = minOf(labels.size, values.size)
                if (count > 0) out += (0 until count).joinToString("\n") { "- ${cellText(labels[it])}: ${displayText(values[it])}" }
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
            "ListBlock" -> {
                // The screen shows item images only in the "image" variant.
                val withImages = n.string("variant") == "image"
                n.nodes("items").mapIndexed { i, item ->
                    val subtitle = item.string("subtitle")
                    val text = item.string("title").orEmpty() + (subtitle?.let { " – $it" } ?: "")
                    val image = item.map("image")?.takeIf { withImages }?.let { image ->
                        displayText(image["src"]).takeIf { it.isNotBlank() }?.let { "![${displayText(image["alt"])}]($it)" }
                    }
                    "${i + 1}. " + listOfNotNull(text, image).filter { it.isNotBlank() }.joinToString(" ")
                }.takeIf { it.isNotEmpty() }?.let { out += it.joinToString("\n") }
            }
            // Interactive parts only work on screen.
            "FollowUpBlock", "FollowUpItem", "Buttons", "Button", "Form", "FormControl", "Icon" -> Unit
            in OpenUiLibrary.inputs -> Unit
            // Like the on-screen fallback: text and child components, without ids, numbers or flags.
            else -> n.args.forEach { arg ->
                when (val part = unbind(arg)) {
                    is String -> if (part.isNotBlank() && !looksLikeId(part)) out += part
                    is UiNode, is List<*> -> write(part, out)
                    else -> Unit
                }
            }
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
        val rawSeries = n.list("series")
        val series = if (rawSeries.isNotEmpty() && rawSeries.all { unbind(it) is Number }) {
            listOf("" to rawSeries)
        } else {
            n.nodes("series").map { it.string("category").orEmpty() to it.list("values") }
        }
        if (labels.isEmpty() || series.isEmpty()) return null
        val headers = listOf(n.string("xLabel").orEmpty()) + series.mapIndexed { i, (name, _) -> name.ifBlank { "${i + 1}" } }
        val rows = labels.indices.map { i -> listOf(cellText(labels[i])) + series.map { cellText(it.second.getOrNull(i)) } }
        return markdownTable(headers, rows)
    }

    private fun markdownTable(headers: List<String>, rows: List<List<String>>): String {
        fun line(cells: List<String>) = "| " + headers.indices.joinToString(" | ") { cells.getOrElse(it) { "" }.replace("|", "\\|").replace("\n", " ") } + " |"
        return (listOf(line(headers), "| " + headers.joinToString(" | ") { "---" } + " |") + rows.map(::line)).joinToString("\n")
    }

    /** One table cell or list value: a string or number, or the text a component in that spot shows. */
    fun cellText(value: Any?): String = when (val v = unbind(value)) {
        // A container such as Card([TextContent("hello")]) has no text of its own; use what it exports.
        is UiNode -> listOf("text", "label", "title", "heading").firstNotNullOfOrNull { v.string(it) }
            ?: mutableListOf<String>().also { node(v, it) }.filter { it.isNotBlank() }.joinToString(" ") { it.replace('\n', ' ') }
        is Map<*, *> -> listOf("label", "text", "title", "value").firstNotNullOfOrNull { key -> v[key]?.let(::displayText)?.takeIf { it.isNotEmpty() } }.orEmpty()
        else -> displayText(v)
    }
}
