package nl.bartvandermeeren.aight

import androidx.compose.runtime.mutableStateMapOf
import nl.bartvandermeeren.aight.openui.ActionPlan
import nl.bartvandermeeren.aight.openui.ActionStep
import nl.bartvandermeeren.aight.openui.Bound
import nl.bartvandermeeren.aight.openui.OpenUiEvaluator
import nl.bartvandermeeren.aight.openui.OpenUiParser
import nl.bartvandermeeren.aight.openui.OpenUiText
import nl.bartvandermeeren.aight.openui.Pending
import nl.bartvandermeeren.aight.openui.UiNode
import nl.bartvandermeeren.aight.ui.openui.ImageClaim
import nl.bartvandermeeren.aight.ui.openui.OpenUiHost
import nl.bartvandermeeren.aight.ui.openui.OpenUiScope
import nl.bartvandermeeren.aight.ui.openui.drawable
import nl.bartvandermeeren.aight.ui.openui.radialStrokePx
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

/** Replies models wrote from OpenUiPrompt, including one from a real Hermes run (hermes-quote.md), kept in the mock server's samples. */
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
        assertTrue(samples.size >= 4)
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
    @Test
    fun crowdedRadialChartKeepsEveryRingInsideTheCanvas() {
        val count = 44
        val radius = 88f
        val slot = radius / (count + 1)
        val stroke = radialStrokePx(slot, 14f)
        assertTrue(stroke > 0f)
        assertTrue(stroke / 2f + (count - 1) * slot < radius)
    }

    @Test
    fun keepsBlocksThatProduceNoExportableText() {
        val unresolved = "```openui-lang\nroot = missing\n```"
        val empty = "```openui-lang\n\n```"
        val scalar = "```openui-lang\nroot = \"hello\"\n```"
        assertEquals(unresolved, OpenUiText.expand(unresolved))
        assertEquals(empty, OpenUiText.expand(empty))
        assertEquals(scalar, OpenUiText.expand(scalar))
    }

    @Test
    fun fallsBackForListsWithoutRenderableMembers() {
        for (body in listOf("root = [missing]", "root = [{label: \"x\"}]", "root = [[null]]")) {
            val source = "```openui-lang\n$body\n```"
            val root = OpenUiEvaluator(OpenUiParser.parse(body), emptyMap()).root()
            assertFalse(OpenUiText.hasRenderableRoot(root))
            assertEquals(source, OpenUiText.expand(source))
        }
        val nested = OpenUiEvaluator(OpenUiParser.parse("root = [[TextContent(\"visible\")]]"), emptyMap()).root()
        assertTrue(OpenUiText.hasRenderableRoot(nested))
    }

    @Test
    fun fallsBackForComponentsWithoutVisibleContent() {
        val empty = listOf(
            "root = Card([missing])",
            "root = Stack([Card([missing])])",
            "root = Card([TextContent(missing)])",
            "root = Card([CardHeader(), Tag(\" \")])",
            "root = Card([BarChart([], [1, 2]), PieChart([\"A\"], [])])",
            "root = Card([Buttons([Button(missing)]), Carousel([[]])])",
            "root = Unknown(\"just-an-id\", [missing])",
            "root = TagBlock([\" \", Tag(\" \"), missing])",
            "root = Card([EntityList([{left: \"\", right: \" \"}], \"sm\", {left: \" \"})])",
            "root = Card([FormControl(\" \", TextContent(missing))])",
            "root = Card([InlineHeader(\" \"), FollowUpBlock([FollowUpItem(\" \")])])",
            "root = Carousel([[missing], []])",
            "root = RadioGroup(\"choice\", [])",
            "root = Card([CheckBoxGroup(\"c\", []), SwitchGroup(\"s\", []), OptionCards(\"o\", \"single\", []), FormControl(\"\", Chips(\"x\", \"multi\", []))])",
            "root = Card([Accordion([AccordionItem(\"\", \" \", [missing])]), SectionBlock([SectionItem(\"\", \"\", [])], false)])",
        )
        for (body in empty) {
            val source = "```openui-lang\n$body\n```"
            val root = OpenUiEvaluator(OpenUiParser.parse(body), emptyMap()).root()
            assertFalse(body, OpenUiText.hasRenderableRoot(root))
            assertEquals(body, source, OpenUiText.expand(source))
        }
        val visible = listOf(
            "root = Card([CardHeader(\"Title\")])",
            "root = Card([missing], [{url: \"https://example.com\"}])",
            "root = Stack([Card([missing]), Separator()])",
            "root = Card([BarChart([\"A\"], [1])])",
            "root = Card([Buttons([Button(\"Go\")])])",
            "root = Unknown(\"A readable line\")",
            "root = TagBlock([\" \", \"urgent\"])",
            "root = EntityList([{left: \"Total\", right: 12}])",
            "root = FormControl(\"\", Input(\"email\"))",
            "root = Carousel([[missing], [TextContent(\"Slide\")]])",
            "root = RadioGroup(\"choice\", [RadioItem(\"Yes\")])",
            "root = Select(\"pick\", [])",
            "root = Accordion([AccordionItem(\"a\", \"Details\", [missing])])",
        )
        for (body in visible) {
            val root = OpenUiEvaluator(OpenUiParser.parse(body), emptyMap()).root()
            assertTrue(body, OpenUiText.hasRenderableRoot(root))
        }
    }

    @Test
    fun rejectedSendStopsTheRestOfTheActionPlan() {
        var notices = 0
        val sent = mutableListOf<String>()
        var accept = false
        val host = OpenUiHost(send = { sent += it; accept }, openUrl = {})
        val state = mutableStateMapOf<String, Any?>("draft" to "keep me")
        val scope = OpenUiScope(OpenUiParser.parse(""), emptyMap(), state, mutableStateMapOf(), streaming = false, host = host) { notices++ }
        val plan = ActionPlan(listOf(ActionStep.ToAssistant("Submit", null), ActionStep.Reset(listOf("draft"))))

        scope.run(plan, "Submit", null)
        assertEquals(1, notices)
        assertEquals("keep me", state["draft"])

        accept = true
        scope.run(plan, "Submit", null)
        assertEquals(listOf("Submit", "Submit"), sent)
        assertFalse(state.containsKey("draft"))
    }

    @Test
    fun mixedSlidesAndSectionsKeepOnlyTheOnesWithContent() {
        val carousel = OpenUiEvaluator(OpenUiParser.parse("root = Carousel([[missing], [TextContent(\"Slide\")]])"), emptyMap()).root() as UiNode
        assertEquals(listOf(false, true), carousel.list("children").map { OpenUiText.hasRenderableRoot(it) })

        val fold = OpenUiEvaluator(OpenUiParser.parse("root = Accordion([AccordionItem(\"\", \" \", [missing]), AccordionItem(\"b\", \"Details\", [missing]), AccordionItem(\"\", \"\", [TextContent(\"Body\")])])"), emptyMap()).root() as UiNode
        assertEquals(listOf(false, true, true), fold.nodes("items").map { OpenUiText.hasSectionContent(it) })
    }

    @Test
    fun imagesShareOneBudgetAndReturnItWhenTheyLeave() {
        val budget = java.util.concurrent.Semaphore(100)
        val first = ImageClaim(budget, 60)
        val second = ImageClaim(budget, 60)
        assertTrue(first.held)
        assertFalse(second.held)
        second.onForgotten()
        assertEquals(40, budget.availablePermits())
        first.onForgotten()
        assertTrue(ImageClaim(budget, 60).held)
        ImageClaim(budget, 60).onAbandoned()
        assertEquals(40, budget.availablePermits())
    }

    @Test
    fun imageClaimsShrinkToWhatTheDecodeUsed() {
        val budget = java.util.concurrent.Semaphore(100)
        val small = ImageClaim(budget, 60)
        small.shrinkTo(10)
        assertEquals(90, budget.availablePermits())
        val failed = ImageClaim(budget, 60)
        failed.shrinkTo(0)
        assertEquals(90, budget.availablePermits())
        // Disposal after a shrink returns only what is still held, never more.
        failed.onForgotten()
        small.onForgotten()
        small.onForgotten()
        assertEquals(100, budget.availablePermits())
    }

    @Test
    fun exportsShareChartValuesAsDrawn() {
        val body = "root = PieChart([\"A\", \"B\", \"C\", \"D\"], [30, -5, \"x\"])"
        assertEquals("- A: 30\n- B: 0\n- C: 0", OpenUiText.expand("```openui-lang\n$body\n```"))
    }

    @Test
    fun exportsListImagesAndHidesFallbackIds() {
        val list = "root = ListBlock([ListItem(\"Museum\", \"Open daily\", {src: \"https://example.com/m.jpg\", alt: \"Front\"}), ListItem(\"\", \"\", {src: \"https://example.com/p.jpg\"})], \"image\")"
        assertEquals(
            "1. Museum – Open daily ![Front](https://example.com/m.jpg)\n2. ![](https://example.com/p.jpg)",
            OpenUiText.expand("```openui-lang\n$list\n```"),
        )
        val plain = list.replace(", \"image\")", ")")
        assertEquals("1. Museum – Open daily\n2.", OpenUiText.expand("```openui-lang\n$plain\n```").trimEnd())

        val mystery = "root = Card([Mystery(\"record-id\", [TextContent(\"Shown\")], 42, true)])"
        assertEquals("Shown", OpenUiText.expand("```openui-lang\n$mystery\n```"))
    }

    @Test
    fun layoutsDropEmptyChildrenOnceTheReplyIsComplete() {
        val stack = OpenUiEvaluator(OpenUiParser.parse("root = Stack([missing, TextContent(missing), TextContent(\"Shown\"), \" \", null], \"row\")"), emptyMap()).root() as UiNode
        val children = stack.list("children")
        assertEquals(listOf(false, false, true, false, false), children.map { drawable(it, streaming = false) })
        // While streaming, pending parts and components keep their place for the placeholder.
        assertEquals(listOf(true, true, true, false, false), children.map { drawable(it, streaming = true) })
    }

    @Test
    fun onlyIconsTheScreenCanDrawCountAsContent() {
        val known = { name: String -> name == "star" }
        for (body in listOf("root = Icon(\"unlisted-icon\")", "root = Card([Icon(\"unlisted-icon\"), TagBlock([Tag(\" \")])])")) {
            val root = OpenUiEvaluator(OpenUiParser.parse(body), emptyMap()).root()
            assertFalse(body, OpenUiText.hasRenderableRoot(root, known))
        }
        val star = OpenUiEvaluator(OpenUiParser.parse("root = Card([Icon(\"star\")])"), emptyMap()).root()
        assertTrue(OpenUiText.hasRenderableRoot(star, known))
    }

    @Test
    fun exportsComponentTableCellsAsText() {
        val body = "root = Table([Col(\"Item\", [Card([TextContent(\"hello\")]), ImageBlock(\"https://example.com/a.png\", \"Chart\")]), Col(\"Status\", [Tag(\"done\"), Stack([TextContent(\"two\"), TextContent(\"lines\")])])])"
        val expanded = OpenUiText.expand("```openui-lang\n$body\n```")
        assertEquals("| Item | Status |\n| --- | --- |\n| hello | done |\n| ![Chart](https://example.com/a.png) | two lines |", expanded)
    }

    @Test
    fun exportsCardSourcesAsNumberedLinks() {
        val body = "root = Card([TextContent(\"Answer [1]\")], [{title: \"Docs\", url: \"https://example.com/docs\", sourceName: \"Example\"}, {url: \"https://example.com/raw\"}, {title: \"No link\"}])"
        val expanded = OpenUiText.expand("```openui-lang\n$body\n```")
        assertEquals("Answer [1]\n\n1. [Docs](https://example.com/docs) – Example\n2. [https://example.com/raw](https://example.com/raw)", expanded)
    }

    @Test
    fun leavesHiddenCalloutsOutOfTheExport() {
        val body = "root = Card([TextContent(\"Shown\"), Callout(\"info\", \"Secret\", \"Hidden text\", false), Callout(\"info\", \"Tip\", \"Visible text\")])"
        val expanded = OpenUiText.expand("```openui-lang\n$body\n```")
        assertEquals("Shown\n\n> **Tip** Visible text", expanded)
    }

    @Test
    fun leavesUnsupportedOpenUiFenceLanguagesAsCode() {
        val source = "```openui-json\nroot = TextContent(\"Visible source\")\n```"
        assertEquals(source, OpenUiText.expand(source))
        assertFalse(OpenUiText.containsOpenUi(source))
        assertFalse(OpenUiText.isOpenUiFence("openui-json"))
    }

    @Test
    fun followsMarkdownFenceLengthsAndTildeFences() {
        val example = "````markdown\n```openui-lang\nroot = TextContent(\"Example\")\n```\n````"
        assertFalse(OpenUiText.containsOpenUi(example))
        assertEquals(example, OpenUiText.expand(example))

        val tilde = "Before\n\n~~~openui-lang\nroot = TextContent(\"Converted\")\n~~~\n\nAfter"
        assertTrue(OpenUiText.containsOpenUi(tilde))
        assertEquals("Before\n\nConverted\n\nAfter", OpenUiText.expand(tilde))
    }

    @Test
    fun exportsBareNumericChartSeriesAlongsideOtherText() {
        val source = "```openui-lang\nroot = Card([TextContent(\"Sales\"), BarChart([\"A\", \"B\"], [1, 2])])\n```"
        val expanded = OpenUiText.expand(source)
        assertTrue(expanded.contains("Sales"))
        assertTrue(expanded.contains("| A | 1 |"))
        assertTrue(expanded.contains("| B | 2 |"))
    }

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
