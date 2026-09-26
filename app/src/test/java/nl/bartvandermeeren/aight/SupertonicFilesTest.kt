package nl.bartvandermeeren.aight

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import nl.bartvandermeeren.aight.voice.SupertonicFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupertonicFilesTest {
    private fun style(ttl: List<List<Double>>, dp: List<List<Double>>) =
        """{"style_ttl": {"dims": [1, ${ttl.size}, ${ttl[0].size}], "data": [$ttl]}, "style_dp": {"dims": [1, ${dp.size}, ${dp[0].size}], "data": [$dp]}}"""

    @Test
    fun indexerIsLittleEndianInt32() {
        val bytes = SupertonicFiles.indexer("[0, -1, 258]")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf(0, -1, 258), List(3) { buffer.getInt() })
    }

    @Test
    fun voicesAreStackedAfterAHeader() {
        val first = style(listOf(listOf(1.0, 2.0), listOf(3.0, 4.0)), listOf(listOf(5.0)))
        val second = style(listOf(listOf(6.0, 7.0), listOf(8.0, 9.0)), listOf(listOf(10.0)))
        val buffer = ByteBuffer.wrap(SupertonicFiles.voices(listOf(first, second))).order(ByteOrder.LITTLE_ENDIAN)
        // [n, d1, d2] for text-to-latent, then for duration.
        assertEquals(listOf(2L, 2L, 2L, 2L, 1L, 1L), List(6) { buffer.getLong() })
        // All text-to-latent styles first, then all duration styles.
        val floats = FloatArray(10) { buffer.getFloat() }
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 6f, 7f, 8f, 9f, 5f, 10f), floats, 0f)
        assertFalse(buffer.hasRemaining())
    }

    @Test
    fun prepareWritesBothFilesInNameOrderAndRemovesTheJson() {
        val dir = Files.createTempDirectory("supertonic").toFile()
        try {
            File(dir, "unicode_indexer.json").writeText("[1, 2]")
            val styles = File(dir, SupertonicFiles.STYLES_DIR).apply { mkdirs() }
            File(styles, "M1.json").writeText(style(listOf(listOf(2.0)), listOf(listOf(20.0))))
            File(styles, "F1.json").writeText(style(listOf(listOf(1.0)), listOf(listOf(10.0))))
            SupertonicFiles.prepare(dir)
            assertTrue(File(dir, SupertonicFiles.INDEXER).exists())
            assertFalse(File(dir, "unicode_indexer.json").exists())
            assertFalse(styles.exists())
            val buffer = ByteBuffer.wrap(File(dir, SupertonicFiles.VOICES).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            repeat(6) { buffer.getLong() }
            // F1 before M1, as sherpa's script sorts the files by name.
            assertArrayEquals(floatArrayOf(1f, 2f, 10f, 20f), FloatArray(4) { buffer.getFloat() }, 0f)
        } finally {
            dir.deleteRecursively()
        }
    }
}
