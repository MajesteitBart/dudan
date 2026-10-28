package nl.bartvandermeeren.dudan.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.bartvandermeeren.dudan.ui.theme.Palette

/**
 * Sheets and dialogs as layers inside the window, over everything else. On the phone they are windows
 * of their own that Android blurs behind; here they stay in the app's own composition, so they share
 * its theme, and Esc or a click on the scrim closes the top one.
 */
class OverlayHost {
    private val layers = mutableStateListOf<Layer>()

    internal class Layer(val onDismiss: () -> Unit, val content: @Composable () -> Unit)

    internal fun add(layer: Layer) { layers += layer }
    internal fun remove(layer: Layer) { layers -= layer }

    /** Closes the top layer; false when there is none, so the key goes on to the window. */
    fun dismissTop(): Boolean {
        val top = layers.lastOrNull() ?: return false
        top.onDismiss()
        return true
    }

    /** Draws the open layers. Place it last in the window, above the app. */
    @Composable
    fun Layers() {
        layers.forEach { layer ->
            key(layer) {
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Palette.BackdropEdge.copy(alpha = 0.55f))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = layer.onDismiss),
                    )
                    AnimatedVisibility(
                        appear,
                        enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.96f),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    ) {
                        layer.content()
                    }
                }
            }
        }
    }
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/**
 * Whether the window this is drawn in is on screen. A window hidden in the tray keeps its composition, so
 * work that only matters while someone looks, such as polling the agent's desktop, waits on this; on the
 * phone, the activity's lifecycle does that.
 */
val LocalWindowShown = compositionLocalOf { true }

/** Shows [content] as a layer above the window while this is in the composition. */
@Composable
fun Overlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current ?: error("Overlay needs an OverlayHost; see DudanWindow")
    val currentDismiss = rememberUpdatedState(onDismiss)
    val currentContent = rememberUpdatedState(content)
    val layer = remember { OverlayHost.Layer({ currentDismiss.value() }, { currentContent.value() }) }
    DisposableEffect(host, layer) {
        host.add(layer)
        onDispose { host.remove(layer) }
    }
}
