package nl.bartvandermeeren.aight.ui.openui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.openui.ActionPlan
import nl.bartvandermeeren.aight.openui.ActionStep
import nl.bartvandermeeren.aight.openui.OpenUiEvaluator
import nl.bartvandermeeren.aight.openui.OpenUiParser
import nl.bartvandermeeren.aight.openui.OpenUiProgram
import nl.bartvandermeeren.aight.openui.OpenUiText
import nl.bartvandermeeren.aight.openui.Pending
import nl.bartvandermeeren.aight.openui.UiNode
import nl.bartvandermeeren.aight.openui.displayText
import nl.bartvandermeeren.aight.openui.truthy
import nl.bartvandermeeren.aight.openui.unbind
import nl.bartvandermeeren.aight.ui.components.CodeBlock
import nl.bartvandermeeren.aight.ui.components.ImageCodec
import nl.bartvandermeeren.aight.ui.components.Markdown
import nl.bartvandermeeren.aight.ui.components.TableGrid
import nl.bartvandermeeren.aight.ui.components.outlined
import nl.bartvandermeeren.aight.ui.components.pane
import nl.bartvandermeeren.aight.ui.theme.LocalAccent
import nl.bartvandermeeren.aight.ui.theme.Palette

/**
 * What OpenUI actions do where a block is shown. [send] posts a user message in the block's chat and
 * returns false when it can't (the chat is still busy); [openUrl] opens a link.
 */
class OpenUiHost(val send: (String) -> Boolean, val openUrl: (String) -> Unit)

val LocalOpenUiHost = staticCompositionLocalOf<OpenUiHost?> { null }

/** True while the message a block belongs to is still streaming; unresolved parts show as placeholders then. */
val LocalStreaming = staticCompositionLocalOf { false }

/** Everything the components of one block share: its program, its `$variables` and the values typed into it. */
internal class OpenUiScope(
    val program: OpenUiProgram,
    val defaults: Map<String, Any?>,
    val state: SnapshotStateMap<String, Any?>,
    val inputs: SnapshotStateMap<String, Any?>,
    val streaming: Boolean,
    val host: OpenUiHost?,
    val busyNotice: () -> Unit,
) {
    fun values(): Map<String, Any?> = defaults + state

    /** Runs a Button's or ListItem's action. Without an action, pressing it sends [label], as in the reference renderer. */
    fun run(action: Any?, label: String, form: FormScope?) {
        for (step in stepsFor(unbind(action), label)) {
            when (step) {
                is ActionStep.ToAssistant -> {
                    if (form != null && !form.validate(this)) return
                    val message = buildString {
                        append(step.message.ifBlank { label })
                        step.context?.takeIf { it != step.message }?.let { append("\n\n").append(it) }
                        form?.summary(this@OpenUiScope)?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
                    }.trim()
                    val host = host ?: return
                    if (message.isNotEmpty() && !host.send(message)) busyNotice()
                }
                is ActionStep.OpenUrl -> host?.openUrl(step.url)
                is ActionStep.SetState -> state[step.target] = unbind(OpenUiEvaluator(program, values()).eval(step.value, step.scope))
                is ActionStep.Reset -> step.targets.forEach { state.remove(it) }
            }
        }
    }

    private fun stepsFor(action: Any?, label: String): List<ActionStep> = when (action) {
        is ActionPlan -> action.steps
        is ActionStep -> listOf(action)
        // The v0.1 action objects: {type: "open_url", url} and {type: "continue_conversation", context}.
        is Map<*, *> -> {
            val params = action["params"] as? Map<*, *>
            when (displayText(action["type"])) {
                "open_url" -> listOf(ActionStep.OpenUrl(displayText(action["url"] ?: params?.get("url"))))
                else -> listOf(ActionStep.ToAssistant(label, displayText(action["context"] ?: params?.get("context")).takeIf { it.isNotBlank() }))
            }
        }
        else -> listOf(ActionStep.ToAssistant(label, null))
    }
}

internal val LocalOpenUiScope = compositionLocalOf<OpenUiScope?> { null }

