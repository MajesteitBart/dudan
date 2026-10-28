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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlin.math.sign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.bartvandermeeren.dudan.desktop.res.*
import nl.bartvandermeeren.dudan.data.*
import nl.bartvandermeeren.dudan.ui.components.LocalWindowShown
import nl.bartvandermeeren.dudan.ui.components.pane
import org.jetbrains.compose.resources.StringResource
import nl.bartvandermeeren.dudan.ui.theme.Palette

data class ConversationDesktops(val capability: DesktopCapabilities? = null, val sessions: List<DesktopSession> = emptyList(), val client: HermesApi? = null)

@Composable
fun rememberConversationDesktops(api: HermesApi, sessionId: String): ConversationDesktops {
    var value by remember(api, sessionId) { mutableStateOf(ConversationDesktops()) }
    // One client while the list lives: the Agent screens are remembered by it, and an open window must survive
    // the main window hiding and coming back. A server change rebuilds the list, and with it this.
    var fixed by remember(api) { mutableStateOf<HermesApi?>(null) }
    // Only while the window is on screen, as the phone checks only while the app is.
    val shown = LocalWindowShown.current
    LaunchedEffect(api, sessionId, shown) {
        if (!shown) return@LaunchedEffect
        val client = fixed ?: try { api.desktopClient().also { fixed = it } } catch (e: CancellationException) { throw e } catch (_: Exception) { return@LaunchedEffect }
        while (true) {
            try {
                val capability = client.capabilities().desktop
                value = ConversationDesktops(capability, if (capability != null) client.desktops(sessionId) else emptyList(), client)
                if (capability == null) return@LaunchedEffect
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Cards retain their last state and show stale frames. */ }
            delay(5000)
        }
    }
    return value
}

/** How a desktop is watched: fast in its open Agent screen window, slowly on its card. */
enum class DesktopWatch { Window, Card }

/**
 * The watch for one desktop. An open Agent screen window watches whatever the main window does, until it is
 * minimized: a viewer out of sight releases control (docs/agent-screen.md). Otherwise the card watches while
 * it is in view and the main window is on screen.
 */
internal fun desktopWatch(windowOpen: Boolean, windowMinimized: Boolean, cardVisible: Boolean, mainShown: Boolean): DesktopWatch? = when {
    windowOpen && !windowMinimized -> DesktopWatch.Window
    cardVisible && mainShown -> DesktopWatch.Card
    else -> null
}

/**
 * The Agent screens of one chat: a controller per desktop and the one whose window is open. The cards are
 * list items, which scrolling throws away, so this lives beside the message list, and so does the window.
 */
@Stable
class AgentScreens(private val client: HermesApi) {
    private val controllers = mutableMapOf<String, DesktopController>()
    var open by mutableStateOf<String?>(null)

    fun controller(session: DesktopSession): DesktopController = controllers.getOrPut(session.id) { DesktopController(client, session) }
}

/**
 * Watches each working desktop as [desktopWatch] says, and shows the Agent screen window of the open one, in a
 * window of its own, as big as the user makes it, beside the chat. [cardVisible] says whether a desktop's card
 * is in view in the message list.
 */
@Composable
fun AgentScreenWindows(screens: AgentScreens, working: List<DesktopSession>, capability: DesktopCapabilities, agentName: String, cardVisible: (String) -> Boolean) {
    val shown = LocalWindowShown.current
    working.forEach { session ->
        key(session.id) {
            val controller = screens.controller(session)
            val open = screens.open == session.id
            // A fresh window each time it opens, so a window closed while minimized doesn't open minimized.
            val windowState = key(open) { rememberWindowState(size = DpSize(1280.dp, 860.dp), position = WindowPosition(Alignment.Center)) }
            val watch = desktopWatch(open, windowState.isMinimized, cardVisible(session.id), shown)
            LaunchedEffect(controller, watch) { if (watch != null) controller.watch(expanded = watch == DesktopWatch.Window) }
            if (open) {
                val view by controller.state.collectAsState()
                Window(
                    onCloseRequest = { screens.open = null },
                    title = stringResource(Res.string.desktop_title) + " · " + agentName,
                    state = windowState,
                ) {
                    AgentScreenViewer(controller, view, capability, onCollapse = { screens.open = null })
                }
            }
        }
    }
}

