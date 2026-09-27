package nl.bartvandermeeren.aight

import nl.bartvandermeeren.aight.openui.ActionPlan
import nl.bartvandermeeren.aight.openui.ActionStep
import nl.bartvandermeeren.aight.openui.Bound
import nl.bartvandermeeren.aight.openui.OpenUiEvaluator
import nl.bartvandermeeren.aight.openui.OpenUiParser
import nl.bartvandermeeren.aight.openui.OpenUiText
import nl.bartvandermeeren.aight.openui.Pending
import nl.bartvandermeeren.aight.openui.UiNode
import nl.bartvandermeeren.aight.voice.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUiParserTest {
    private fun root(source: String, state: Map<String, Any?>? = null): Any? {
        val program = OpenUiParser.parse(source)
        return OpenUiEvaluator(program, state ?: OpenUiEvaluator.stateDefaults(program)).root()
    }

    @Test
    fun mapsPositionalArgumentsAndResolvesForwardReferences() {
        val card = root(
            """
            root = Card([header, btns])
            header = CardHeader("Energy", "Fixed, 1 year")
            btns = Buttons([Button("Switch", Action([@ToAssistant("Switch me")]), "primary")])
            """.trimIndent(),
        ) as UiNode
        assertEquals("Card", card.type)
        val children = card.list("children")
        val header = children[0] as UiNode
        assertEquals("Energy", header.string("title"))
        assertEquals("Fixed, 1 year", header.string("subtitle"))
        val button = (children[1] as UiNode).nodes("buttons").single()
        assertEquals("Switch", button.string("label"))
        assertEquals("primary", button.string("variant"))
        val plan = button["action"] as ActionPlan
        assertEquals(listOf(ActionStep.ToAssistant("Switch me", null)), plan.steps)
    }

    @Test
    fun partialStreamRendersWhatArrivedAndMarksTheRest() {
        // Cut off in the middle of a string, with an open bracket, as a stream chunk can be.
        val program = OpenUiParser.parse("root = Card([header, table])\nheader = CardHeader(\"Rev")
        assertTrue(program.incomplete)
        val card = OpenUiEvaluator(program, emptyMap()).root() as UiNode
        val children = card.list("children")
        assertEquals("Rev", (children[0] as UiNode).string("title"))
        assertEquals(Pending("table"), children[1])
    }

    @Test
    fun picksRootComponentWhenNoStatementIsCalledRoot() {
        val program = OpenUiParser.parse("data = [1, 2]\nmain = Card([TextContent(\"hi\")])")
        assertEquals("main", program.rootId)
        assertNull(OpenUiParser.parse("   \n").rootId)
    }

    @Test
    fun stripsLineCommentsButKeepsUrlsInStrings() {
        val card = root(
            """
            root = Card([link]) // the whole reply
            link = TextContent("See https://openui.com // not a comment")
            """.trimIndent(),
        ) as UiNode
        assertEquals("See https://openui.com // not a comment", (card.list("children")[0] as UiNode).string("text"))
    }

    @Test
    fun evaluatesExpressionsLikeJavaScript() {
        val card = root(
            """
            root = Card([a, b, c, d, e, f])
            rows = [{title: "A", n: 3}, {title: "B", n: -1}, {title: "C", n: 7}]
            a = TextContent("Total: " + @Sum(rows.n))
            b = TextContent("" + @Count(@Filter(rows, "n", ">", 0)) + " positive")
            c = TextContent(@First(@Sort(rows, "n", "desc")).title)
            d = TextContent(5 - -2 > 6 ? "yes" : "no")
            e = TextContent("" + @Round(2 / 3, 2))
            f = TextContent("" + (1 / 0))
            """.trimIndent(),
        ) as UiNode
        val texts = card.nodes("children").map { it.string("text") }
        assertEquals(listOf("Total: 9", "2 positive", "C", "yes", "0.67", "0"), texts)
    }

    @Test
    fun eachRendersATemplatePerItem() {
        val card = root(
            """
            root = Card([tags])
            tags = TagBlock(@Each(["red", "green"], "t", Tag(t, null, "sm", "info")))
            """.trimIndent(),
        ) as UiNode
        val tags = (card.list("children")[0] as UiNode).list("tags").map { (it as UiNode).string("text") }
        assertEquals(listOf("red", "green"), tags)
    }

    @Test
    fun statePropsAreBoundAndDefaultsAreApplied() {
        val source = """
            ${'$'}days = "7"
            root = Card([pick, label])
            pick = Select("days", [SelectItem("7", "Week"), SelectItem("30", "Month")], "Range", null, ${'$'}days)
            label = TextContent("Last " + ${'$'}days + " days")
        """.trimIndent()
        val card = root(source) as UiNode
        val select = card.list("children")[0] as UiNode
        assertEquals(Bound("\$days", "7"), select.props["value"])
        assertEquals("Last 7 days", (card.list("children")[1] as UiNode).string("text"))
        val changed = root(source, mapOf("\$days" to "30")) as UiNode
        assertEquals("Last 30 days", (changed.list("children")[1] as UiNode).string("text"))
    }

    @Test
    fun cyclesAndUnknownComponentsDontBreakRendering() {
        val card = root("root = Card([a, Mystery(\"x\", b)])\na = Stack([a])\nb = TextContent(\"inner\")") as UiNode
        val mystery = card.list("children")[1] as UiNode
        assertFalse(mystery.known)
        assertEquals("inner", (mystery.args[1] as UiNode).string("text"))
    }

    @Test
    fun nonFiniteNumbersNeverReachChartsOrRounding() {
        assertNull(nl.bartvandermeeren.aight.openui.toNumberOrNull("NaN"))
        assertNull(nl.bartvandermeeren.aight.openui.toNumberOrNull(Double.POSITIVE_INFINITY))
        // 1e999 lexes to Infinity; rounding it used to throw.
        val card = root("root = Card([TextContent(\"\" + @Round(1e999, 2)), chart])\nchart = BarChart([\"a\", \"b\"], [Series(\"s\", [\"NaN\", 1e999])])") as UiNode
        assertEquals("0", (card.list("children")[0] as UiNode).string("text"))
        val values = ((card.list("children")[1] as UiNode).nodes("series").single().list("values")).map { nl.bartvandermeeren.aight.openui.toNumberOrNull(it) }
        assertEquals(listOf<Double?>(null, null), values)
    }

    @Test
    fun lenientAboutBuiltinsWithoutTheAtSign() {
        val card = root("root = Card([TextContent(\"\" + Count([1, 2, 3]))])") as UiNode
        assertEquals("3", (card.list("children")[0] as UiNode).string("text"))
    }
}