/**
 * Renders one ```openui-lang block. A block that doesn't parse into anything shows its source as a
 * code block, so a malformed reply never loses its content.
 */
@Composable
fun OpenUiBlock(source: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val streaming = LocalStreaming.current
    val host = LocalOpenUiHost.current
    val program = remember(source) { OpenUiParser.parse(source) }
    val defaults = remember(program) { runCatching { OpenUiEvaluator.stateDefaults(program) }.getOrDefault(emptyMap()) }
    // Survives the re-parse on every streamed chunk, and the message scrolling out of the list and back.
    val state = rememberSaveable(saver = ValuesSaver) { mutableStateMapOf<String, Any?>() }
    val inputs = rememberSaveable(saver = ValuesSaver) { mutableStateMapOf<String, Any?>() }
    val busy = stringResource(R.string.openui_busy)
    val scope = OpenUiScope(program, defaults, state, inputs, streaming, host) {
        Toast.makeText(context, busy, Toast.LENGTH_SHORT).show()
    }
    // A bug the evaluator hits on some model output must not take the app down with it; the block
    // then shows as code. The reply is stored, so a crash here would repeat on every visit.
    val root = runCatching { OpenUiEvaluator(program, scope.values()).root() }.getOrNull()
    CompositionLocalProvider(LocalOpenUiScope provides scope) {
        Box(modifier.fillMaxWidth()) {
            when {
                root is UiNode || (root is List<*> && root.isNotEmpty()) -> Render(root, topLevel = true)
                streaming -> Skeleton()
                source.isNotBlank() -> CodeBlock("openui-lang", source.trimEnd())
            }
        }
    }
}

/**
 * Saves a block's `$variables` and typed values as JSON, which a saved-state bundle can hold. They
 * are strings, numbers, booleans and lists or maps of those.
 */
private val ValuesSaver = Saver<SnapshotStateMap<String, Any?>, String>(
    save = { map -> JsonObject(map.mapValues { (_, v) -> toJson(v) }).toString() },
    restore = { text ->
        mutableStateMapOf<String, Any?>().apply {
            (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.forEach { (k, v) -> put(k, fromJson(v)) }
        }
    },
)

private fun toJson(value: Any?): JsonElement = when (val v = unbind(value)) {
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map(::toJson))
    is Map<*, *> -> JsonObject(v.entries.associate { (k, item) -> k.toString() to toJson(item) })
    else -> JsonNull
}

private fun fromJson(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.doubleOrNull
    is JsonArray -> element.map(::fromJson)
    is JsonObject -> LinkedHashMap<String, Any?>().apply { element.forEach { (k, v) -> put(k, fromJson(v)) } }
}

/** Opens [url] in the app that handles it. Only web, mail, phone, map and app links; never files or intents. */
fun openExternalUrl(context: android.content.Context, url: String) {
    val uri = runCatching { url.trim().toUri() }.getOrNull() ?: return
    if (uri.scheme?.lowercase() !in setOf("http", "https", "mailto", "tel", "geo", "sms")) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing handles it; leave the reply as it is.
    }
}

// ---- Dispatch -------------------------------------------------------------------------------------

@Composable
internal fun Render(value: Any?, modifier: Modifier = Modifier, topLevel: Boolean = false) {
    when (val v = unbind(value)) {
        is UiNode -> Node(v, modifier, topLevel)
        is List<*> -> Children(v, modifier)
        is String -> if (v.isNotBlank()) Markdown(v, modifier)
        is Pending -> if (LocalStreaming.current) Skeleton(modifier)
        is Double, is Boolean -> Text(displayText(v), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = modifier)
        else -> Unit
    }
}

/** A vertical run of components, as in a Card or a tab. */
@Composable
internal fun Children(items: List<*>, modifier: Modifier = Modifier, spacing: Dp = 14.dp) {
    val streaming = LocalStreaming.current
    val visible = items.filter { item ->
        when (val v = unbind(item)) {
            null -> false
            is Pending -> streaming
            is String -> v.isNotBlank()
            else -> true
        }
    }
    if (visible.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        visible.forEach { Render(it) }
    }
}