/** A desktop in the chat: a thumbnail and its state, the preview in place on a click, and Open for its window. */
@Composable
fun AgentScreenCard(controller: DesktopController, onOpen: () -> Unit) {
    val view by controller.state.collectAsState()
    val session = view.session
    val aspect = DesktopCoordinates.aspect(view.session.width, view.session.height)
    // Small by default: one row with a thumbnail and the state. Clicking it shows the preview in place; Open gives it a window.
    var inline by rememberSaveable("inline:${session.id}") { mutableStateOf(false) }
    val stopped = view.session.state == DesktopState.Stopped
    Column(Modifier.padding(horizontal = 16.dp).widthIn(max = CardMaxWidth).fillMaxWidth().pane(RoundedCornerShape(18.dp))
        .clickable(enabled = !stopped, onClickLabel = stringResource(if (inline) Res.string.desktop_collapse else Res.string.desktop_show)) { inline = !inline }
        .animateContentSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!inline && view.bitmap != null) DesktopSurface(view, false, Modifier.width(64.dp).aspectRatio(aspect)) {}
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.desktop_title), color = Palette.TextPrimary, style = MaterialTheme.typography.titleSmall)
                Text(if (stopped) stoppedLine(view) else stringResource(statusLabel(view)), color = Palette.TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onOpen, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text(stringResource(Res.string.desktop_open)) }
        }
        if (inline && !stopped) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                val tallest = 360.dp
                DesktopSurface(view, false, Modifier.width(minOf(maxWidth, tallest * aspect)).aspectRatio(aspect)) {}
            }
        }
        // Folded or not, a retained frame says how old it is, and an uncertain input says so.
        if (!stopped) DesktopNotes(view)
    }
}

/** What the Agent screen window shows: the desktop, its state, and the controls to take and return it. */
@Composable
fun AgentScreenViewer(controller: DesktopController, view: DesktopViewState, capability: DesktopCapabilities, onCollapse: () -> Unit) {
    val scope = rememberCoroutineScope()
    val aspect = DesktopCoordinates.aspect(view.session.width, view.session.height)
    Surface(Modifier.fillMaxSize(), color = Palette.MenuSolid) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
          val controlsHeight = (maxHeight * 0.35f).coerceAtMost(220.dp)
          Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.desktop_title), Modifier.weight(1f), color = Palette.TextPrimary, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onCollapse, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.desktop_collapse)) }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                DesktopSurface(view, view.controlling && !view.stale,
                    Modifier.aspectRatio(aspect)) { input -> scope.launch { controller.send(input) } }
            }
            Column(Modifier.heightIn(max = controlsHeight).verticalScroll(rememberScrollState())) {
                DesktopStatus(view)
                if (view.error) Text(stringResource(Res.string.desktop_error), color = MaterialTheme.colorScheme.error)
                if (capability.control && view.session.state != DesktopState.Stopped) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!view.controlling && view.session.state != DesktopState.HumanControlled) {
                            Button(onClick = { scope.launch { controller.takeControl() } }, enabled = !view.stale && !view.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.desktop_take_control)) }
                        }
                        if (view.controlling || (view.session.agentPaused && view.session.state != DesktopState.HumanControlled)) {
                            TextButton(onClick = { scope.launch { controller.handBack() } }, enabled = !view.stale && !view.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.desktop_hand_back)) }
                        }
                    }
                }
                if (view.controlling) DesktopKeyboard(enabled = !view.stale && !view.busy) { input -> scope.launch { controller.send(input) } }
            }
          }
        }
    }
}

private val CardMaxWidth = 600.dp

@Composable
private fun stoppedLine(view: DesktopViewState): String {
    val at = view.receivedAt ?: view.session.lastFrameAt ?: return stringResource(Res.string.desktop_stopped)
    return stringResource(Res.string.desktop_stopped_at, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at)))
}

