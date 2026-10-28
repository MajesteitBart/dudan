package nl.bartvandermeeren.dudan.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.dudan.chat.BackgroundStatus
import nl.bartvandermeeren.dudan.desktop.res.*
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.Screen
import nl.bartvandermeeren.dudan.ui.chat.ChatHostActions
import nl.bartvandermeeren.dudan.ui.DudanRoot
import nl.bartvandermeeren.dudan.ui.components.ImageCodec
import nl.bartvandermeeren.dudan.ui.components.LocalWindowShown
import nl.bartvandermeeren.dudan.ui.components.OverlayHost
import nl.bartvandermeeren.dudan.ui.settings.label
import nl.bartvandermeeren.dudan.ui.theme.Palette
import nl.bartvandermeeren.dudan.ui.theme.Sky
import nl.bartvandermeeren.dudan.voice.SpeechText
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun main() {
    var showFromOutside: () -> Unit = {}
    val instance = SingleInstance { SwingUtilities.invokeLater { showFromOutside() } }
    if (!instance.claim()) return

    // The Swing event thread, so the chat engine's state changes where Compose reads it.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = DesktopSettings()
    val vm = MainViewModel(store, appScope)
    val quick = QuickAsk(vm, appScope)

    application {
        var mainVisible by remember { mutableStateOf(true) }
        var mainWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        var mainFocused by remember { mutableStateOf(false) }
        var toldAboutTray by remember { mutableStateOf(false) }
        var notifiedSession by remember { mutableStateOf<String?>(null) }
        val overlay = remember { OverlayHost() }
        var trayMenuAt by remember { mutableStateOf<java.awt.Point?>(null) }

        fun showMain() {
            mainVisible = true
            (mainWindow as? java.awt.Frame)?.let { if (it.extendedState and java.awt.Frame.ICONIFIED != 0) it.extendedState = java.awt.Frame.NORMAL }
            mainWindow?.toFront()
            mainWindow?.requestFocus()
        }
        showFromOutside = ::showMain

        // A click on the icon or on a reply's notification: open dudan on the chat that answered.
        fun openFromTray() {
            notifiedSession?.let { vm.openSession(it) }
            notifiedSession = null
            showMain()
        }
        val icon = painterResource(Res.drawable.dudan_app_icon)
        val tray = remember { DesktopTray(DesktopTray.image(icon), "dudan", onOpen = ::openFromTray, onMenu = { trayMenuAt = it }) }
        DisposableEffect(tray) { onDispose { tray.remove() } }

        // The quick question's keys (Ctrl+Alt+Space unless changed), unless removed or another app holds them.
        // While Settings waits for new ones, the old ones are let go, so pressing them reaches the window.
        val prefs by store.prefs.collectAsState()
        val quickKeys = prefs.shortcut(ShortcutAction.QuickAsk)
        val recordingQuick = vm.recording == ShortcutAction.QuickAsk
        var shortcutTaken by remember { mutableStateOf(false) }
        DisposableEffect(quickKeys, recordingQuick) {
            val shortcut = quickKeys?.takeUnless { recordingQuick }?.let { GlobalShortcut(it.winModifiers, it.key.win, onPress = quick::open) }
            shortcutTaken = shortcut != null && !shortcut.start()
            onDispose { shortcut?.stop() }
        }

        // A reply that comes in while dudan isn't looking at that chat gets a notification; the tray icon opens it.
        LaunchedEffect(Unit) {
            vm.engine.completedTurns.filterNotNull().collect { turn ->
                val onScreen = (mainFocused && vm.screen == Screen.Chat && vm.currentId.value == turn.sessionId) ||
                    (quick.visible && quick.sessionId == turn.sessionId)
                if (onScreen) return@collect
                notifiedSession = turn.sessionId
                val body = SpeechText.fromMarkdown(turn.text).replace(Regex("\\s+"), " ").trim().ifBlank { turn.files.joinToString(", ") }
                tray.notify(getString(Res.string.desktop_reply_ready, vm.settings.value.assistantName), body.take(240))
            }
        }
        // So does the result of background work, which arrives without a turn of its own. The engine reports
        // each result once; an early failure notice doesn't count, since the rest of its batch is still out.
        LaunchedEffect(Unit) {
            vm.engine.backgroundResults.collect { arrival ->
                val result = arrival.result
                if (result.interim) return@collect
                val onScreen = (mainFocused && vm.screen == Screen.Chat && vm.currentId.value == arrival.sessionId) ||
                    (quick.visible && quick.sessionId == arrival.sessionId)
                if (onScreen) return@collect
                notifiedSession = arrival.sessionId
                val taskCount = result.taskCount
                val title = when {
                    result.status == BackgroundStatus.Completed -> getString(Res.string.background_done)
                    result.status == BackgroundStatus.PartlyFailed && taskCount != null ->
                        getString(Res.string.background_partly_failed, result.failedCount, taskCount)
                    else -> getString(Res.string.background_failed)
                }
                val body = SpeechText.fromMarkdown(result.summary).replace(Regex("\\s+"), " ").trim()
                tray.notify(title, body.take(240))
            }
        }

        trayMenuAt?.let { at ->
            TrayMenuWindow(
                at, vm,
                onOpen = ::showMain,
                onQuickAsk = quick::open,
                onQuit = {
                    tray.remove()
                    instance.release()
                    exitApplication()
                },
                onDismiss = { trayMenuAt = null },
            )
        }

        val mainState = rememberWindowState(size = DpSize(1180.dp, 820.dp), position = WindowPosition(Alignment.Center))
        val trayRunning = quickKeys?.let { stringResource(Res.string.desktop_tray_running, it.label()) } ?: stringResource(Res.string.desktop_tray_running_plain)
        val pickTitle = stringResource(Res.string.desktop_pick_images)
        Window(
            onCloseRequest = {
                // Closing keeps dudan in the tray, where the shortcut and replies still reach it.
                mainVisible = false
                if (!toldAboutTray) {
                    toldAboutTray = true
                    tray.notify("dudan", trayRunning)
                }
            },
            visible = mainVisible,
            state = mainState,
            title = "dudan",
            icon = icon,
            onPreviewKeyEvent = { event -> mainWindowKeys(event, vm, overlay) },
        ) {
            DisposableEffect(window) {
                mainWindow = window
                window.minimumSize = Dimension(720, 540)
                val focus = object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent) {
                        mainFocused = true
                        vm.onWindowFocused()
                    }

                    override fun windowLostFocus(e: WindowEvent) {
                        mainFocused = false
                        vm.onWindowBlurred()
                    }
                }
                window.addWindowFocusListener(focus)
                // Pictures dragged from Explorer go into the prompt bar.
                window.dropTarget = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
                    override fun drop(event: DropTargetDropEvent) {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        val files = runCatching { event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*> }.getOrDefault(emptyList<File>())
                        vm.addImageFiles(files.filterIsInstance<File>().filter(DesktopFiles::isImage))
                        event.dropComplete(true)
                    }
                }, true)
                onDispose { window.removeWindowFocusListener(focus) }
            }
            // The title bar in the sky's top color, also after the sky changes in Settings.
            val sky = Sky.from(vm.settings.collectAsState().value.sky)
            LaunchedEffect(window, sky, mainVisible) { if (mainVisible) WindowChrome.apply(window, sky.top, Palette.TextPrimary) }
            val actions = remember(window) {
                ChatHostActions(
                    onPickImages = { vm.addImageFiles(DesktopFiles.pickImages(window, pickTitle)) },
                    onScreenshot = {
                        vm.attachLater {
                            // Out of the way first, so the picture shows what's behind dudan.
                            mainVisible = false
                            delay(350)
                            val shot = ScreenCapture.grab()
                            showMain()
                            shot?.let { withContext(Dispatchers.Default) { ScreenCapture.prepare(it) } }
                        }
                    },
                )
            }
            CompositionLocalProvider(LocalWindowShown provides (mainVisible && !mainState.isMinimized)) {
                DudanRoot(vm, overlay, actions, shortcutTaken)
            }
        }

        if (quick.visible) QuickAskWindow(quick, vm, onOpenInApp = { quick.openInApp(::showMain) })
    }
}