/** Replies a model wrote from OpenUiPrompt, kept in the mock server's samples. */
class OpenUiModelSamplesTest {
    private val samples = java.io.File("../tools/mock-hermes/samples").listFiles { f -> f.extension == "md" }.orEmpty().sortedBy { it.name }

    private fun walk(value: Any?, visit: (UiNode) -> Unit) {
        when (val v = nl.bartvandermeeren.aight.openui.unbind(value)) {
            is UiNode -> {
                visit(v)
                v.args.forEach { walk(it, visit) }
            }
            is List<*> -> v.forEach { walk(it, visit) }
            is Map<*, *> -> v.values.forEach { walk(it, visit) }
        }
    }

    @Test
    fun everySampleRendersWithKnownComponentsOnly() {
        assertEquals(3, samples.size)
        samples.forEach { file ->
            val block = Regex("```openui-lang\\n([\\s\\S]*?)```").find(file.readText())?.groupValues?.get(1)
                ?: error("${file.name} has no openui-lang block")
            val program = OpenUiParser.parse(block)
            assertFalse(file.name, program.incomplete)
            val root = OpenUiEvaluator(program, OpenUiEvaluator.stateDefaults(program)).root() as UiNode
            assertEquals(file.name, "Card", root.type)
            walk(root) { node ->
                assertTrue("${file.name}: unknown ${node.type}", node.known)
                node.args.forEach { assertFalse("${file.name}: unresolved ref in ${node.type}", it is Pending) }
            }
            assertTrue(file.name, OpenUiText.toMarkdown(block).isNotBlank())
        }
    }
}

class OpenUiTextTest {
    private val reply = """
        Here is the comparison.

        ```openui-lang
        root = Card([header, table, steps, note, chart, followUps])
        header = CardHeader("Contracts", "Fixed, 1 year")
        table = Table([Col("Supplier", ["A", "B"]), Col("Per month", [142, 148], "number")])
        steps = Steps([StepsItem("Compare", "Check the rates"), StepsItem("Switch", "Sign up online")])
        note = Callout("info", "Leaving early", "Costs 125 euro.")
        chart = BarChart(["Oct", "Nov"], [Series("Sales", [5, 7])])
        followUps = FollowUpBlock([FollowUpItem("Which is greenest?")])
        ```

        Anything else?
    """.trimIndent()

    @Test
    fun expandsBlocksIntoMarkdown() {
        val expanded = OpenUiText.expand(reply)
        assertTrue(expanded.startsWith("Here is the comparison."))
        assertTrue(expanded.contains("## Contracts"))
        assertTrue(expanded.contains("| Supplier | Per month |"))
        assertTrue(expanded.contains("| A | 142 |"))
        assertTrue(expanded.contains("1. **Compare**: Check the rates"))
        assertTrue(expanded.contains("> **Leaving early** Costs 125 euro."))
        assertTrue(expanded.contains("| Oct | 5 |"))
        // Follow-up suggestions only make sense on screen.
        assertFalse(expanded.contains("greenest"))
        assertFalse(expanded.contains("openui-lang"))
        assertTrue(expanded.trimEnd().endsWith("Anything else?"))
    }

    @Test
    fun readsBlocksAloudInsteadOfSkippingThem() {
        val speech = SpeechText.fromMarkdown(reply)
        assertTrue(speech.contains("Contracts"))
        assertTrue(speech.contains("A, 142"))
        assertFalse(speech.contains("Card("))
    }

    @Test
    fun handlesABlockThatIsStillStreaming() {
        val partial = "Look:\n\n```openui-lang\nroot = Card([h])\nh = CardHeader(\"Almost"
        assertTrue(OpenUiText.containsOpenUi(partial))
        assertEquals("Look:\n\n## Almost", OpenUiText.expand(partial))
    }

    @Test
    fun recognizesFenceNames() {
        assertTrue(OpenUiText.isOpenUiFence("openui-lang"))
        assertTrue(OpenUiText.isOpenUiFence(" OpenUI "))
        assertFalse(OpenUiText.isOpenUiFence("kotlin"))
        assertFalse(OpenUiText.isOpenUiFence(null))
    }
}
