package nl.bartvandermeeren.dudan.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.data.*
import nl.bartvandermeeren.dudan.ui.components.pane
import nl.bartvandermeeren.dudan.ui.theme.Palette

data class ConversationDesktops(val capability: DesktopCapabilities? = null, val sessions: List<DesktopSession> = emptyList(), val client: HermesApi? = null)

@Composable
fun rememberConversationDesktops(api: HermesApi, sessionId: String): ConversationDesktops {
    var value by remember(api, sessionId) { mutableStateOf(ConversationDesktops()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api, sessionId, lifecycle) {
        val client = api.desktopClient()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val capability = client.capabilities().desktop
                    value = ConversationDesktops(capability, if (capability != null) client.desktops(sessionId) else emptyList(), client)
                    if (capability == null) return@repeatOnLifecycle
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Cards retain their last state and show stale frames. */ }
                delay(5000)
            }
        }
    }
    return value
}

@Composable
fun AgentScreenCard(api: HermesApi, session: DesktopSession, capability: DesktopCapabilities, visible: Boolean, agentName: String) {
    val controller = remember(api, session.id) { DesktopController(api, session) }
    val view by controller.state.collectAsState()
    var expanded by rememberSaveable(session.id) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller, visible, expanded, lifecycle) {
        if (visible || expanded) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { controller.watch(expanded) }
    }
    val aspect = DesktopCoordinates.aspect(view.session.width, view.session.height)
    // Small by default: one row with a thumbnail and the state. Tapping it shows the preview in place; Open goes full screen.
    var inline by rememberSaveable("inline:${session.id}") { mutableStateOf(false) }
    val stopped = view.session.state == DesktopState.Stopped
    Column(Modifier.padding(horizontal = 16.dp).widthIn(max = CardMaxWidth).fillMaxWidth().pane(RoundedCornerShape(18.dp))
        .clickable(enabled = !stopped, onClickLabel = stringResource(if (inline) R.string.desktop_collapse else R.string.desktop_show)) { inline = !inline }
        .animateContentSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!inline && view.bitmap != null) DesktopSurface(view, false, Modifier.width(64.dp).aspectRatio(aspect)) {}
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.desktop_title), color = Palette.TextPrimary, style = MaterialTheme.typography.titleSmall)
                Text(if (stopped) stoppedLine(view) else stringResource(statusLabel(view)), color = Palette.TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { expanded = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text(stringResource(R.string.desktop_open)) }
        }
        if (inline && !stopped) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                val tallest = if (LocalConfiguration.current.screenHeightDp < 500) 120.dp else 360.dp
                DesktopSurface(view, false, Modifier.width(minOf(maxWidth, tallest * aspect)).aspectRatio(aspect)) {}
            }
        }
        // Folded or not, a retained frame says how old it is, and an uncertain input says so.
        if (!stopped) DesktopNotes(view)
    }
    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().systemBarsPadding(), color = Palette.MenuSolid) {
                BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
                  val controlsHeight = (maxHeight * 0.45f).coerceAtMost(260.dp)
                  Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.desktop_title), Modifier.weight(1f), color = Palette.TextPrimary, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { expanded = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.desktop_collapse)) }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        DesktopSurface(view, view.controlling && !view.stale,
                            Modifier.aspectRatio(aspect)) { input -> scope.launch { controller.send(input) } }
                    }
                    Column(Modifier.heightIn(max = controlsHeight).verticalScroll(rememberScrollState())) {
                        DesktopStatus(view)
                        if (view.error) Text(stringResource(R.string.desktop_error), color = MaterialTheme.colorScheme.error)
                        if (capability.control && view.session.state != DesktopState.Stopped) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!view.controlling && view.session.state != DesktopState.HumanControlled) {
                                    Button(onClick = { scope.launch { controller.takeControl() } }, enabled = !view.stale && !view.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.desktop_take_control)) }
                                }
                                if (view.controlling || (view.session.agentPaused && view.session.state != DesktopState.HumanControlled)) {
                                    TextButton(onClick = { scope.launch { controller.handBack() } }, enabled = !view.stale && !view.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.desktop_hand_back)) }
                                }
                            }
                        }
                        if (view.controlling) DesktopKeyboard(enabled = !view.stale && !view.busy) { input -> scope.launch { controller.send(input) } }
                    }
                  }
                }
            }
        }
    }
}

private val CardMaxWidth = 600.dp

@Composable
private fun stoppedLine(view: DesktopViewState): String {
    val at = view.receivedAt ?: view.session.lastFrameAt ?: return stringResource(R.string.desktop_stopped)
    return stringResource(R.string.desktop_stopped_at, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at)))
}

