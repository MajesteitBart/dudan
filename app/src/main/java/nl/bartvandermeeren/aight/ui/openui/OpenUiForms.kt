package nl.bartvandermeeren.aight.ui.openui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Euro
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.openui.Bound
import nl.bartvandermeeren.aight.openui.UiNode
import nl.bartvandermeeren.aight.openui.displayText
import nl.bartvandermeeren.aight.openui.formatNumber
import nl.bartvandermeeren.aight.openui.toNumberOrNull
import nl.bartvandermeeren.aight.openui.unbind
import nl.bartvandermeeren.aight.ui.components.GlassDropdownMenu
import nl.bartvandermeeren.aight.ui.components.Toggle
import nl.bartvandermeeren.aight.ui.components.outlined
import nl.bartvandermeeren.aight.ui.components.pane
import nl.bartvandermeeren.aight.ui.theme.LocalAccent
import nl.bartvandermeeren.aight.ui.theme.Palette

/** Why a field can't be sent yet; [arg] is the limit a rule sets, such as the minimum length. */
internal data class FieldError(val code: String, val arg: String? = null)

/** One Form: its fields and the errors found the last time a button tried to send it. */
internal class FormScope(val name: String) {
    var node: UiNode? = null
    val errors = mutableStateMapOf<String, FieldError>()

    /** The form's inputs with their labels, in order. Fields may be FormControls or bare inputs. */
    fun controls(): List<Pair<String, UiNode>> = formFields(node ?: return emptyList()).mapNotNull { field ->
        if (field.type == "FormControl") {
            val input = field.node("input") ?: return@mapNotNull null
            (field.string("label") ?: input.string("name").orEmpty()) to input
        } else {
            (field.string("label") ?: field.string("name").orEmpty()) to field
        }
    }

    fun validate(scope: OpenUiScope): Boolean {
        errors.clear()
        controls().forEach { (_, input) -> validateField(input, fieldValue(scope, this, input))?.let { errors[input.string("name").orEmpty()] = it } }
        return errors.isEmpty()
    }

    /** One "label: value" line per filled-in field, as it goes into the message to the agent. */
    fun summary(scope: OpenUiScope): String = controls().mapNotNull { (label, input) ->
        displayValue(input, fieldValue(scope, this, input))?.let { "$label: $it" }
    }.joinToString("\n")
}

internal val LocalFormScope = compositionLocalOf<FormScope?> { null }

/** Form(name, buttons, fields), and v0.1's Form(name, fields, buttons). */
private fun formFields(n: UiNode): List<UiNode> = when (val fields = n["fields"]) {
    is List<*> -> fields.mapNotNull { unbind(it) as? UiNode }
    else -> (n["buttons"] as? List<*>)?.mapNotNull { unbind(it) as? UiNode }.orEmpty()
}

private fun formButtons(n: UiNode): UiNode? = (n["buttons"] as? UiNode) ?: (n["fields"] as? UiNode)

@Composable
internal fun FormView(n: UiNode, modifier: Modifier = Modifier) {
    val name = n.string("name").orEmpty()
    val form = remember(name) { FormScope(name) }
    form.node = n
    CompositionLocalProvider(LocalFormScope provides form) {
        Column(
            modifier.fillMaxWidth().pane(RoundedCornerShape(20.dp), Palette.Surface, outline = Palette.Hairline).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            formFields(n).forEach { Render(it) }
            formButtons(n)?.let { Render(it) }
        }
    }
}

@Composable
internal fun FormControlView(n: UiNode) {
    val input = n.node("input")
    val form = LocalFormScope.current
    val error = input?.string("name")?.let { form?.errors?.get(it) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        n.string("label")?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary) }
        input?.let { InputView(it) }
        when {
            error != null -> Text(errorText(error), style = MaterialTheme.typography.bodySmall, color = Palette.Danger)
            else -> n.string("hint")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary) }
        }
    }
}

@Composable
private fun errorText(error: FieldError): String = when (error.code) {
    "email" -> stringResource(R.string.openui_error_email)
    "url" -> stringResource(R.string.openui_error_url)
    "numeric" -> stringResource(R.string.openui_error_number)
    "minLength" -> stringResource(R.string.openui_error_min_length, error.arg.orEmpty())
    "maxLength" -> stringResource(R.string.openui_error_max_length, error.arg.orEmpty())
    "min" -> stringResource(R.string.openui_error_min, error.arg.orEmpty())
    "max" -> stringResource(R.string.openui_error_max, error.arg.orEmpty())
    "pattern" -> stringResource(R.string.openui_error_pattern)
    else -> stringResource(R.string.openui_error_required)
}

