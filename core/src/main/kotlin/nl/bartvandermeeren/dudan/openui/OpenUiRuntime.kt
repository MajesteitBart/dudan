package nl.bartvandermeeren.dudan.openui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

/** A component with its positional arguments mapped to named props. [known] is false for components outside [OpenUiLibrary]. */
class UiNode(val type: String, val props: Map<String, Any?>, val args: List<Any?>, val known: Boolean) {
    operator fun get(name: String): Any? = unbind(props[name])
    fun string(name: String): String? = get(name)?.let(::displayText)?.takeIf { it.isNotEmpty() }
    fun number(name: String): Double? = get(name)?.let { toNumberOrNull(it) }
    fun bool(name: String): Boolean? = get(name) as? Boolean
    fun list(name: String): List<Any?> = get(name) as? List<*> ?: emptyList<Any?>()
    fun nodes(name: String): List<UiNode> = list(name).filterIsInstance<UiNode>()
    fun node(name: String): UiNode? = get(name) as? UiNode
    fun map(name: String): Map<*, *>? = get(name) as? Map<*, *>
}

/** A reference whose statement hasn't streamed in yet. */
data class Pending(val name: String)

/** A prop written as a `$variable`: its current value plus the variable, so an input can write back to it. */
data class Bound(val state: String, val value: Any?)

class ActionPlan(val steps: List<ActionStep>)

sealed interface ActionStep {
    data class ToAssistant(val message: String, val context: String?) : ActionStep
    data class OpenUrl(val url: String) : ActionStep
    /** Evaluated at click time, against the state as it is then. */
    data class SetState(val target: String, val value: Expr, val scope: Map<String, Any?>) : ActionStep
    data class Reset(val targets: List<String>) : ActionStep
}

fun unbind(value: Any?): Any? = if (value is Bound) value.value else value

/**
 * Positional parameters per component, in the order of the zod schemas in @openuidev/react-ui's
 * chat library (openuiChatLibrary). The order is the contract: `Button("Save", action, "primary")`
 * maps "Save" to label because label is the first key of Button's schema.
 */
object OpenUiLibrary {
    private val charts2d = listOf("labels", "series", "variant", "xLabel", "yLabel", "height")

    val params: Map<String, List<String>> = mapOf(
        "Card" to listOf("children", "sources"),
        "Stack" to listOf("children", "direction", "gap", "align", "justify", "wrap"),
        "CardHeader" to listOf("title", "subtitle"),
        "TextContent" to listOf("text", "size"),
        "MarkDownRenderer" to listOf("textMarkdown", "variant"),
        "Callout" to listOf("variant", "title", "description", "visible"),
        "TextCallout" to listOf("variant", "title", "description"),
        "Image" to listOf("alt", "src"),
        "ImageBlock" to listOf("src", "alt"),
        "ImageGallery" to listOf("images"),
        "CodeBlock" to listOf("language", "codeString"),
        "Separator" to listOf("orientation", "decorative"),
        "InlineHeader" to listOf("heading", "description"),
        // v0.5 tables are column-oriented; "rows" keeps v0.1's Table(columns, rows) working.
        "Table" to listOf("columns", "rows"),
        "Col" to listOf("label", "data", "type"),
        "BarChart" to charts2d,
        "LineChart" to charts2d,
        "AreaChart" to charts2d,
        "HorizontalBarChart" to listOf("labels", "series", "variant", "xLabel", "yLabel"),
        "Series" to listOf("category", "values"),
        "PieChart" to listOf("labels", "values", "variant", "appearance"),
        "RadialChart" to listOf("labels", "values"),
        "SingleStackedBarChart" to listOf("labels", "values"),
        "Slice" to listOf("category", "value"),
        "Steps" to listOf("items"),
        "StepsItem" to listOf("title", "details"),
        "Tabs" to listOf("items"),
        "TabItem" to listOf("value", "trigger", "content"),
        "Accordion" to listOf("items"),
        "AccordionItem" to listOf("value", "trigger", "content"),
        "SectionBlock" to listOf("sections", "isFoldable"),
        "SectionItem" to listOf("value", "trigger", "content"),
        "Carousel" to listOf("children", "variant"),
        "TagBlock" to listOf("tags", "size"),
        "Tag" to listOf("text", "icon", "size", "variant"),
        "EntityList" to listOf("rows", "size", "header", "footer"),
        "ListBlock" to listOf("items", "variant", "size"),
        "ListItem" to listOf("title", "subtitle", "image", "actionLabel", "action"),
        "FollowUpBlock" to listOf("items"),
        "FollowUpItem" to listOf("text"),
        "Buttons" to listOf("buttons", "direction"),
        "Button" to listOf("label", "action", "variant", "type", "size"),
        "Form" to listOf("name", "buttons", "fields"),
        "FormControl" to listOf("label", "input", "hint"),
        "Label" to listOf("text"),
        "Input" to listOf("name", "placeholder", "type", "rules", "value"),
        "TextArea" to listOf("name", "placeholder", "rows", "rules", "value"),
        "Select" to listOf("name", "items", "placeholder", "rules", "value", "size"),
        "SelectItem" to listOf("value", "label"),
        "RadioGroup" to listOf("name", "items", "defaultValue", "rules", "value"),
        "RadioItem" to listOf("label", "description", "value"),
        "CheckBoxGroup" to listOf("name", "items", "rules", "value"),
        "CheckBoxItem" to listOf("label", "description", "name", "defaultChecked"),
        "SwitchGroup" to listOf("name", "items", "variant", "value"),
        "SwitchItem" to listOf("label", "description", "name", "defaultChecked"),
        "Slider" to listOf("name", "variant", "min", "max", "step", "defaultValue", "label", "rules", "value"),
        "DatePicker" to listOf("name", "mode", "rules", "value"),
        "Chips" to listOf("name", "type", "items", "rules", "defaultValue"),
        "ChipItem" to listOf("value", "label", "icon", "disabled"),
        "OptionCards" to listOf("name", "type", "items", "rules", "defaultValue"),
        "OptionCard" to listOf("value", "title", "subtitle", "topContent", "disabled"),
        "Icon" to listOf("name", "category"),
    )