/**
 * The main window's keys. While Settings waits for a shortcut, every key goes there. Esc closes the top
 * layer, then Settings, then clears the chat search, then stops reading aloud. Ctrl+V with a picture on
 * the clipboard attaches it. The rest are the shortcuts from Settings.
 */
internal fun mainWindowKeys(event: KeyEvent, vm: MainViewModel, overlay: OverlayHost): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (vm.recording != null) {
        vm.record(event.key.nativeKeyCode, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)
        return true
    }
    val shortcut = vm.shortcutFor(event.key.nativeKeyCode, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)
    return when {
        event.key == Key.Escape -> overlay.dismissTop() ||
            (vm.screen == Screen.Settings).also { if (it) vm.screen = Screen.Chat } ||
            vm.searchQuery.value.isNotEmpty().also { if (it) vm.searchQuery.value = "" } ||
            vm.stopSpeaking()
        shortcut != null -> {
            vm.perform(shortcut)
            true
        }
        event.isCtrlPressed && event.key == Key.V && vm.screen == Screen.Chat -> {
            val files = DesktopFiles.clipboardFiles()
            if (files.isNotEmpty()) {
                vm.addImageFiles(files)
                return true
            }
            // Text pastes go on to the prompt bar; only a picture is taken here.
            val png = DesktopFiles.clipboardImage() ?: return false
            vm.attachLater { withContext(Dispatchers.Default) { ImageCodec.prepare(png) } }
            true
        }
        else -> false
    }
}

