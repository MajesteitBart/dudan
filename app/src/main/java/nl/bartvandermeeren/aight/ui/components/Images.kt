package nl.bartvandermeeren.aight.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.aight.chat.PreparedImage
import okhttp3.OkHttpClient
import okhttp3.Request

private val thumbnailCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Loads a data: URL, content:// URI or http(s) URL as a downsampled bitmap, off the main thread. */
@Composable
fun rememberImageBitmap(source: String, maxDimension: Int = 1024): State<ImageBitmap?> {
    val context = LocalContext.current
    val key = "${source.hashCode()}:${source.length}:$maxDimension"
    return produceState(initialValue = thumbnailCache.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) { ImageCodec.decodeThumbnailCached(context, source, maxDimension) }
    }
}

object ImageCodec {
    private const val MAX_UPLOAD_DIMENSION = 1600

    /** Images in agent replies come from the web; anything bigger than this isn't worth a phone's data. */
    private const val MAX_REMOTE_BYTES = 12L * 1024 * 1024

    private val remote by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Shared by chat images and OpenUI, so a disposed chat row can reuse its decoded thumbnail. */
    fun decodeThumbnailCached(context: Context, source: String, maxDimension: Int): ImageBitmap? {
        val key = "${source.hashCode()}:${source.length}:$maxDimension"
        thumbnailCache.get(key)?.let { return it }
        return runCatching { decodeThumbnail(context, source, maxDimension) }.getOrNull()?.asImageBitmap()
            ?.also { thumbnailCache.put(key, it) }
    }

    fun decodeThumbnail(context: Context, source: String, maxDimension: Int): Bitmap? {
        if (source.startsWith("data:")) return decodeBytes(Base64.decode(source.substringAfter(","), Base64.DEFAULT), maxDimension)
        if (source.startsWith("https://") || source.startsWith("http://")) return decodeBytes(download(source), maxDimension)
        return decodeUri(context, Uri.parse(source), maxDimension)
    }

    private fun decodeBytes(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
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

    fun decodeUri(context: Context, uri: Uri, maxDimension: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > maxDimension) {
                val scale = maxDimension.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    suspend fun prepare(context: Context, uri: Uri): PreparedImage = withContext(Dispatchers.IO) {
        prepare(decodeUri(context, uri, MAX_UPLOAD_DIMENSION))
    }

    /** JPEG-encodes a bitmap for upload, capped at 1600 px so a turn stays well under Hermes' 10 MB limit. */
    fun prepare(bitmap: Bitmap): PreparedImage {
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_UPLOAD_DIMENSION) {
            val scale = MAX_UPLOAD_DIMENSION.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        return PreparedImage(bytes, "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }
}
