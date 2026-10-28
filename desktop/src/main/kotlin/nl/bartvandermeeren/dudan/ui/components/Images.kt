package nl.bartvandermeeren.dudan.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.dudan.chat.PreparedImage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * The cache key for [source] at [maxDimension]. A data: URL is the whole picture, so the key holds its digest
 * instead, and the cache keeps no copy of it.
 */
internal fun thumbnailKey(source: String, maxDimension: Int): String {
    if (!source.startsWith("data:")) return "$maxDimension:$source"
    val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
    return "$maxDimension:sha256:" + digest.joinToString("") { "%02x".format(it) }
}

/** Decoded thumbnails by [thumbnailKey], so a chat row that scrolls back in doesn't decode again. */
private val thumbnailCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 64
}

/** Loads a data:image URL or an http(s) URL as a downsampled bitmap, off the main thread; see [ImageCodec.bytesOf]. */
@Composable
fun rememberImageBitmap(source: String, maxDimension: Int = 1024): State<ImageBitmap?> {
    val key = remember(source, maxDimension) { thumbnailKey(source, maxDimension) }
    return produceState(initialValue = synchronized(thumbnailCache) { thumbnailCache[key] }, key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) { ImageCodec.decodeThumbnailCached(source, maxDimension) }
    }
}

/** The Android app's ImageCodec on Skia: the same limits, so a photo from the PC reaches Hermes as one from the phone. */
object ImageCodec {
    private const val MAX_UPLOAD_DIMENSION = 1600

    /** Images in agent replies come from the web; anything bigger than this isn't worth loading. */
    private const val MAX_REMOTE_BYTES = 12L * 1024 * 1024

    private val remote by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun decodeThumbnailCached(source: String, maxDimension: Int): ImageBitmap? {
        val key = thumbnailKey(source, maxDimension)
        synchronized(thumbnailCache) { thumbnailCache[key] }?.let { return it }
        return runCatching { scaled(Image.makeFromEncoded(bytesOf(source)), maxDimension).toComposeImageBitmap() }.getOrNull()
            ?.also { synchronized(thumbnailCache) { thumbnailCache[key] = it } }
    }

    /**
     * Only pictures carried in the source itself or on the web. Sources also come from replies, such as an
     * OpenUI image, so a file path or share never counts as one: reading it would let a reply look at this
     * PC's files or make Windows sign in to a share. Pictures the user picks go through [prepare].
     */
    private fun bytesOf(source: String): ByteArray = when {
        source.startsWith("data:image/") -> Base64.getMimeDecoder().decode(source.substringAfter(","))
        source.startsWith("https://") || source.startsWith("http://") -> download(source)
        else -> throw IOException("Not a picture source dudan loads")
    }

    private fun download(url: String): ByteArray =
        remote.newCall(Request.Builder().url(url).header("Accept", "image/*").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            if (body.contentLength() > MAX_REMOTE_BYTES) throw IOException("Image too large")
            val source = body.source()
            // Content-Length can be missing, so cap what is actually read as well.
            if (source.request(MAX_REMOTE_BYTES + 1)) throw IOException("Image too large")
            source.buffer.readByteArray()
        }

    /** The size to draw a [width] × [height] image at so its longest side is [maxDimension]; null when it already fits. */
    internal fun targetSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int>? {
        val longest = max(width, height)
        if (longest <= maxDimension) return null
        val scale = maxDimension.toFloat() / longest
        return (width * scale).toInt().coerceIn(1, maxDimension) to (height * scale).toInt().coerceIn(1, maxDimension)
    }

    private fun scaled(image: Image, maxDimension: Int): Image {
        val (width, height) = targetSize(image.width, image.height, maxDimension) ?: return image
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(width.toFloat(), height.toFloat()),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
            null,
            true,
        )
        return surface.makeImageSnapshot()
    }

    /** A frame of the agent's desktop: at most 8192 px a side from the server, drawn at no more than [maxDimension]. */
    fun decodeFrame(bytes: ByteArray, maxDimension: Int = 1600): ImageBitmap {
        val image = Image.makeFromEncoded(bytes)
        require(image.width in 1..8192 && image.height in 1..8192) { "Frame size ${image.width}x${image.height}" }
        return scaled(image, maxDimension).toComposeImageBitmap()
    }

    /** Reads an image file for sending; null when it isn't one Skia can decode. */
    suspend fun prepare(file: File): PreparedImage? = withContext(Dispatchers.IO) {
        runCatching { prepare(file.readBytes()) }.getOrNull()
    }

    /** JPEG-encodes encoded image [bytes] for upload, capped at 1600 px so a turn stays well under Hermes' 10 MB limit. */
    fun prepare(bytes: ByteArray): PreparedImage = prepare(Image.makeFromEncoded(bytes))

    fun prepare(image: Image): PreparedImage {
        val jpeg = scaled(image, MAX_UPLOAD_DIMENSION).encodeToData(EncodedImageFormat.JPEG, 85)?.bytes
            ?: throw IOException("Can't encode the image")
        return PreparedImage(jpeg, "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg))
    }
}