/**
 * The quick question's window: no frame, always on top, at the top of the screen the pointer is on.
 * It's transparent around the card, and it goes away with Esc or when another window takes the focus.
 */
@Composable
private fun QuickAskWindow(quick: QuickAsk, vm: MainViewModel, onOpenInApp: () -> Unit) {
    val overlay = remember { OverlayHost() }
    val width = 720.dp
    val position = remember {
        val screen = (MouseInfo.getPointerInfo()?.device ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice)
            .defaultConfiguration.bounds
        WindowPosition((screen.x + (screen.width - width.value) / 2).dp, (screen.y + screen.height * 0.16f).dp)
    }
    Window(
        onCloseRequest = quick::close,
        state = rememberWindowState(size = DpSize(width, 640.dp), position = position),
        title = "dudan",
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        icon = painterResource(Res.drawable.dudan_app_icon),
        onPreviewKeyEvent = { event -> quickWindowKeys(event, quick, vm, overlay) },
    ) {
        DisposableEffect(window) {
            val focus = object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) {
                    // Clicking elsewhere puts it away, as tapping outside the phone's overlay does.
                    // A running answer goes on, and the tray says when it's in.
                    if (e.oppositeWindow == null || e.oppositeWindow?.owner != window) quick.close()
                }
            }
            window.addWindowFocusListener(focus)
            window.toFront()
            window.requestFocus()
            onDispose { window.removeWindowFocusListener(focus) }
        }
        QuickAskCard(quick, vm, overlay, onOpenInApp)
    }
}

/** Esc puts the quick question away; of the shortcuts, it takes New chat, reading aloud and the text size. */
private fun quickWindowKeys(event: KeyEvent, quick: QuickAsk, vm: MainViewModel, overlay: OverlayHost): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key == Key.Escape) {
        if (!overlay.dismissTop()) quick.close()
        return true
    }
    return when (vm.shortcutFor(event.key.nativeKeyCode, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)) {
        ShortcutAction.NewChat -> quick.newQuestion()
        ShortcutAction.ReadAloud -> vm.toggleSpeakLatest(quick.sessionId, quick = true)
        ShortcutAction.TextBigger -> vm.stepTextSize(1)
        ShortcutAction.TextSmaller -> vm.stepTextSize(-1)
        ShortcutAction.TextReset -> vm.resetTextSize()
        else -> return false
    }.let { true }
}
