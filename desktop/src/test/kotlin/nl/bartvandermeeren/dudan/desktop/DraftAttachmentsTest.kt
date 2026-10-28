package nl.bartvandermeeren.dudan.desktop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.bartvandermeeren.dudan.chat.PreparedImage
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.components.thumbnailKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftAttachmentsTest {
    private val dir: File = Files.createTempDirectory("dudan-draft").toFile()
    // Unconfined: the encode runs up to its first suspension right away, and resumes where it's completed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val vm = MainViewModel(DesktopSettings(dir), scope)
    private val picture = PreparedImage(ByteArray(10), "data:image/jpeg;base64,AAAA")

    @After
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun aPictureThatFinishesEncodingGoesIntoTheDraftItWasPickedFor() {
        val encoded = CompletableDeferred<PreparedImage?>()
        vm.attachLater { encoded.await() }
        assertTrue("the composer waits for it", vm.preparingSend)
        encoded.complete(picture)
        assertEquals(listOf(picture), vm.attachments.map { it.image })
        assertFalse(vm.preparingSend)
    }

    @Test
    fun aPictureStillEncodingWhenTheUserMovesOnIsDropped() {
        val encoded = CompletableDeferred<PreparedImage?>()
        vm.attachLater { encoded.await() }
        vm.newChat()
        encoded.complete(picture)
        assertTrue(vm.attachments.isEmpty())

        val later = CompletableDeferred<PreparedImage?>()
        vm.attachLater { later.await() }
        vm.openSession("dudan_other")
        later.complete(picture)
        assertTrue(vm.attachments.isEmpty())
        assertFalse(vm.preparingSend)
    }

    @Test
    fun theImageCacheKeepsNoCopyOfAPicture() {
        val key = thumbnailKey("data:image/png;base64," + "Q".repeat(4000), 256)
        assertFalse(key, "QQQQ" in key)
        assertEquals("256:https://example.com/a.png", thumbnailKey("https://example.com/a.png", 256))
    }
}