@Composable
private fun Node(n: UiNode, modifier: Modifier, topLevel: Boolean) {
    when (n.type) {
        "Card" -> CardView(n, modifier, topLevel)
        "Stack" -> StackView(n, modifier)
        "CardHeader" -> CardHeaderView(n, modifier)
        "InlineHeader" -> InlineHeaderView(n, modifier)
        "TextContent" -> TextContentView(n, modifier)
        "MarkDownRenderer" -> MarkdownView(n, modifier)
        "Label" -> n.string("text")?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = modifier) }
        "Callout", "TextCallout" -> if (n.props["visible"] == null || truthy(n["visible"])) CalloutView(n, modifier)
        "CodeBlock" -> CodeBlock(n.string("language"), n.string("codeString").orEmpty())
        "Separator" -> HorizontalDivider(color = Palette.Hairline, modifier = modifier.padding(vertical = 2.dp))
        "Image", "ImageBlock" -> n.string("src")?.let { RemoteImage(it, n.string("alt"), modifier) }
        "ImageGallery" -> GalleryView(n, modifier)
        "Table" -> TableView(n, modifier)
        "BarChart", "LineChart", "AreaChart", "HorizontalBarChart" -> SeriesChart(n, modifier)
        "PieChart", "RadialChart", "SingleStackedBarChart" -> ShareChart(n, modifier)
        "Steps" -> StepsView(n, modifier)
        "Tabs" -> TabsView(n, modifier)
        "Accordion" -> FoldView(n.nodes("items"), openByDefault = false, foldable = true, modifier)
        "SectionBlock" -> FoldView(n.nodes("sections"), openByDefault = true, foldable = n.bool("isFoldable") != false, modifier)
        "Carousel" -> CarouselView(n, modifier)
        "TagBlock" -> TagBlockView(n, modifier)
        "Tag" -> TagView(n.string("text").orEmpty(), n.string("variant"))
        "EntityList" -> EntityListView(n, modifier)
        "ListBlock" -> ListBlockView(n, modifier)
        "FollowUpBlock" -> FollowUpView(n, modifier)
        "Buttons" -> ButtonsView(n, modifier)
        "Button" -> ButtonView(n)
        "Form" -> FormView(n, modifier)
        "FormControl" -> FormControlView(n)
        "Icon" -> OpenUiIcon(n.string("name"), Modifier.size(20.dp))
        in INPUT_TYPES -> InputView(n)
        else -> Fallback(n, modifier)
    }
}

private val INPUT_TYPES = nl.bartvandermeeren.aight.openui.OpenUiLibrary.inputs

// ---- Layout ---------------------------------------------------------------------------------------

/** The reply's own Card sits on the sky like any answer; a Card inside it gets a quiet pane. */
@Composable
private fun CardView(n: UiNode, modifier: Modifier, topLevel: Boolean) {
    val content: @Composable () -> Unit = {
        Children(n.list("children"))
        Sources(n.list("sources"))
    }
    if (topLevel) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    } else {
        Column(
            modifier.fillMaxWidth().pane(RoundedCornerShape(20.dp), Palette.Surface, outline = Palette.Hairline).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

private fun gap(name: String?): Dp = when (name) {
    "none" -> 0.dp
    "xs" -> 4.dp
    "s" -> 8.dp
    "l" -> 20.dp
    "xl" -> 28.dp
    "2xl" -> 36.dp
    else -> 14.dp
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StackView(n: UiNode, modifier: Modifier) {
    val children = n.list("children").filter { unbind(it) != null }
    val spacing = gap(n.string("gap"))
    if (n.string("direction") == "row") {
        // A phone is too narrow for a row of cards; two to a line, wrapping.
        FlowRow(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            maxItemsInEachRow = 2,
        ) {
            children.forEach { child -> Box(Modifier.weight(1f)) { Render(child) } }
        }
    } else {
        Children(children, modifier, spacing)
    }
}

// ---- Content ----------------------------------------------------------------------------------------

@Composable
private fun CardHeaderView(n: UiNode, modifier: Modifier) {
    val title = n.string("title")
    val subtitle = n.string("subtitle")
    if (title == null && subtitle == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium), color = Palette.TextPrimary) }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary) }
    }
}