    /** Components that hold user input; a form collects their values under their name prop. */
    val inputs = setOf("Input", "TextArea", "Select", "RadioGroup", "CheckBoxGroup", "SwitchGroup", "Slider", "DatePicker", "Chips", "OptionCards")
}

private val dataBuiltins = setOf("Count", "Sum", "Avg", "Min", "Max", "First", "Last", "Filter", "Sort", "Round", "Abs", "Floor", "Ceil")
private val actionSteps = setOf("ToAssistant", "OpenUrl", "Set", "Reset", "Run")

/**
 * Evaluates a program against the current `$variable` values. Each statement is evaluated once per
 * instance; a reference to a statement that hasn't arrived yet becomes [Pending].
 */
class OpenUiEvaluator(private val program: OpenUiProgram, private val state: Map<String, Any?>) {
    private val cache = HashMap<String, Any?>()
    private val visiting = HashSet<String>()

    fun root(): Any? = program.rootId?.let(::resolve)

    fun resolve(name: String): Any? {
        if (cache.containsKey(name)) return cache[name]
        val expr = program.statements[name] ?: return Pending(name)
        if (!visiting.add(name)) return null // A cycle; the reference parser drops it too.
        try {
            return eval(expr, emptyMap()).also { cache[name] = it }
        } finally {
            visiting.remove(name)
        }
    }

    fun eval(expr: Expr, scope: Map<String, Any?>): Any? = when (expr) {
        is Expr.Str -> expr.value
        is Expr.Num -> expr.value
        is Expr.Bool -> expr.value
        Expr.Null -> null
        is Expr.Arr -> expr.items.map { eval(it, scope) }
        is Expr.Obj -> LinkedHashMap<String, Any?>().apply { expr.entries.forEach { (k, v) -> put(k, eval(v, scope)) } }
        is Expr.Ref -> if (scope.containsKey(expr.name)) scope[expr.name] else resolve(expr.name)
        is Expr.StateRef -> state[expr.name]
        is Expr.Assign -> state[expr.target]
        is Expr.Call -> call(expr, scope)
        is Expr.Unary -> when (expr.op) {
            "!" -> !truthy(eval(expr.operand, scope))
            else -> -toNumber(eval(expr.operand, scope))
        }
        is Expr.Ternary -> if (truthy(eval(expr.condition, scope))) eval(expr.then, scope) else eval(expr.otherwise, scope)
        is Expr.Binary -> binary(expr, scope)
        is Expr.Member -> member(unbind(eval(expr.target, scope)), expr.field)
        is Expr.Index -> {
            val target = unbind(eval(expr.target, scope))
            val index = unbind(eval(expr.index, scope))
            when {
                target == null || index == null -> null
                target is List<*> -> target.getOrNull(toNumber(index).toInt())
                target is Map<*, *> -> target[displayText(index)]
                else -> null
            }
        }
    }

