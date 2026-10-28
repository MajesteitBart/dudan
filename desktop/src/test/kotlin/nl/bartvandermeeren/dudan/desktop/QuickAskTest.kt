package nl.bartvandermeeren.dudan.desktop

import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.bartvandermeeren.dudan.ui.MainViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAskTest {
    private val dir: File = Files.createTempDirectory("dudan-quick").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun theShortcutAgainTakesItsPictureWithoutTheLastQuestionOnScreen() {
        val vm = MainViewModel(DesktopSettings(dir), scope)
        // Whether a quick question was on screen at each capture.
        val shownAtGrab = mutableListOf<Boolean>()
        lateinit var quick: QuickAsk
        SwingUtilities.invokeAndWait {
            quick = QuickAsk(vm, scope, grab = { shownAtGrab += quick.visible; null })
            quick.open()
            quick.open()
        }
        val until = System.currentTimeMillis() + 5_000
        while (shownAtGrab.size < 2 && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(listOf(false, false), shownAtGrab)
        SwingUtilities.invokeAndWait { assertTrue(quick.visible) }
    }
}