@Composable
private fun InlineHeaderView(n: UiNode, modifier: Modifier) {
    Column(modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        n.string("heading")?.let { Text(it, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Palette.TextPrimary) }
        n.string("description")?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary) }
    }
}

@Composable
private fun TextContentView(n: UiNode, modifier: Modifier) {
    val text = n.string("text") ?: return
    val base = MaterialTheme.typography.bodyLarge
    val style = when (n.string("size")) {
        "small" -> MaterialTheme.typography.bodyMedium
        "small-heavy" -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        "large" -> base.copy(fontSize = 19.sp, lineHeight = 27.sp)
        "large-heavy" -> base.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        else -> base
    }
    Markdown(text, modifier, style)
}

@Composable
private fun MarkdownView(n: UiNode, modifier: Modifier) {
    val text = n.string("textMarkdown") ?: return
    when (n.string("variant")) {
        "card", "sunk" -> Box(modifier.fillMaxWidth().pane(RoundedCornerShape(18.dp), if (n.string("variant") == "sunk") Palette.Code else Palette.Surface, outline = Palette.Hairline).padding(16.dp)) {
            Markdown(text)
        }
        else -> Markdown(text, modifier)
    }
}

@Composable
private fun CalloutView(n: UiNode, modifier: Modifier) {
    val variant = n.string("variant") ?: "neutral"
    val (tint, fill) = when (variant) {
        "info" -> Palette.SparkBlue to Palette.SparkBlue.copy(alpha = 0.16f)
        "warning" -> Palette.SparkAmber to Palette.WarningPane
        "error", "danger" -> Palette.Danger to Palette.DangerPane
        "success" -> Palette.Success to Palette.Success.copy(alpha = 0.14f)
        else -> Palette.TextSecondary to Palette.Surface
    }
    val icon = when (variant) {
        "warning" -> Icons.Outlined.WarningAmber
        "error", "danger" -> Icons.Outlined.ErrorOutline
        "success" -> Icons.Outlined.CheckCircle
        else -> Icons.Outlined.Info
    }
    Row(
        modifier.fillMaxWidth().pane(RoundedCornerShape(18.dp), fill, outline = tint.copy(alpha = 0.3f)).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            n.string("title")?.let { Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = Palette.TextPrimary) }
            n.string("description")?.let { Markdown(it, style = MaterialTheme.typography.bodyMedium.copy(color = Palette.TextPrimary)) }
        }
    }
}

@Composable
private fun Sources(sources: List<*>) {
    val items = sources.mapNotNull { unbind(it) as? Map<*, *> }.filter { displayText(it["url"]).isNotBlank() }
    if (items.isEmpty()) return
    val scope = LocalOpenUiScope.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.openui_sources), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
        items.forEachIndexed { index, source ->
            val url = displayText(source["url"])
            val title = displayText(source["title"]).ifBlank { url }
            val name = displayText(source["sourceName"])
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { scope?.host?.openUrl(url) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).pane(CircleShape, Palette.Surface, outline = Palette.Hairline), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = LocalAccent.current.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary, maxLines = 1)
                }
            }
        }
    }
}

private sealed interface RemoteState {
    data object Loading : RemoteState
    data class Loaded(val image: ImageBitmap) : RemoteState
    data object Failed : RemoteState
}

@Composable
private fun rememberRemoteImage(src: String, maxDimension: Int): RemoteState {
    val context = LocalContext.current
    val state by produceState<RemoteState>(RemoteState.Loading, src) {
        value = withContext(Dispatchers.IO) {
            ImageCodec.decodeThumbnailCached(context, src, maxDimension)
        }?.let { RemoteState.Loaded(it) } ?: RemoteState.Failed
    }
    return state
}

