package nl.bartvandermeeren.dudan.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.FileDialog
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import nl.bartvandermeeren.dudan.chat.PreparedImage
import nl.bartvandermeeren.dudan.ui.components.ImageCodec

/** The screen under the mouse pointer, at its full resolution. */
object ScreenCapture {
    /** Grabs the pixels right away, before a dudan window covers them; [prepare] encodes them later. */
    fun grab(): BufferedImage? = runCatching {
        val device = MouseInfo.getPointerInfo()?.device ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        val bounds: Rectangle = device.defaultConfiguration.bounds
        // With display scaling, the plain capture is at the scaled-down size; the last variant is the real pixels.
        val shot = Robot(device).createMultiResolutionScreenCapture(bounds).resolutionVariants.last()
        BufferedImage(shot.getWidth(null), shot.getHeight(null), BufferedImage.TYPE_INT_RGB).also { image ->
            image.createGraphics().apply { drawImage(shot, 0, 0, null); dispose() }
        }
    }.getOrNull()

    /** The grabbed screen as a photo to send; slow on a large screen, so call it off the UI thread. */
    fun prepare(image: BufferedImage): PreparedImage? = runCatching { ImageCodec.prepare(png(image)) }.getOrNull()

    /** A small picture of the grabbed screen for the prompt bar, made here so no shared image cache keeps it. */
    fun preview(image: BufferedImage, maxDimension: Int = 256): ImageBitmap {
        val scale = minOf(1.0, maxDimension.toDouble() / maxOf(image.width, image.height))
        val width = maxOf(1, (image.width * scale).toInt())
        val height = maxOf(1, (image.height * scale).toInt())
        val small = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, width, height, null)
            dispose()
        }
        return small.toComposeImageBitmap()
    }

    internal fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { out ->
        if (!ImageIO.write(image, "png", out)) throw IOException("No PNG writer")
        out.toByteArray()
    }
}

object DesktopFiles {
    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    fun isImage(file: File) = file.isFile && file.extension.lowercase() in imageExtensions

    /** Windows' own file dialog for pictures; empty when cancelled. */
    fun pickImages(owner: Frame?, title: String): List<File> {
        val dialog = FileDialog(owner, title, FileDialog.LOAD).apply {
            isMultipleMode = true
            // The Windows dialog filters by this pattern; the filter below covers typed names.
            file = imageExtensions.joinToString(";") { "*.$it" }
            setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in imageExtensions }
        }
        dialog.isVisible = true
        return dialog.files.orEmpty().filter(::isImage)
    }

    /**
     * Windows' own save dialog, over the dudan window that has the focus, starting at [name]; null when
     * cancelled. The dialog asks before replacing a file.
     */
    fun saveAs(title: String, name: String): File? {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
        val dialog = FileDialog(owner, title, FileDialog.SAVE).apply { file = name }
        dialog.isVisible = true
        return dialog.files.orEmpty().firstOrNull()
    }

    /** Explorer with [file] selected. It doesn't open the file: what the agent made isn't run. */
    fun showInFolder(file: File) {
        runCatching { ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start() }
    }

    /** A picture on the clipboard (a screenshot taken with Win+Shift+S, say), as PNG bytes; null when there is none. */
    fun clipboardImage(): ByteArray? = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
        buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        ScreenCapture.png(buffered)
    }.getOrNull()

    /** Image files copied in Explorer, for Ctrl+V. */
    fun clipboardFiles(): List<File> = runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) return emptyList()
        (clipboard.getData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>().filter(::isImage)
    }.getOrDefault(emptyList())
}

/**
 * One dudan per Windows account and profile. The first one listens on a loopback port; a second start
 * tells it to show its window and exits, as a click on the Start menu entry should while dudan sits in
 * the tray. A DUDAN_HOME profile is a dudan of its own, so it runs beside the installed one. Loopback
 * ports are shared by every account signed in on the PC, so the port and the handshake carry [identity]:
 * another account's dudan on the port isn't this one.
 */
class SingleInstance(
    private val identity: String = identityOf(System.getProperty("user.name"), System.getenv("DUDAN_HOME")),
    private val port: Int = portFor(identity),
    private val onShow: () -> Unit,
) {
    private var server: ServerSocket? = null

    /**
     * True when this dudan should run: it's the only one, or the port belongs to some other program.
     * False when a running dudan answered and will show itself.
     */
    fun claim(): Boolean {
        val socket = try {
            ServerSocket(port, 4, InetAddress.getLoopbackAddress())
        } catch (_: IOException) {
            return !otherDudanShowed()
        }
        server = socket
        thread(isDaemon = true, name = "dudan-instance") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use {
                        it.soTimeout = 1_000
                        if (it.getInputStream().bufferedReader().readLine() == "$SHOW $identity") {
                            it.getOutputStream().write("$SHOWN\n".toByteArray())
                            onShow()
                        }
                    }
                }
            }
        }
        return true
    }

    private fun otherDudanShowed(): Boolean = runCatching {
        Socket(InetAddress.getLoopbackAddress(), port).use {
            it.soTimeout = 1_000
            it.getOutputStream().write("$SHOW $identity\n".toByteArray())
            it.getInputStream().bufferedReader().readLine() == SHOWN
        }
    }.getOrDefault(false)

    fun release() {
        runCatching { server?.close() }
    }

    companion object {
        /** A fixed loopback port. Another program on it means dudan runs without the guard. */
        private const val PORT = 48652
        private const val SHOW = "dudan:show"
        private const val SHOWN = "dudan:shown"

        /** The Windows account and the DUDAN_HOME profile, if any; the same folder written another way is the same profile. */
        fun identityOf(user: String?, home: String?): String =
            user.orEmpty().lowercase() + "|" + home?.takeIf { it.isNotBlank() }?.let { java.io.File(it).absolutePath.lowercase() }.orEmpty()

        /** One of a thousand ports from the fixed one up, picked by [identity]. */
        fun portFor(identity: String): Int = PORT + Math.floorMod(identity.hashCode(), 1000)
    }
}
