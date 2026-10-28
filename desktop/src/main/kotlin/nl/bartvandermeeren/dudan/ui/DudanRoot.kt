package nl.bartvandermeeren.dudan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import nl.bartvandermeeren.dudan.ui.chat.ChatHostActions
import nl.bartvandermeeren.dudan.ui.chat.ChatPane
import nl.bartvandermeeren.dudan.ui.chat.Sidebar
import nl.bartvandermeeren.dudan.ui.components.GlassBackdrop
import nl.bartvandermeeren.dudan.ui.components.LocalHazeState
import nl.bartvandermeeren.dudan.ui.components.LocalOverlayHost
import nl.bartvandermeeren.dudan.ui.components.OverlayHost
import nl.bartvandermeeren.dudan.ui.settings.SettingsScreen
import nl.bartvandermeeren.dudan.ui.settings.SetupScreen
import nl.bartvandermeeren.dudan.ui.theme.Accent
import nl.bartvandermeeren.dudan.ui.theme.DudanTheme
import nl.bartvandermeeren.dudan.ui.theme.Palette
import nl.bartvandermeeren.dudan.ui.theme.Sky
import org.jetbrains.compose.resources.getString

/** The sky, the theme and the in-window layers every dudan window shares. */
@Composable
fun DudanSurface(vm: MainViewModel, overlay: OverlayHost, glow: Boolean, dim: Boolean, content: @Composable () -> Unit) {
    val settings by vm.settings.collectAsState()
    val prefs by vm.prefs.collectAsState()
    DudanTheme(settings.reduceTransparency, Accent.from(settings.accent), Sky.from(settings.sky), prefs.textScale) {
        val haze = rememberHazeState()
        Box(Modifier.fillMaxSize()) {
            GlassBackdrop(Modifier.hazeSource(haze), glow = glow, dim = dim)
            CompositionLocalProvider(LocalHazeState provides haze, LocalOverlayHost provides overlay) {
                content()
                overlay.Layers()
            }
        }
    }
}

/**
 * The main window, as the phone's wide layout: the chat list docked on the left, the chat or Settings
 * beside it, all on one sky. Before a server is set up it shows Setup.
 */
@Composable
fun DudanRoot(vm: MainViewModel, overlay: OverlayHost, actions: ChatHostActions, shortcutTaken: Boolean) {
    val settings by vm.settings.collectAsState()
    val conversation by vm.conversation.collectAsState()
    val emptyChat = vm.screen == Screen.Chat && conversation.showGreeting
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.notices.collect { notice ->
            val result = snackbar.showSnackbar(getString(notice.text, *notice.args.toTypedArray()), actionLabel = notice.action?.let { getString(it.label) })
            if (result == SnackbarResult.ActionPerformed) notice.action?.run?.invoke()
        }
    }

    DudanSurface(
        vm, overlay,
        glow = emptyChat || !settings.isConfigured,
        // Replies sit on the sky itself, so it deepens while a conversation is open.
        dim = settings.isConfigured && vm.screen == Screen.Chat && !emptyChat,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (!settings.isConfigured) {
                SetupScreen(vm, settings)
            } else {
                Row(Modifier.fillMaxSize()) {
                    AnimatedVisibility(vm.sidebarOpen, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                        Sidebar(vm, settings, onClose = { vm.sidebarOpen = false }, modifier = Modifier.width(320.dp))
                    }
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        when (vm.screen) {
                            Screen.Chat -> ChatPane(
                                vm, settings, actions,
                                onOpenSidebar = if (vm.sidebarOpen) null else { { vm.sidebarOpen = true; vm.refreshSessions() } },
                            )
                            Screen.Settings -> SettingsScreen(vm, settings, onBack = { vm.screen = Screen.Chat }, shortcutTaken = shortcutTaken)
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) { data ->
                Snackbar(
                    data,
                    actionColor = nl.bartvandermeeren.dudan.ui.theme.LocalAccent.current.soft,
                    shape = RoundedCornerShape(18.dp),
                    containerColor = Palette.MenuSolid,
                    contentColor = Palette.TextPrimary,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
            }
        }
    }
}