private fun statusLabel(view: DesktopViewState): Int = when {
        view.session.state == DesktopState.Stopped -> R.string.desktop_stopped
        view.stale && view.bitmap != null -> R.string.desktop_stale
        view.error || view.session.state == DesktopState.Unavailable -> R.string.desktop_unavailable
        view.session.state == DesktopState.Error -> R.string.desktop_error
        view.session.state == DesktopState.Reconnecting -> R.string.desktop_stale
        view.session.state == DesktopState.Connecting -> R.string.desktop_connecting
        view.controlling -> R.string.desktop_human
        view.session.state == DesktopState.HumanControlled -> R.string.desktop_other_viewer
        view.session.agentPaused -> R.string.desktop_paused
        view.bitmap == null -> R.string.desktop_connecting
        else -> R.string.desktop_live
    }

@Composable
private fun DesktopStatus(view: DesktopViewState) {
    Text(stringResource(statusLabel(view)), Modifier.padding(vertical = 8.dp), color = Palette.TextSecondary, style = MaterialTheme.typography.labelMedium)
    DesktopNotes(view)
}

/** What the state line leaves out: an uncertain input, and when the last frame came. */
@Composable
private fun DesktopNotes(view: DesktopViewState) {
    if (view.session.problem == "input_uncertain") Text(stringResource(R.string.desktop_uncertain), color = Palette.TextSecondary, style = MaterialTheme.typography.bodySmall)
    if (view.stale && view.receivedAt != null) Text(stringResource(R.string.desktop_last_frame, java.text.DateFormat.getTimeInstance().format(java.util.Date(view.receivedAt))), color = Palette.TextSecondary, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun DesktopSurface(view: DesktopViewState, inputEnabled: Boolean, modifier: Modifier, send: (JsonObject) -> Unit) {
    val width = view.session.width
    val height = view.session.height
    val focus = remember { FocusRequester() }
    LaunchedEffect(inputEnabled) { if (inputEnabled) focus.requestFocus() }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black)
        .focusRequester(focus)
        .onKeyEvent { event ->
            val key = event.nativeKeyEvent
            if (!inputEnabled || key.action != android.view.KeyEvent.ACTION_DOWN) false
            else DesktopKeys.input(key.keyCode, key.unicodeChar, key.isCtrlPressed, key.isAltPressed, key.isShiftPressed, key.isMetaPressed)?.let { send(it); true } ?: false
        }
        .focusable(inputEnabled)
        .then(if (inputEnabled) Modifier.pointerInput(width, height) {
            awaitEachGesture {
                val down = awaitFirstDown()
                focus.requestFocus()
                val start = down.position
                var end = start
                var cancelled = false
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.id != down.id && it.pressed }) cancelled = true
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (pointer == null || pointer.isConsumed) cancelled = true
                    if (pointer != null) { end = pointer.position; pointer.consume() }
                } while (event.changes.any { it.pressed })
                val from = DesktopCoordinates.map(start.x, start.y, size.width.toFloat(), size.height.toFloat(), width, height)
                val to = DesktopCoordinates.map(end.x, end.y, size.width.toFloat(), size.height.toFloat(), width, height)
                if (!cancelled && from != null && to != null) send(buildJsonObject {
                    put("kind", if ((end - start).getDistance() < viewConfiguration.touchSlop) "tap" else "drag")
                    put("x", from.first); put("y", from.second)
                    put("toX", to.first); put("toY", to.second)
                })
            }
        } else Modifier), contentAlignment = Alignment.Center) {
        val bitmap = view.bitmap
        if (bitmap != null) Image(bitmap.asImageBitmap(), stringResource(R.string.desktop_image), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (view.session.state != DesktopState.Stopped) CircularProgressIndicator(Modifier.size(32.dp))
    }
}

@Composable
private fun DesktopKeyboard(enabled: Boolean, send: (JsonObject) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it.take(4096) }, Modifier.weight(1f), enabled = enabled,
            label = { Text(stringResource(R.string.desktop_type)) }, singleLine = true)
        TextButton(enabled = enabled && text.isNotEmpty(), onClick = { send(buildJsonObject { put("kind", "text"); put("text", text) }); text = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.desktop_send)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("Enter" to "enter", "Tab" to "tab", "Esc" to "escape", stringResource(R.string.desktop_backspace) to "backspace").forEach { (label, key) ->
            TextButton(enabled = enabled, onClick = { send(buildJsonObject { put("kind", "key"); put("keys", JsonArray(listOf(JsonPrimitive(key)))) }) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text(label) }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(enabled = enabled, onClick = { send(buildJsonObject { put("kind", "scroll"); put("dx", 0); put("dy", -3) }) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.desktop_scroll_up)) }
        TextButton(enabled = enabled, onClick = { send(buildJsonObject { put("kind", "scroll"); put("dx", 0); put("dy", 3) }) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.desktop_scroll_down)) }
    }
}
