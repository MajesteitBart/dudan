package nl.bartvandermeeren.aight.data

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Client for the aight upload service on the Hermes host (tools/hermes-upload). Hermes' own API
 * server takes no files, so a file goes there first and the turn then names its path. Uploads go in
 * chunks and pick up where the service left off when the connection drops, which on mobile data
 * happens during a 250 MB video.
 */
class UploadClient(
    private val http: OkHttpClient,
    /** How long to wait while the service still holds a dropped connection lock on an upload. */
    private val busyWaitMs: Long = 10_000L,
    private val config: suspend () -> Config,
) {
    data class Config(val baseUrl: String, val apiKey: String)

    open class UploadException(message: String, val status: Int = 0, val code: String? = null) : IOException(message)

    /** Where a file's bytes come from. [open] starts reading at [offset]. */
    interface Source {
        val name: String
        val mime: String
        val size: Long
        fun open(offset: Long): InputStream
    }

    data class Health(val maxBytes: Long, val chunkBytes: Int)

    suspend fun health(): Health = withContext(Dispatchers.IO) {
        val o = send(request("health").get().build())
        Health(o.dbl("max_bytes")?.toLong() ?: MAX_ATTACHMENT_BYTES, o.int("chunk_bytes") ?: DEFAULT_CHUNK)
    }

    /**
     * Uploads [source] and returns where it landed. [onProgress] gets the bytes the service has
     * stored so far. [onCreated] gets the upload id as soon as there is one, so a cancelled upload
     * can be deleted.
     */
    suspend fun upload(source: Source, onCreated: (String) -> Unit = {}, onProgress: (Long) -> Unit = {}): FileRef = withContext(Dispatchers.IO) {
        if (source.size <= 0) throw UploadException("${source.name} is empty.")
        if (source.size > MAX_ATTACHMENT_BYTES) throw UploadException("${source.name} is larger than ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB.", code = "too_large")
        val created = send(
            request("uploads").post(
                buildJsonObject {
                    put("name", source.name)
                    put("size", source.size)
                    put("mime", source.mime)
                }.toString().toRequestBody(JSON),
            ).build(),
        )
        val id = created.str("id") ?: throw UploadException("The upload service didn't return an upload id.")
        onCreated(id)
        val chunk = (created.int("chunk_bytes") ?: DEFAULT_CHUNK).coerceIn(256 * 1024, MAX_CHUNK)

        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        var stream: InputStream? = null
        var failures = 0
        var busyWaits = 0
        val buffer = ByteArray(chunk)
        var landed: FileRef? = null
        try {
            while (landed == null) {
                ensureActive()
                if (stream == null) stream = source.open(offset)
                val length = minOf(chunk.toLong(), source.size - offset).toInt()
                readFully(stream, buffer, length, source.name)
                // A file that grew since it was picked would arrive cut off, with a matching checksum.
                if (offset + length == source.size && stream.read() != -1) {
                    throw UploadException("${source.name} changed while it was uploading. Pick it again.", code = "changed")
                }
                val result = try {
                    send(
                        request("uploads", id, query = mapOf("offset" to offset.toString()))
                            .put(buffer.toRequestBody(OCTETS, 0, length))
                            .build(),
                    ).also { failures = 0 }
                } catch (e: UploadException) {
                    val held = (e as? OffsetMismatch)?.serverOffset
                    if (held != null) {
                        // The service holds a different amount than we thought; continue from its count.
                        stream.close()
                        stream = null
                        offset = rehash(source, digest, held)
                        continue
                    }
                    if (e.code == "upload_busy") {
                        // A connection that died mid-chunk keeps the upload locked until the service
                        // times it out (120 s). That is waiting, not failing.
                        busyWaits++
                        if (busyWaits > MAX_BUSY_WAITS) throw e
                        stream.close()
                        stream = null
                        delay(busyWaitMs)
                        offset = rehash(source, digest, serverOffset(id) ?: offset)
                        continue
                    }
                    // 4xx and a full disk won't get better by trying again.
                    if ((e.status in 400..499 && e.status != 408 && e.status != 429) || e.status == 507) throw e
                    failures++
                    if (failures > MAX_RETRIES) throw e
                    stream.close()
                    stream = null
                    delay(backoff(failures))
                    offset = rehash(source, digest, serverOffset(id) ?: offset)
                    continue
                } catch (e: IOException) {
                    failures++
                    if (failures > MAX_RETRIES) throw e
                    stream.close()
                    stream = null
                    delay(backoff(failures))
                    offset = rehash(source, digest, serverOffset(id) ?: offset)
                    continue
                }
                digest.update(buffer, 0, length)
                offset += length
                onProgress(offset)
                if (result.bool("complete") == true) {
                    val path = result.str("path") ?: throw UploadException("The upload service didn't say where it saved ${source.name}.")
                    val expected = digest.digest().joinToString("") { "%02x".format(it) }
                    val stored = result.str("sha256")
                    if (stored != null && !stored.equals(expected, ignoreCase = true)) {
                        runCatching { delete(id) }
                        throw UploadException("${source.name} arrived damaged. Try again.", code = "checksum_mismatch")
                    }
                    landed = FileRef(source.name, source.mime, source.size, path)
                } else if (offset >= source.size) {
                    throw UploadException("The upload service didn't finish ${source.name}.")
                }
            }
        } finally {
            stream?.close()
        }
        landed
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        try {
            send(request("uploads", id).delete().build())
        } catch (e: UploadException) {
            if (e.status != 404) throw e
        }
        Unit
    }

    private suspend fun serverOffset(id: String): Long? =
        runCatching { send(request("uploads", id).get().build()).dbl("offset")?.toLong() }.getOrNull()

    /** Resets [digest] to cover the first [offset] bytes, the part the service already holds. */
    private fun rehash(source: Source, digest: MessageDigest, offset: Long): Long {
        digest.reset()
        if (offset <= 0) return 0
        source.open(0).use { input ->
            val buffer = ByteArray(64 * 1024)
            var left = offset
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) throw UploadException("${source.name} got shorter while it was uploading.")
                digest.update(buffer, 0, read)
                left -= read
            }
        }
        return offset
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int, name: String) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw UploadException("$name got shorter while it was uploading.")
            read += n
        }
    }

    private fun backoff(failures: Int): Long = minOf(30_000L, 1_000L * (1L shl (failures - 1)))

    private class OffsetMismatch(message: String, val serverOffset: Long) : UploadException(message, 409, "offset_mismatch")

    private suspend fun request(vararg segments: String, query: Map<String, String> = emptyMap()): Request.Builder {
        val c = config()
        val base: HttpUrl = c.baseUrl.toHttpUrlOrNull() ?: throw UploadException("The upload server address isn't valid.")
        val url = base.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return Request.Builder().url(url).header("Authorization", "Bearer ${c.apiKey}").header("Accept", "application/json")
    }

    /** Cancelling the coroutine cancels the call, so a removed file stops uploading mid-chunk. */
    private suspend fun send(request: Request): JsonObject {
        val call = http.newCall(request)
        val response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, value, _ -> value.close() }
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            })
        }
        response.use { response ->
            val text = response.body?.string().orEmpty()
            val root = runCatching { HermesJson.parseToJsonElement(text) }.getOrNull().asObject() ?: JsonObject(emptyMap())
            if (response.isSuccessful) return root
            val error = root["error"].asObject()
            val message = error?.str("message") ?: text.take(200).ifBlank { "HTTP ${response.code}" }
            val code = error?.str("code")
            if (response.code == 409 && code == "offset_mismatch") {
                root.dbl("offset")?.toLong()?.let { throw OffsetMismatch(message, it) }
            }
            throw UploadException(message, response.code, code)
        }
    }

    companion object {
        const val DEFAULT_PORT = 8645
        private const val DEFAULT_CHUNK = 8 * 1024 * 1024
        private const val MAX_CHUNK = 16 * 1024 * 1024
        private const val MAX_RETRIES = 8
        private const val MAX_BUSY_WAITS = 18
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTETS = "application/octet-stream".toMediaType()

        /**
         * The upload service's address: [override] when set, otherwise the Hermes server's host on
         * port 8645, since the service runs next to Hermes.
         */
        fun baseUrlFor(serverUrl: String, override: String): String {
            val explicit = override.trim()
            if (explicit.isNotEmpty()) return HermesApi.normalizeBaseUrl(explicit)
            val server = HermesApi.normalizeBaseUrl(serverUrl).toHttpUrlOrNull() ?: return ""
            return server.newBuilder().port(DEFAULT_PORT).encodedPath("/").build().toString().trimEnd('/')
        }
    }
}