@Composable
private fun RemoteImage(src: String, alt: String?, modifier: Modifier) {
    val scope = LocalOpenUiScope.current
    val shape = RoundedCornerShape(18.dp)
    when (val image = rememberRemoteImage(src, 1280)) {
        is RemoteState.Loaded -> Image(
            image.image,
            contentDescription = alt,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio((image.image.width.toFloat() / image.image.height).coerceIn(0.6f, 2.4f))
                .clip(shape)
                .clickable { scope?.host?.openUrl(src) },
        )
        RemoteState.Loading -> Box(modifier.fillMaxWidth().height(180.dp).pane(shape, Palette.Surface))
        RemoteState.Failed -> alt?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, modifier = modifier.pane(shape, Palette.Surface, outline = Palette.Hairline).padding(14.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryView(n: UiNode, modifier: Modifier) {
    val images = n.list("images").mapNotNull { unbind(it) as? Map<*, *> }.filter { displayText(it["src"]).isNotBlank() }
    if (images.isEmpty()) return
    val scope = LocalOpenUiScope.current
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
        images.forEach { image ->
            val src = displayText(image["src"])
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val shape = RoundedCornerShape(14.dp)
                when (val loaded = rememberRemoteImage(src, 640)) {
                    is RemoteState.Loaded -> Image(
                        loaded.image, contentDescription = displayText(image["alt"]), contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).clickable { scope?.host?.openUrl(src) },
                    )
                    else -> Box(Modifier.fillMaxWidth().aspectRatio(1f).pane(shape, Palette.Surface))
                }
                displayText(image["details"] ?: image["alt"]).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---- Data display -----------------------------------------------------------------------------------

private const val TABLE_PAGE = 10

@Composable
private fun TableView(n: UiNode, modifier: Modifier) {
    val columns = n.nodes("columns")
    if (columns.isEmpty()) return
    val headers = columns.map { it.string("label").orEmpty() }
    val numeric = columns.map { it.string("type") == "number" }
    val legacyRows = n.list("rows").mapNotNull { unbind(it) as? List<*> }
    val rows: List<List<Any?>> = if (legacyRows.isNotEmpty()) {
        legacyRows.map { it.toList() }
    } else {
        val data = columns.map { (it["data"] as? List<*>).orEmpty() }
        val count = data.maxOfOrNull { it.size } ?: 0
        (0 until count).map { r -> data.map { it.getOrNull(r) } }
    }
    var expanded by rememberSaveable(rows.size) { mutableStateOf(false) }
    val shown = if (expanded || rows.size <= TABLE_PAGE + 2) rows else rows.take(TABLE_PAGE)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DataTable(headers, shown, numeric)
        if (shown.size < rows.size) {
            Text(
                pluralStringResource(R.plurals.openui_show_more_rows, rows.size - shown.size, rows.size - shown.size),
                style = MaterialTheme.typography.labelLarge,
                color = LocalAccent.current.soft,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = true }.padding(6.dp),
            )
        }
    }
}

/** A table with cells that can be components (a Tag in a status column), in the markdown table's frame. */
@Composable
internal fun DataTable(headers: List<String>, rows: List<List<Any?>>, numeric: List<Boolean> = emptyList()) {
    val cellStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp)
    TableGrid(rowCount = rows.size + 1, columnCount = headers.size) { r, c ->
        val align = if (numeric.getOrElse(c) { false }) TextAlign.End else TextAlign.Start
        if (r == 0) {
            Text(
                headers[c], style = cellStyle.copy(fontWeight = FontWeight.SemiBold, textAlign = align), color = Palette.TextPrimary,
                modifier = Modifier.background(Palette.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        } else {
            val cell = unbind(rows[r - 1].getOrNull(c))
            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = if (align == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart) {
                if (cell is UiNode) Render(cell) else Text(OpenUiText.cellText(cell), style = cellStyle.copy(textAlign = align), color = Palette.TextPrimary)
            }
        }
    }
}

@Composable
private fun StepsView(n: UiNode, modifier: Modifier) {
    val items = n.nodes("items")
    if (items.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(28.dp).pane(CircleShape, LocalAccent.current.bubble, outline = Palette.Hairline), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary)
                    }
                    if (index < items.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(Palette.Hairline))
                }
                Column(Modifier.weight(1f).padding(top = 3.dp, bottom = if (index < items.lastIndex) 18.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.string("title")?.let { Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = Palette.TextPrimary) }
                    item.string("details")?.let { Markdown(it, style = MaterialTheme.typography.bodyMedium.copy(color = Palette.TextSecondary)) }
                }
            }
        }
    }
}

