package nl.bartvandermeeren.dudan.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MouseInfo
import java.awt.Point
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BaseMultiResolutionImage
import nl.bartvandermeeren.dudan.desktop.res.*
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.components.DudanMark
import nl.bartvandermeeren.dudan.ui.components.GlassDefaults
import nl.bartvandermeeren.dudan.ui.settings.label
import nl.bartvandermeeren.dudan.ui.theme.Accent
import nl.bartvandermeeren.dudan.ui.theme.DudanTheme
import nl.bartvandermeeren.dudan.ui.theme.Palette
import nl.bartvandermeeren.dudan.ui.theme.Sky
import org.jetbrains.compose.resources.stringResource

/**
 * dudan's icon in the notification area. A click opens dudan, as does a click on one of its notifications.
 * Windows draws an AWT tray icon's own menu as a Windows 95 menu that can't be styled, so a right click
 * calls [onMenu] with the pointer's place instead, and [TrayMenuWindow] draws dudan's menu there.
 */
class DesktopTray(image: Image, tooltip: String, private val onOpen: () -> Unit, private val onMenu: (Point) -> Unit) {
    private val icon: TrayIcon? = if (SystemTray.isSupported()) TrayIcon(image, tooltip) else null

    init {
        icon?.apply {
            isImageAutoSize = true
            // A double click, or a click on a notification.
            addActionListener { onOpen() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    when {
                        // The pointer from MouseInfo: a tray event's own point can be in unscaled pixels on a scaled screen.
                        e.isPopupTrigger || e.button == MouseEvent.BUTTON3 -> onMenu(MouseInfo.getPointerInfo()?.location ?: e.locationOnScreen)
                        e.button == MouseEvent.BUTTON1 && e.clickCount == 1 -> onOpen()
                    }
                }
            })
            runCatching { SystemTray.getSystemTray().add(this) }
        }
    }

    fun notify(title: String, body: String) {
        icon?.displayMessage(title, body, TrayIcon.MessageType.NONE)
    }

    fun remove() {
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
    }

    companion object {
        /** The icon at every size Windows may draw it, 100% to 300% scaling, so it stays sharp. */
        fun image(painter: Painter): Image = BaseMultiResolutionImage(
            *listOf(16, 20, 24, 32, 40, 48, 64).map { px -> painter.toAwtImage(Density(1f), LayoutDirection.Ltr, Size(px.toFloat(), px.toFloat())) }.toTypedArray(),
        )
    }
}

private val MenuWidth = 336.dp
/** Room around the card for its shadow; the window itself is transparent. */
private val MenuMargin = 14.dp
/** Three items, a divider and the card's padding: 6 + 44 + 44 + 9 + 44 + 6. */
private val MenuHeight = 153.dp

/**
 * The tray icon's menu, drawn by dudan: open, a quick question with its keys, quit. It opens beside [at],
 * above the pointer when there's room, as Windows 11 places a tray menu, and goes away with Esc, a choice
 * or a click anywhere else.
 */
@Composable
fun TrayMenuWindow(at: Point, vm: MainViewModel, onOpen: () -> Unit, onQuickAsk: () -> Unit, onQuit: () -> Unit, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val size = DpSize(MenuWidth + MenuMargin * 2, MenuHeight + MenuMargin * 2)
    val position = remember(at) { menuPosition(at, size) }
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(position = position, size = size),
        title = "dudan",
        undecorated = true,
        transparent = true,
        resizable = false,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        DisposableEffect(window) {
            window.isAlwaysOnTop = true
            val focus = object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) = onDismiss()
            }
            window.addWindowFocusListener(focus)
            window.toFront()
            window.requestFocus()
            onDispose { window.removeWindowFocusListener(focus) }
        }
        DudanTheme(settings.reduceTransparency, Accent.from(settings.accent), Sky.from(settings.sky), prefs.textScale) {
            Box(Modifier.fillMaxSize().padding(MenuMargin), contentAlignment = Alignment.BottomCenter) {
                TrayMenu(
                    quickKeys = prefs.shortcut(ShortcutAction.QuickAsk)?.label(),
                    onOpen = { onDismiss(); onOpen() },
                    onQuickAsk = { onDismiss(); onQuickAsk() },
                    onQuit = { onDismiss(); onQuit() },
                )
            }
        }
    }
}

/** The menu's card: open dudan, a quick question with its keys, and quit, apart from the divider. */
@Composable
internal fun TrayMenu(quickKeys: String?, onOpen: () -> Unit, onQuickAsk: () -> Unit, onQuit: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(18.dp, shape)
            .clip(shape)
            .background(Palette.MenuSolid)
            .border(GlassDefaults.Border, shape)
            .padding(6.dp),
    ) {
        TrayMenuItem(icon = { DudanMark(size = 20.dp) }, label = stringResource(Res.string.desktop_tray_open), onClick = onOpen)
        TrayMenuItem(icon = { ItemIcon(Icons.Outlined.Bolt) }, label = stringResource(Res.string.desktop_tray_quick_ask), keys = quickKeys, onClick = onQuickAsk)
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).height(1.dp).background(Palette.Hairline))
        TrayMenuItem(icon = { ItemIcon(Icons.Outlined.PowerSettingsNew) }, label = stringResource(Res.string.desktop_tray_quit), onClick = onQuit)
    }
}

@Composable
private fun ItemIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
}

@Composable
private fun TrayMenuItem(icon: @Composable () -> Unit, label: String, keys: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (keys != null) {
            Spacer(Modifier.size(4.dp))
            Text(keys, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1)
        }
    }
}

/**
 * Where the menu's window goes for a click at [at]: its card's bottom corner at the pointer, to the left
 * when the pointer is near the right edge, kept inside the screen's work area, so off the taskbar.
 */
private fun menuPosition(at: Point, size: DpSize): WindowPosition {
    val config = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration }
        .firstOrNull { it.bounds.contains(at) }
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val area = config.bounds.apply {
        x += insets.left
        y += insets.top
        width -= insets.left + insets.right
        height -= insets.top + insets.bottom
    }
    val w = size.width.value.toInt()
    val h = size.height.value.toInt()
    val margin = MenuMargin.value.toInt()
    val x = (if (at.x + w - margin > area.x + area.width) at.x - w + margin else at.x - margin).coerceIn(area.x, area.x + area.width - w)
    val y = (if (at.y - h + margin >= area.y) at.y - h + margin else at.y - margin).coerceIn(area.y, area.y + area.height - h)
    return WindowPosition(x.dp, y.dp)
}