// ---- Values ---------------------------------------------------------------------------------------

private fun fieldKey(form: FormScope?, input: UiNode) = "${form?.name.orEmpty()}/${input.string("name").orEmpty()}"

internal fun fieldValue(scope: OpenUiScope, form: FormScope?, input: UiNode): Any? {
    (input.props["value"] as? Bound)?.let { bound ->
        return if (scope.state.containsKey(bound.state)) scope.state[bound.state] else scope.defaults[bound.state] ?: defaultValue(input)
    }
    val key = fieldKey(form, input)
    return if (scope.inputs.containsKey(key)) scope.inputs[key] else defaultValue(input)
}

private fun setFieldValue(scope: OpenUiScope, form: FormScope?, input: UiNode, value: Any?) {
    val bound = input.props["value"] as? Bound
    if (bound != null) scope.state[bound.state] = value else scope.inputs[fieldKey(form, input)] = value
    form?.errors?.remove(input.string("name").orEmpty())
}

private fun stringList(value: Any?): List<String> = when (val v = unbind(value)) {
    null -> emptyList()
    is List<*> -> v.map { displayText(it) }.filter { it.isNotEmpty() }
    else -> listOf(displayText(v)).filter { it.isNotEmpty() }
}

private fun defaultValue(input: UiNode): Any? = when (input.type) {
    "CheckBoxGroup", "SwitchGroup" -> input.nodes("items").associate { it.string("name").orEmpty() to (it.bool("defaultChecked") ?: false) }
    "RadioGroup" -> input.string("value") ?: input.string("defaultValue")
    "Slider" -> (input.list("defaultValue").mapNotNull { toNumberOrNull(it) }.takeIf { it.isNotEmpty() }
        ?: input.list("value").mapNotNull { toNumberOrNull(it) }.takeIf { it.isNotEmpty() }
        ?: listOf(input.number("min") ?: 0.0))
    "Chips", "OptionCards" -> stringList(input["defaultValue"])
    else -> input.string("value")
}

