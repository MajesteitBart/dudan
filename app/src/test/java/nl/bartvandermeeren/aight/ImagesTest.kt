package nl.bartvandermeeren.aight

import nl.bartvandermeeren.aight.ui.components.thumbnailKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImagesTest {
    @Test
    fun differentUrlsWithCollidingJavaHashesHaveDifferentThumbnailKeys() {
        val first = "http://x/Aa"
        val second = "http://x/BB"
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.length, second.length)
        assertNotEquals(thumbnailKey(first, 640), thumbnailKey(second, 640))
    }
}
