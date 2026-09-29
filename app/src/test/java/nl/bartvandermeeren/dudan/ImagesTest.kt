package nl.bartvandermeeren.dudan

import nl.bartvandermeeren.dudan.ui.components.ImageCodec
import nl.bartvandermeeren.dudan.ui.components.thumbnailKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImagesTest {
    @Test
    fun decodesNeverExceedTheBudgetedDimension() {
        // Power-of-two sampling would have left this at 2559 px, nearly four times the budgeted memory.
        assertEquals(1280 to 1280, ImageCodec.targetSize(2559, 2559, 1280))
        assertEquals(1280 to 426, ImageCodec.targetSize(3000, 1000, 1280))
        assertEquals(1 to 640, ImageCodec.targetSize(10, 100_000, 640))
        assertNull(ImageCodec.targetSize(1280, 720, 1280))
    }

    @Test
    fun differentUrlsWithCollidingJavaHashesHaveDifferentThumbnailKeys() {
        val first = "http://x/Aa"
        val second = "http://x/BB"
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.length, second.length)
        assertNotEquals(thumbnailKey(first, 640), thumbnailKey(second, 640))
    }
}