/** What a field holds, as the agent gets it; null leaves the field out of the message. */
private fun displayValue(input: UiNode, value: Any?): String? {
    fun labelOf(items: List<UiNode>, key: String, v: String) = items.firstOrNull { it.string(key) == v }?.let { it.string("label") ?: it.string("title") } ?: v
    return when (input.type) {
        "Select" -> displayText(value).takeIf { it.isNotEmpty() }?.let { labelOf(input.nodes("items"), "value", it) }
        "RadioGroup" -> displayText(value).takeIf { it.isNotEmpty() }?.let { labelOf(input.nodes("items"), "value", it) }
        "CheckBoxGroup", "SwitchGroup" -> {
            val checked = (value as? Map<*, *>).orEmpty()
            input.nodes("items").filter { checked[it.string("name")] == true }
                .map { it.string("label") ?: it.string("name").orEmpty() }
                .takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
        "Chips", "OptionCards" -> stringList(value).map { labelOf(input.nodes("items"), "value", it) }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        "Slider" -> stringList(value).takeIf { it.isNotEmpty() }?.joinToString(" – ")
        "DatePicker" -> stringList(value).takeIf { it.isNotEmpty() }?.joinToString(" – ")
        else -> displayText(value).trim().takeIf { it.isNotEmpty() }
    }
}

private val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

internal fun validateField(input: UiNode, value: Any?): FieldError? {
    val rules = input.map("rules").orEmpty()
    fun rule(name: String) = unbind(rules[name])
    val empty = when (input.type) {
        "CheckBoxGroup", "SwitchGroup" -> (value as? Map<*, *>)?.values?.none { it == true } ?: true
        "Chips", "OptionCards", "DatePicker", "Slider" -> stringList(value).isEmpty()
        else -> displayText(value).isBlank()
    }
    if (empty) return if (rule("required") == true) FieldError("required") else null
    if (input.type != "Input" && input.type != "TextArea") return null
    val text = displayText(value).trim()
    if (rule("email") == true || input.string("type") == "email") if (!emailPattern.matches(text)) return FieldError("email")
    if (rule("url") == true || input.string("type") == "url") {
        if (!(text.startsWith("http://") || text.startsWith("https://")) || text.length < 10) return FieldError("url")
    }
    val number = text.replace(',', '.').toDoubleOrNull()
    if ((rule("numeric") == true || input.string("type") == "number") && number == null) return FieldError("numeric")
    toNumberOrNull(rule("minLength"))?.let { if (text.length < it) return FieldError("minLength", formatNumber(it)) }
    toNumberOrNull(rule("maxLength"))?.let { if (text.length > it) return FieldError("maxLength", formatNumber(it)) }
    if (number != null) {
        toNumberOrNull(rule("min"))?.let { if (number < it) return FieldError("min", formatNumber(it)) }
        toNumberOrNull(rule("max"))?.let { if (number > it) return FieldError("max", formatNumber(it)) }
    }
    (rule("pattern") as? String)?.let { pattern ->
        val regex = runCatching { Regex(pattern) }.getOrNull()
        if (regex != null && !regex.matches(text)) return FieldError("pattern")
    }
    return null
}

// ---- Inputs ---------------------------------------------------------------------------------------

@Composable
internal fun InputView(n: UiNode) {
    val scope = LocalOpenUiScope.current ?: return
    val form = LocalFormScope.current
    val value = fieldValue(scope, form, n)
    val set: (Any?) -> Unit = { setFieldValue(scope, form, n, it) }
    val invalid = form?.errors?.containsKey(n.string("name").orEmpty()) == true
    when (n.type) {
        "Input" -> TextBox(displayText(value), n.string("placeholder"), invalid, minLines = 1, keyboard = n.string("type")) { set(it) }
        "TextArea" -> TextBox(displayText(value), n.string("placeholder"), invalid, minLines = (n.number("rows") ?: 3.0).toInt().coerceIn(2, 8), keyboard = null) { set(it) }
        "Select" -> SelectBox(n, displayText(value), invalid, set)
        "RadioGroup" -> RadioList(n, displayText(value), set)
        "CheckBoxGroup" -> CheckList(n, (value as? Map<*, *>).orEmpty(), set)
        "SwitchGroup" -> SwitchList(n, (value as? Map<*, *>).orEmpty(), set)
        "Slider" -> SliderBox(n, stringList(value).mapNotNull { it.toDoubleOrNull() }, set)
        "DatePicker" -> DateBox(n, stringList(value), invalid, set)
        "Chips" -> ChipsBox(n, stringList(value), set)
        "OptionCards" -> OptionCardsBox(n, stringList(value), set)
    }
}

private val FieldShape = RoundedCornerShape(14.dp)

@Composable
private fun fieldOutline(invalid: Boolean) = if (invalid) Palette.Danger else Palette.Outline

@Composable
private fun TextBox(value: String, placeholder: String?, invalid: Boolean, minLines: Int, keyboard: String?, onChange: (String) -> Unit) {
    val type = when (keyboard) {
        "email" -> KeyboardType.Email
        "number" -> KeyboardType.Decimal
        "url" -> KeyboardType.Uri
        "password" -> KeyboardType.Password
        else -> KeyboardType.Text
    }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.TextPrimary),
        cursorBrush = SolidColor(LocalAccent.current.soft),
        singleLine = minLines == 1,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier
            .fillMaxWidth()
            .outlined(FieldShape, Palette.Surface, fieldOutline(invalid))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && !placeholder.isNullOrBlank()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Palette.TextTertiary, maxLines = minLines)
                }
                inner()
            }
        },
    )
}

@Composable
private fun FieldButton(text: String?, placeholder: String, invalid: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .outlined(FieldShape, Palette.Surface, fieldOutline(invalid))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text ?: placeholder,
            style = MaterialTheme.typography.bodyLarge,
            color = if (text != null) Palette.TextPrimary else Palette.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SelectBox(n: UiNode, value: String, invalid: Boolean, set: (Any?) -> Unit) {
    val items = n.nodes("items")
    var open by remember { mutableStateOf(false) }
    val selected = items.firstOrNull { it.string("value") == value }
    Box {
        FieldButton(selected?.string("label") ?: value.ifEmpty { null }, n.string("placeholder") ?: stringResource(R.string.openui_choose), invalid) { open = true }
        GlassDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.string("label") ?: item.string("value").orEmpty(), color = Palette.TextPrimary) },
                    trailingIcon = if (item === selected) ({ Icon(Icons.Outlined.Check, null, tint = LocalAccent.current.soft) }) else null,
                    onClick = {
                        set(item.string("value"))
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, detail: String?, onClick: () -> Unit, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        control()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
        }
    }
}