private fun statusLabel(view: DesktopViewState): StringResource = when {
        view.session.state == DesktopState.Stopped -> Res.string.desktop_stopped
        view.stale && view.bitmap != null -> Res.string.desktop_stale
        view.error || view.session.state == DesktopState.Unavailable -> Res.string.desktop_unavailable
        view.session.state == DesktopState.Error -> Res.string.desktop_error
        view.session.state == DesktopState.Reconnecting -> Res.string.desktop_stale
        view.session.state == DesktopState.Connecting -> Res.string.desktop_connecting
        view.controlling -> Res.string.desktop_human
        view.session.state == DesktopState.HumanControlled -> Res.string.desktop_other_viewer
        view.session.agentPaused -> Res.string.desktop_paused
        view.bitmap == null -> Res.string.desktop_connecting
        else -> Res.string.desktop_live
    }

@Composable
private fun DesktopStatus(view: DesktopViewState) {
    Text(stringResource(statusLabel(view)), Modifier.padding(vertical = 8.dp), color = Palette.TextSecondary, style = MaterialTheme.typography.labelMedium)
    DesktopNotes(view)
}

/** What the state line leaves out: an uncertain input, and when the last frame came. */
@Composable
private fun DesktopNotes(view: DesktopViewState) {
    if (view.session.problem == "input_uncertain") Text(stringResource(Res.string.desktop_uncertain), color = Palette.TextSecondary, style = MaterialTheme.typography.bodySmall)
    if (view.stale && view.receivedAt != null) Text(stringResource(Res.string.desktop_last_frame, java.text.DateFormat.getTimeInstance().format(java.util.Date(view.receivedAt))), color = Palette.TextSecondary, style = MaterialTheme.typography.labelSmall)
}

/**
 * A surface's key tracker, which forgets the right Alt whenever a key's release may have gone elsewhere: input
 * turned on or off, or the window losing the focus to another app. Compose keeps a node's focus while its
 * window is in the background, so the surface's own focus (see DesktopSurface) doesn't say so.
 */
@Composable
internal fun rememberKeyTracker(inputEnabled: Boolean): DesktopKeys.Tracker {
    val keys = remember { DesktopKeys.Tracker() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(inputEnabled, windowFocused) { keys.reset() }
    return keys
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DesktopSurface(view: DesktopViewState, inputEnabled: Boolean, modifier: Modifier, send: (JsonObject) -> Unit) {
    val width = view.session.width
    val height = view.session.height
    val focus = remember { FocusRequester() }
    val keys = rememberKeyTracker(inputEnabled)
    LaunchedEffect(inputEnabled) { if (inputEnabled) focus.requestFocus() }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black)
        .focusRequester(focus)
        // A key released while the focus was elsewhere never reaches the tracker.
        .onFocusChanged { if (!it.isFocused) keys.reset() }
        .onKeyEvent { event ->
            val key = event.nativeKeyEvent as? java.awt.event.KeyEvent
            if (!inputEnabled || key == null) false
            else keys.input(key)?.let { send(it); true } ?: false
        }
        // The wheel scrolls the agent's screen, three lines a notch, as the Scroll buttons do.
        .then(if (inputEnabled) Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (dy != 0f) send(buildJsonObject { put("kind", "scroll"); put("dx", 0); put("dy", 3 * dy.sign.toInt()) })
        } else Modifier)
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
        if (bitmap != null) Image(bitmap, stringResource(Res.string.desktop_image), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (view.session.state != DesktopState.Stopped) CircularProgressIndicator(Modifier.size(32.dp))
    }
}

/**
 * The phone's keyboard row, cut to what a PC needs: its own keys and wheel already reach the desktop (see
 * DesktopSurface), so this says so and keeps a field for pasting longer text.
 */
@Composable
private fun DesktopKeyboard(enabled: Boolean, send: (JsonObject) -> Unit) {
    var text by remember { mutableStateOf("") }
    Text(stringResource(Res.string.desktop_agent_keys_hint), Modifier.padding(bottom = 8.dp), color = Palette.TextSecondary, style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it.take(4096) }, Modifier.weight(1f), enabled = enabled,
            label = { Text(stringResource(Res.string.desktop_type)) }, singleLine = true)
        TextButton(enabled = enabled && text.isNotEmpty(), onClick = { send(buildJsonObject { put("kind", "text"); put("text", text) }); text = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.desktop_send)) }
    }
}
