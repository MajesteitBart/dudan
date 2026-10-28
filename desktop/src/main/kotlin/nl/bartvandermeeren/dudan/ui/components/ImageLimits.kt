package nl.bartvandermeeren.dudan.ui.components

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * How large an image from elsewhere may be before it is decoded. Sampling keeps the bitmap small, but
 * some decoders hold the whole image while they work, whatever the sample size: interlaced PNG,
 * progressive JPEG and JPEG with more than one scan, WebP other than simple lossy, and GIF. Those get
 * [BUFFERED_PIXELS]. A plain PNG, a single-scan sequential JPEG and a simple lossy WebP decode a few rows
 * at a time and get [STREAMED_PIXELS], which bounds how long a decode takes. Anything not recognised
 * gets the smaller limit.
 */
object ImageLimits {
    const val MAX_SIDE = 16_384
    const val STREAMED_PIXELS = 100_000_000L
    const val BUFFERED_PIXELS = 24_000_000L

    private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    fun allows(width: Int, height: Int, maxPixels: Long): Boolean =
        width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= maxPixels

    /** The pixel limit for the image [input] starts with. It reads the header only, up to a JPEG's first scan. */
    fun maxPixels(input: InputStream): Long = if (decodesInRows(input)) STREAMED_PIXELS else BUFFERED_PIXELS

    private fun decodesInRows(input: InputStream): Boolean = try {
        val data = DataInputStream(input)
        val start = ByteArray(2).also(data::readFully)
        if (start[0] == 0xFF.toByte() && start[1] == 0xD8.toByte()) {
            sequentialJpeg(data)
        } else {
            // Up to and including the PNG interlace byte, at offset 28.
            val head = start + ByteArray(27).also(data::readFully)
            when {
                head.copyOfRange(0, 8).contentEquals(PNG) -> head.ascii(12, 4) == "IHDR" && head[28] == 0.toByte()
                head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WEBP" -> head.ascii(12, 4) == "VP8 "
                else -> false
            }
        }
    } catch (_: IOException) {
        false
    }

    /**
     * Walks a JPEG's markers up to its first scan. libjpeg decodes a sequential (SOF0 or SOF1) image whose
     * first scan holds every component a row of blocks at a time; for anything else it keeps the
     * coefficients of the whole image.
     */
    private fun sequentialJpeg(data: DataInputStream): Boolean {
        var components = -1
        while (true) {
            if (data.readUnsignedByte() != 0xFF) return false
            var marker = data.readUnsignedByte()
            while (marker == 0xFF) marker = data.readUnsignedByte()
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            if (marker == 0xD9) return false
            val length = data.readUnsignedShort() - 2
            when {
                length < 0 -> return false
                marker == 0xC0 || marker == 0xC1 -> {
                    // Precision, height and width, then the number of components.
                    if (length < 6) return false
                    data.skipFully(5)
                    components = data.readUnsignedByte()
                    data.skipFully(length - 6)
                }
                // Any other frame type: progressive, lossless, hierarchical or arithmetic.
                marker in 0xC2..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> return false
                marker == 0xDA -> return components > 0 && length > 0 && data.readUnsignedByte() == components
                else -> data.skipFully(length)
            }
        }
    }

    private fun DataInputStream.skipFully(count: Int) {
        var left = count
        while (left > 0) {
            val skipped = skipBytes(left)
            if (skipped > 0) left -= skipped else { readUnsignedByte(); left-- }
        }
    }

    private fun ByteArray.ascii(offset: Int, length: Int) = String(this, offset, length, Charsets.ISO_8859_1)
}
