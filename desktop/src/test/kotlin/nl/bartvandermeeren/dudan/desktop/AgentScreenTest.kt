package nl.bartvandermeeren.dudan.desktop

import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import nl.bartvandermeeren.dudan.data.DesktopKeys
import nl.bartvandermeeren.dudan.ui.chat.DesktopWatch
import nl.bartvandermeeren.dudan.ui.chat.desktopWatch
import nl.bartvandermeeren.dudan.ui.chat.rememberKeyTracker
import nl.bartvandermeeren.dudan.ui.components.ImageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentScreenTest {
    @Test
    fun anOpenWindowWatchesWhateverTheMainWindowDoes() {
        assertEquals(DesktopWatch.Window, desktopWatch(windowOpen = true, windowMinimized = false, cardVisible = false, mainShown = false))
        assertEquals(DesktopWatch.Window, desktopWatch(windowOpen = true, windowMinimized = false, cardVisible = true, mainShown = true))
    }

    @Test
    fun aMinimizedWindowStopsWatchingSoControlIsReleased() {
        assertNull(desktopWatch(windowOpen = true, windowMinimized = true, cardVisible = false, mainShown = false))
        // The card in view may still watch, slowly, which starts a new watch without the lease.
        assertEquals(DesktopWatch.Card, desktopWatch(windowOpen = true, windowMinimized = true, cardVisible = true, mainShown = true))
    }

    /** Switching apps keeps a node's focus, so the window's own focus has to clear an AltGr released elsewhere. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anAltGrReleasedInAnotherAppIsForgotten() = runDesktopComposeUiTest {
        var focused by mutableStateOf(true)
        val window = object : WindowInfo {
            override val isWindowFocused: Boolean get() = focused
        }
        var tracker: DesktopKeys.Tracker? = null
        setContent { CompositionLocalProvider(LocalWindowInfo provides window) { tracker = rememberKeyTracker(inputEnabled = true) } }
        waitForIdle()
        val keys = tracker!!
        val source = object : Component() {}
        val ctrlAlt = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK
        assertNull(keys.input(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, ctrlAlt, KeyEvent.VK_ALT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_RIGHT)))
        // Another app takes the focus and gets the release; then dudan comes back with left Ctrl+Alt held.
        focused = false
        waitForIdle()
        focused = true
        waitForIdle()
        val shortcut = keys.input(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, ctrlAlt, KeyEvent.VK_T, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_STANDARD))
        assertEquals(listOf("ctrl", "alt", "t"), shortcut?.get("keys")?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun theCardWatchesOnlyInViewInAWindowOnScreen() {
        assertEquals(DesktopWatch.Card, desktopWatch(windowOpen = false, windowMinimized = false, cardVisible = true, mainShown = true))
        assertNull(desktopWatch(windowOpen = false, windowMinimized = false, cardVisible = true, mainShown = false))
        assertNull(desktopWatch(windowOpen = false, windowMinimized = false, cardVisible = false, mainShown = true))
    }
}

/** Image sources can come from replies, so only pictures in the source itself or on the web load. */
class ImageSourcesTest {
    @Test
    fun localFilesAndSharesNeverLoad() {
        val file = Files.createTempFile("dudan-image", ".png").toFile()
        try {
            ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", file)
            assertNull(ImageCodec.decodeThumbnailCached(file.absolutePath, 64))
            assertNull(ImageCodec.decodeThumbnailCached(file.toURI().toString(), 64))
            assertNull(ImageCodec.decodeThumbnailCached("\\\\fileserver\\share\\picture.png", 64))
            assertNull(ImageCodec.decodeThumbnailCached("data:text/plain;base64,aGVsbG8=", 64))
        } finally {
            file.delete()
        }
    }

    @Test
    fun aPictureInADataUrlLoads() {
        val png = ByteArrayOutputStream().use { out -> ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out); out.toByteArray() }
        assertNotNull(ImageCodec.decodeThumbnailCached("data:image/png;base64," + Base64.getEncoder().encodeToString(png), 64))
    }
}