    private fun call(call: Expr.Call, scope: Map<String, Any?>): Any? {
        val name = call.name
        val isComponent = name in OpenUiLibrary.params
        return when {
            name == "Each" -> each(call, scope)
            name == "Action" -> {
                val steps = (eval(call.args.firstOrNull() ?: Expr.Null, scope) as? List<*>).orEmpty()
                ActionPlan(steps.filterIsInstance<ActionStep>())
            }
            name in actionSteps && (call.builtin || !isComponent) -> actionStep(name, call.args, scope)
            name in dataBuiltins && (call.builtin || !isComponent) -> dataBuiltin(name, call.args.map { unbind(eval(it, scope)) })
            // dudan has no tools to query; a Query renders with its defaults.
            name == "Query" -> call.args.getOrNull(2)?.let { eval(it, scope) }
            name == "Mutation" -> null
            else -> {
                val args = call.args.map { arg -> if (arg is Expr.StateRef) Bound(arg.name, state[arg.name]) else eval(arg, scope) }
                val params = OpenUiLibrary.params[name]
                val props = if (params != null) params.zip(args).toMap() else emptyMap()
                UiNode(name, props, args, known = params != null)
            }
        }
    }

    private fun actionStep(name: String, args: List<Expr>, scope: Map<String, Any?>): ActionStep? = when (name) {
        "ToAssistant" -> ActionStep.ToAssistant(
            message = args.getOrNull(0)?.let { displayText(unbind(eval(it, scope))) }.orEmpty(),
            context = args.getOrNull(1)?.let { displayText(unbind(eval(it, scope))) }?.takeIf { it.isNotBlank() },
        )
        "OpenUrl" -> ActionStep.OpenUrl(args.getOrNull(0)?.let { displayText(unbind(eval(it, scope))) }.orEmpty())
        "Set" -> (args.getOrNull(0) as? Expr.StateRef)?.let { target ->
            args.getOrNull(1)?.let { ActionStep.SetState(target.name, it, scope) }
        }
        "Reset" -> args.filterIsInstance<Expr.StateRef>().map { it.name }.takeIf { it.isNotEmpty() }?.let(ActionStep::Reset)
        else -> null // @Run needs a Query or Mutation, which dudan doesn't run.
    }

    private fun each(call: Expr.Call, scope: Map<String, Any?>): List<Any?> {
        val items = unbind(eval(call.args.getOrNull(0) ?: return emptyList(), scope)) as? List<*> ?: return emptyList()
        val variable = when (val v = call.args.getOrNull(1)) {
            is Expr.Ref -> v.name
            is Expr.Str -> v.value
            else -> return emptyList()
        }
        val template = call.args.getOrNull(2) ?: return emptyList()
        return items.map { item -> eval(template, scope + (variable to item)) }
    }

    private fun binary(expr: Expr.Binary, scope: Map<String, Any?>): Any? {
        if (expr.op == "&&") {
            val left = unbind(eval(expr.left, scope))
            return if (truthy(left)) unbind(eval(expr.right, scope)) else left
        }
        if (expr.op == "||") {
            val left = unbind(eval(expr.left, scope))
            return if (truthy(left)) left else unbind(eval(expr.right, scope))
        }
        val left = unbind(eval(expr.left, scope)).let { if (it is Pending) null else it }
        val right = unbind(eval(expr.right, scope)).let { if (it is Pending) null else it }
        return when (expr.op) {
            "+" -> if (left is String || right is String) displayText(left) + displayText(right) else toNumber(left) + toNumber(right)
            "-" -> toNumber(left) - toNumber(right)
            "*" -> toNumber(left) * toNumber(right)
            // As in the reference: dividing by zero gives 0, not Infinity.
            "/" -> if (toNumber(right) == 0.0) 0.0 else toNumber(left) / toNumber(right)
            "%" -> if (toNumber(right) == 0.0) 0.0 else toNumber(left) % toNumber(right)
            "==" -> looseEquals(left, right)
            "!=" -> !looseEquals(left, right)
            ">" -> toNumber(left) > toNumber(right)
            "<" -> toNumber(left) < toNumber(right)
            ">=" -> toNumber(left) >= toNumber(right)
            "<=" -> toNumber(left) <= toNumber(right)
            else -> null
        }
    }

    private fun member(target: Any?, field: String): Any? = when (target) {
        null, is Pending -> null
        // Array pluck: rows.title takes title from every row.
        is List<*> -> if (field == "length") target.size.toDouble() else target.map { item -> member(unbind(item), field) }
        is Map<*, *> -> target[field]
        is UiNode -> target[field]
        is String -> if (field == "length") target.length.toDouble() else null
        else -> null
    }