@Composable
private fun RadioList(n: UiNode, value: String, set: (Any?) -> Unit) {
    val accent = LocalAccent.current
    Column {
        n.nodes("items").forEach { item ->
            val itemValue = item.string("value") ?: item.string("label").orEmpty()
            OptionRow(item.string("label") ?: itemValue, item.string("description"), onClick = { set(itemValue) }) {
                RadioButton(
                    selected = itemValue == value,
                    onClick = { set(itemValue) },
                    colors = RadioButtonDefaults.colors(selectedColor = accent.color, unselectedColor = Palette.Outline),
                )
            }
        }
    }
}

@Composable
private fun CheckList(n: UiNode, checked: Map<*, *>, set: (Any?) -> Unit) {
    val accent = LocalAccent.current
    Column {
        n.nodes("items").forEach { item ->
            val name = item.string("name") ?: item.string("label").orEmpty()
            val on = checked[name] == true
            val toggle = { set(checked.entries.associate { displayText(it.key) to (it.value == true) } + (name to !on)) }
            OptionRow(item.string("label") ?: name, item.string("description"), onClick = toggle) {
                Checkbox(
                    checked = on,
                    onCheckedChange = { toggle() },
                    colors = CheckboxDefaults.colors(checkedColor = accent.color, checkmarkColor = accent.on, uncheckedColor = Palette.Outline),
                )
            }
        }
    }
}

@Composable
private fun SwitchList(n: UiNode, checked: Map<*, *>, set: (Any?) -> Unit) {
    Column {
        n.nodes("items").forEach { item ->
            val name = item.string("name") ?: item.string("label").orEmpty()
            Toggle(item.string("label") ?: name, item.string("description").orEmpty(), checked[name] == true) { on ->
                set(checked.entries.associate { displayText(it.key) to (it.value == true) } + (name to on))
            }
        }
    }
}