@Composable
private fun TabsView(n: UiNode, modifier: Modifier) {
    val items = n.nodes("items")
    if (items.isEmpty()) return
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val current = items.getOrElse(selected) { items.first() }
    val accent = LocalAccent.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEachIndexed { index, item ->
                val active = item === current
                Text(
                    item.string("trigger") ?: item.string("value") ?: "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) accent.on else Palette.TextPrimary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .then(if (active) Modifier.pane(CircleShape, accent.color) else Modifier.outlined(CircleShape))
                        .clickable { selected = index }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
        Children(current.list("content"))
    }
}

/** Accordion and SectionBlock: titled sections that fold open. */
@Composable
private fun FoldView(items: List<UiNode>, openByDefault: Boolean, foldable: Boolean, modifier: Modifier) {
    if (items.isEmpty()) return
    Column(modifier.fillMaxWidth().pane(RoundedCornerShape(18.dp), Color.Transparent, outline = Palette.Hairline)) {
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = Palette.Hairline)
            var open by rememberSaveable(item.string("value") ?: "$index") { mutableStateOf(openByDefault || !foldable) }
            Column(Modifier.fillMaxWidth().animateContentSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (foldable) Modifier.clickable { open = !open } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.string("trigger") ?: item.string("value").orEmpty(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = Palette.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (foldable) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(22.dp).rotate(if (open) 180f else 0f))
                    }
                }
                AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Children(item.list("content"), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun CarouselView(n: UiNode, modifier: Modifier) {
    val slides = n.list("children").mapNotNull { unbind(it) as? List<*> }.filter { it.isNotEmpty() }
    if (slides.isEmpty()) return
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(slides) { _, slide ->
            Column(
                Modifier.width(260.dp).pane(RoundedCornerShape(18.dp), Palette.Surface, outline = Palette.Hairline).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { slide.forEach { Render(it) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagBlockView(n: UiNode, modifier: Modifier) {
    val tags = n.list("tags").map { unbind(it) }.filter { it != null }
    if (tags.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag ->
            if (tag is UiNode) Render(tag) else TagView(OpenUiText.cellText(tag), null)
        }
    }
}

@Composable
internal fun TagView(text: String, variant: String?) {
    if (text.isBlank()) return
    val tint = when (variant) {
        "info" -> Palette.SparkBlue
        "success" -> Palette.Success
        "warning" -> Palette.SparkAmber
        "danger" -> Palette.Danger
        else -> null
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.TextPrimary,
        maxLines = 1,
        modifier = Modifier
            .pane(CircleShape, tint?.copy(alpha = 0.18f) ?: Palette.Surface, outline = tint?.copy(alpha = 0.4f) ?: Palette.Hairline)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun EntityListView(n: UiNode, modifier: Modifier) {
    val rows = n.list("rows").mapNotNull { unbind(it) as? Map<*, *> }
    val header = n.map("header")
    val footer = n.map("footer")
    if (rows.isEmpty() && header == null) return
    Column(modifier.fillMaxWidth()) {
        header?.let { EntityRow(it, strong = true) }
        rows.forEachIndexed { index, row ->
            if (index > 0 || header != null) HorizontalDivider(color = Palette.Hairline)
            EntityRow(row, strong = false)
        }
        footer?.let {
            HorizontalDivider(color = Palette.Outline)
            EntityRow(it, strong = true)
        }
    }
}

@Composable
private fun EntityRow(row: Map<*, *>, strong: Boolean) {
    val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(OpenUiText.cellText(row["left"]), style = style, color = if (strong) Palette.TextPrimary else Palette.TextSecondary, modifier = Modifier.weight(1f))
        Text(OpenUiText.cellText(row["right"]), style = style.copy(textAlign = TextAlign.End), color = Palette.TextPrimary)
    }
}

@Composable
private fun ListBlockView(n: UiNode, modifier: Modifier) {
    val items = n.nodes("items")
    if (items.isEmpty()) return
    val scope = LocalOpenUiScope.current
    val withImages = n.string("variant") == "image"
    val small = n.string("size") == "small"
    Column(modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = Palette.Hairline)
            val title = item.string("title").orEmpty()
            val action = item.props["action"]
            val tappable = unbind(action) != null
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (tappable) Modifier.clickable { scope?.run(action, title, null) } else Modifier)
                    .padding(vertical = if (small) 8.dp else 12.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val image = item.map("image")?.let { displayText(it["src"]) }?.takeIf { it.isNotBlank() }
                if (withImages && image != null) {
                    when (val loaded = rememberRemoteImage(image, 256)) {
                        is RemoteState.Loaded -> Image(loaded.image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                        else -> Box(Modifier.size(44.dp).pane(RoundedCornerShape(10.dp), Palette.Surface))
                    }
                } else {
                    Box(Modifier.size(26.dp).pane(CircleShape, Palette.Surface, outline = Palette.Hairline), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = Palette.TextPrimary)
                    item.string("subtitle")?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary) }
                }
                if (tappable) {
                    item.string("actionLabel")?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = LocalAccent.current.soft) }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun FollowUpView(n: UiNode, modifier: Modifier) {
    val items = n.nodes("items").mapNotNull { it.string("text") }
    if (items.isEmpty()) return
    val scope = LocalOpenUiScope.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { text ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .outlined(RoundedCornerShape(18.dp))
                    .clickable { scope?.run(null, text, null) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(16.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary)
            }
        }
    }
}

// ---- Buttons --------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ButtonsView(n: UiNode, modifier: Modifier = Modifier) {
    val buttons = n.nodes("buttons")
    if (buttons.isEmpty()) return
    if (n.string("direction") == "column") {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons.forEach { ButtonView(it, Modifier.fillMaxWidth()) } }
    } else {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons.forEach { ButtonView(it) }
        }
    }
}

@Composable
internal fun ButtonView(n: UiNode, modifier: Modifier = Modifier) {
    val scope = LocalOpenUiScope.current
    val form = LocalFormScope.current
    val label = n.string("label") ?: return
    val accent = LocalAccent.current
    val destructive = n.string("type") == "destructive"
    val variant = n.string("variant") ?: "primary"
    val shape = CircleShape
    val (fill, ink) = when {
        variant == "primary" && destructive -> Palette.Danger to Color(0xFF3A0B10)
        variant == "primary" -> accent.color to accent.on
        destructive -> Color.Transparent to Palette.Danger
        else -> Color.Transparent to Palette.TextPrimary
    }
    Box(
        modifier
            .clip(shape)
            .then(
                when (variant) {
                    "primary" -> Modifier.pane(shape, fill)
                    "tertiary" -> Modifier
                    else -> Modifier.outlined(shape)
                },
            )
            .clickable { scope?.run(n.props["action"], label, form) }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

// ---- Fallbacks ----------------------------------------------------------------------------------

/** Components aight doesn't draw show what they hold: their text and their child components. */
@Composable
private fun Fallback(n: UiNode, modifier: Modifier) {
    val parts = n.args.map { unbind(it) }.filter { it is UiNode || it is List<*> || (it is String && it.isNotBlank()) }
    if (parts.isEmpty()) return
    val boxed = n.type.endsWith("Item") || n.type.endsWith("CardItem")
    Column(
        modifier.fillMaxWidth().then(if (boxed) Modifier.pane(RoundedCornerShape(16.dp), Palette.Surface, outline = Palette.Hairline).padding(12.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        parts.forEach { part ->
            when (part) {
                is String -> if (!part.looksLikeId()) Text(part, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary)
                else -> Render(part)
            }
        }
    }
}

/** Ids such as "revenue" or "art-museums" in a card item's first slot are for the program, not the reader. */
private fun String.looksLikeId(): Boolean = length <= 40 && !contains(' ') && all { it.isLowerCase() || it.isDigit() || it == '-' || it == '_' }

/** Stands in for a part that is still streaming in. */
@Composable
internal fun Skeleton(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(22.dp).pane(RoundedCornerShape(8.dp), Palette.Surface))
}