    private fun dataBuiltin(name: String, args: List<Any?>): Any? {
        val list = args.getOrNull(0) as? List<*>
        return when (name) {
            "Count" -> (list?.size ?: 0).toDouble()
            "First" -> list?.firstOrNull()
            "Last" -> list?.lastOrNull()
            "Sum" -> list?.sumOf { toNumber(it) } ?: 0.0
            "Avg" -> if (list.isNullOrEmpty()) 0.0 else list.sumOf { toNumber(it) } / list.size
            "Min" -> list?.takeIf { it.isNotEmpty() }?.minOf { toNumber(it) } ?: 0.0
            "Max" -> list?.takeIf { it.isNotEmpty() }?.maxOf { toNumber(it) } ?: 0.0
            "Filter" -> {
                val field = displayText(args.getOrNull(1))
                val op = displayText(args.getOrNull(2)).ifEmpty { "==" }
                val value = args.getOrNull(3)
                list.orEmpty().filter { item ->
                    val v = if (field.isEmpty()) item else field(item, field)
                    when (op) {
                        "==" -> looseEquals(v, value)
                        "!=" -> !looseEquals(v, value)
                        ">" -> toNumber(v) > toNumber(value)
                        "<" -> toNumber(v) < toNumber(value)
                        ">=" -> toNumber(v) >= toNumber(value)
                        "<=" -> toNumber(v) <= toNumber(value)
                        "contains" -> displayText(v).contains(displayText(value))
                        else -> false
                    }
                }
            }
            "Sort" -> {
                val field = displayText(args.getOrNull(1))
                val descending = displayText(args.getOrNull(2)) == "desc"
                val sorted = list.orEmpty().sortedWith { a, b ->
                    val av = if (field.isEmpty()) a else field(a, field)
                    val bv = if (field.isEmpty()) b else field(b, field)
                    val an = toNumberOrNull(av)
                    val bn = toNumberOrNull(bv)
                    if (an != null && bn != null) an.compareTo(bn) else displayText(av).compareTo(displayText(bv))
                }
                if (descending) sorted.reversed() else sorted
            }
            "Round" -> {
                val factor = 10.0.pow((args.getOrNull(1)?.let(::toNumber) ?: 0.0).coerceIn(0.0, 12.0))
                round(toNumber(args.getOrNull(0)) * factor) / factor
            }
            "Abs" -> abs(toNumber(args.getOrNull(0)))
            "Floor" -> floor(toNumber(args.getOrNull(0)))
            "Ceil" -> ceil(toNumber(args.getOrNull(0)))
            else -> null
        }
    }

    private fun field(item: Any?, path: String): Any? =
        path.split('.').fold(unbind(item)) { acc, key -> (acc as? Map<*, *>)?.get(key) ?: (acc as? UiNode)?.get(key) }

    companion object {
        /** Initial values of the program's `$variables`; referenced but undeclared ones start as null. */
        fun stateDefaults(program: OpenUiProgram): Map<String, Any?> {
            val evaluator = OpenUiEvaluator(program, emptyMap())
            val defaults = LinkedHashMap<String, Any?>()
            program.stateIds.forEach { id -> defaults[id] = unbind(evaluator.eval(program.statements.getValue(id), emptyMap())) }
            return defaults
        }
    }
}

// ---- JavaScript-flavored value helpers ------------------------------------------------------------

fun truthy(value: Any?): Boolean = when (val v = unbind(value)) {
    null, is Pending -> false
    is Boolean -> v
    is Double -> v != 0.0 && !v.isNaN()
    is Number -> v.toDouble() != 0.0
    is String -> v.isNotEmpty()
    else -> true
}

/** A finite number for [value], or null. NaN and infinities count as no number, so no chart or layout ever sees one. */
fun toNumberOrNull(value: Any?): Double? = when (val v = unbind(value)) {
    is Double -> v
    is Number -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    is String -> v.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    else -> null
}?.takeIf { it.isFinite() }

fun toNumber(value: Any?): Double = toNumberOrNull(value) ?: 0.0

/** Text for a value, as JavaScript's String() would give it; null becomes "". */
fun displayText(value: Any?): String = when (val v = unbind(value)) {
    null, is Pending -> ""
    is String -> v
    is Double -> formatNumber(v)
    is Number -> formatNumber(v.toDouble())
    is Boolean -> v.toString()
    is List<*> -> v.joinToString(",") { displayText(it) }
    else -> ""
}

private fun looseEquals(a: Any?, b: Any?): Boolean {
    val x = unbind(a)
    val y = unbind(b)
    if (x == null || y == null) return x == null && y == null
    if (x is String && y is String) return x == y
    if (x is Boolean && y is Boolean) return x == y
    val xn = toNumberOrNull(x)
    val yn = toNumberOrNull(y)
    if ((x is Double || x is Boolean || y is Double || y is Boolean) && xn != null && yn != null) return xn == yn
    return x == y
}