@Composable
private fun SliderBox(n: UiNode, value: List<Double>, set: (Any?) -> Unit) {
    val min = n.number("min") ?: 0.0
    val max = (n.number("max") ?: 100.0).coerceAtLeast(min + 1e-9)
    val step = n.number("step")?.takeIf { it > 0 } ?: if (n.string("variant") == "discrete") 1.0 else null
    val steps = step?.let { (((max - min) / it).roundToInt() - 1).coerceIn(0, 200) } ?: 0
    fun snap(v: Float): Double {
        val raw = v.toDouble().coerceIn(min, max)
        return if (step != null) min + ((raw - min) / step).roundToInt() * step else (raw * 100).roundToInt() / 100.0
    }
    val accent = LocalAccent.current
    val colors = SliderDefaults.colors(thumbColor = accent.color, activeTrackColor = accent.color, inactiveTrackColor = Palette.Outline, activeTickColor = accent.on, inactiveTickColor = Palette.TextTertiary)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        n.string("label")?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary) }
        if (value.size >= 2) {
            RangeSlider(
                value = value[0].toFloat()..value[1].toFloat(),
                onValueChange = { set(listOf(snap(it.start), snap(it.endInclusive))) },
                valueRange = min.toFloat()..max.toFloat(),
                steps = steps,
                colors = colors,
            )
        } else {
            Slider(
                value = (value.firstOrNull() ?: min).toFloat(),
                onValueChange = { set(listOf(snap(it))) },
                valueRange = min.toFloat()..max.toFloat(),
                steps = steps,
                colors = colors,
            )
        }
        Text(value.joinToString(" – ") { formatNumber(it) }, style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateBox(n: UiNode, value: List<String>, invalid: Boolean, set: (Any?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val range = n.string("mode") == "range"
    FieldButton(value.takeIf { it.isNotEmpty() }?.joinToString(" – "), stringResource(R.string.openui_pick_date), invalid) { open = true }
    if (!open) return
    fun day(millis: Long?) = millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
    fun millis(date: String?) = date?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() }
    if (range) {
        val state = rememberDateRangePickerState(initialSelectedStartDateMillis = millis(value.getOrNull(0)), initialSelectedEndDateMillis = millis(value.getOrNull(1)))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    set(listOfNotNull(day(state.selectedStartDateMillis), day(state.selectedEndDateMillis)))
                    open = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
        ) { DateRangePicker(state, modifier = Modifier.weight(1f)) }
    } else {
        val state = rememberDatePickerState(initialSelectedDateMillis = millis(value.firstOrNull()))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    set(day(state.selectedDateMillis))
                    open = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsBox(n: UiNode, value: List<String>, set: (Any?) -> Unit) {
    val single = n.string("type") == "single"
    val accent = LocalAccent.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        n.nodes("items").forEach { item ->
            val itemValue = item.string("value") ?: item.string("label").orEmpty()
            val active = itemValue in value
            val disabled = item.bool("disabled") == true
            Row(
                Modifier
                    .then(if (active) Modifier.pane(CircleShape, accent.color) else Modifier.outlined(CircleShape))
                    .clickable(enabled = !disabled) {
                        set(if (single) listOf(itemValue) else if (active) value - itemValue else value + itemValue)
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.node("icon")?.let { OpenUiIcon(it.string("name"), Modifier.size(16.dp), if (active) accent.on else Palette.TextSecondary) }
                Text(
                    item.string("label") ?: itemValue,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        disabled -> Palette.TextTertiary
                        active -> accent.on
                        else -> Palette.TextPrimary
                    },
                )
            }
        }
    }
}

@Composable
private fun OptionCardsBox(n: UiNode, value: List<String>, set: (Any?) -> Unit) {
    val multiple = n.string("type") == "multiple"
    val accent = LocalAccent.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        n.nodes("items").forEach { item ->
            val itemValue = item.string("value") ?: item.string("title").orEmpty()
            val active = itemValue in value
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .outlined(shape, if (active) accent.bubble else Palette.Surface, if (active) accent.color else Palette.Outline)
                    .clickable(enabled = item.bool("disabled") != true) {
                        set(if (!multiple) listOf(itemValue) else if (active) value - itemValue else value + itemValue)
                    }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                (item.node("topContent"))?.takeIf { it.type == "Icon" }?.let { OpenUiIcon(it.string("name"), Modifier.size(22.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(item.string("title") ?: itemValue, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = Palette.TextPrimary)
                    item.string("subtitle")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
                }
                if (active) Icon(Icons.Outlined.Check, contentDescription = null, tint = accent.soft, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ---- Icons ----------------------------------------------------------------------------------------

/** OpenUI names lucide icons; these are the common ones, drawn with their Material look-alikes. */
private val icons: Map<String, ImageVector> = mapOf(
    "check" to Icons.Outlined.Check,
    "x" to Icons.Outlined.Close,
    "info" to Icons.Outlined.Info,
    "alert-triangle" to Icons.Outlined.WarningAmber,
    "triangle-alert" to Icons.Outlined.WarningAmber,
    "star" to Icons.Outlined.StarOutline,
    "heart" to Icons.Outlined.FavoriteBorder,
    "calendar" to Icons.Outlined.CalendarToday,
    "clock" to Icons.Outlined.Schedule,
    "map-pin" to Icons.Outlined.Place,
    "phone" to Icons.Outlined.Phone,
    "mail" to Icons.Outlined.Mail,
    "user" to Icons.Outlined.Person,
    "users" to Icons.Outlined.Group,
    "dollar-sign" to Icons.Outlined.Payments,
    "circle-dollar-sign" to Icons.Outlined.Payments,
    "euro" to Icons.Outlined.Euro,
    "trending-up" to Icons.AutoMirrored.Outlined.TrendingUp,
    "trending-down" to Icons.AutoMirrored.Outlined.TrendingDown,
    "home" to Icons.Outlined.Home,
    "house" to Icons.Outlined.Home,
    "settings" to Icons.Outlined.Settings,
    "search" to Icons.Outlined.Search,
    "link" to Icons.Outlined.Link,
    "file" to Icons.Outlined.Description,
    "file-text" to Icons.Outlined.Description,
    "zap" to Icons.Outlined.Bolt,
    "sun" to Icons.Outlined.WbSunny,
    "cloud" to Icons.Outlined.Cloud,
    "car" to Icons.Outlined.DirectionsCar,
    "shopping-cart" to Icons.Outlined.ShoppingCart,
    "package" to Icons.Outlined.Inventory2,
    "box" to Icons.Outlined.Inventory2,
    "globe" to Icons.Outlined.Public,
    "lightbulb" to Icons.Outlined.Lightbulb,
    "target" to Icons.Outlined.TrackChanges,
)

/** True when [OpenUiIcon] can draw [name]; other names draw nothing. */
internal fun hasOpenUiIcon(name: String): Boolean = name.lowercase() in icons

@Composable
internal fun OpenUiIcon(name: String?, modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color = Palette.TextSecondary) {
    val icon = icons[name?.lowercase()] ?: return
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
}
