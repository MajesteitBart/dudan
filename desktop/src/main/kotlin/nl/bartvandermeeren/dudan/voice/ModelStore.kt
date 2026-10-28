package nl.bartvandermeeren.dudan.voice

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * A sherpa-onnx voice model on the PC, kept under [root]: the phone's ModelPackage without its wait for
 * Wi-Fi, since a PC's connection is rarely metered. It only downloads when the user asks.
 */
class ModelStore(private val root: File, private val http: OkHttpClient, private val scope: CoroutineScope, val spec: ModelSpec) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State

        /** Loading the native engine took dudan down last time; the voice stays off until a retry. */
        data object Crashed : State
    }

    private val folder = File(root, spec.folder)
    val dir = File(folder, spec.name)
    private val marker = File(dir, ".complete")

    /** Exists only while the native engine loads. Still there at the next start means the load crashed dudan. */
    private val loadGuard = File(root, "${spec.folder}-loading")
    private var loading = 0

    private val _state = MutableStateFlow(
        when {
            !marker.exists() -> State.Missing
            loadGuard.exists() -> State.Crashed
            else -> State.Ready
        },
    )
    val state: StateFlow<State> = _state.asStateFlow()

    val isReady: Boolean get() = _state.value == State.Ready
    private var job: Job? = null
    /** The request reading now, so [cancel] can break off a read that's waiting on the network. */
    @Volatile private var call: Call? = null

    /** Runs the native engine load, leaving a file behind that the next start turns into Crashed if it never returns. */
    fun <T> guardLoad(load: () -> T): T {
        synchronized(loadGuard) {
            if (loading++ == 0) {
                root.mkdirs()
                loadGuard.writeText(spec.name)
            }
        }
        try {
            return load()
        } finally {
            synchronized(loadGuard) { if (--loading == 0) loadGuard.delete() }
        }
    }

    /** Settings' retry after a crash: the files are fine, so allow loading again. */
    fun retryAfterCrash() {
        loadGuard.delete()
        _state.value = if (marker.exists()) State.Ready else State.Missing
    }

    /** Starts the download, unless one is running or still cleaning up after [cancel]. */
    @Synchronized
    fun download() {
        if (isReady || job?.isCompleted == false) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = State.Downloading(0f)
            val staging = File(folder, "${spec.name}.partial")
            val self = coroutineContext.job
            try {
                // Anything already here is an interrupted install or an older version of the model; clear
                // it so the disk only holds one copy.
                folder.deleteRecursively()
                staging.mkdirs()
                var received = 0L
                var reported = 0L
                val progress: (Long) -> Unit = { bytes ->
                    // Every read checks for Stop download, so a cancelled download never installs.
                    self.ensureActive()
                    received += bytes
                    if (received - reported > 512 * 1024) {
                        reported = received
                        _state.value = State.Downloading((received.toFloat() / spec.sizeBytes).coerceIn(0f, 0.99f))
                    }
                }
                spec.url?.let { url ->
                    execute(url, self).use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        val body = response.body ?: error("empty response")
                        val digest = MessageDigest.getInstance("SHA-256")
                        val counting = CountingStream(DigestInputStream(body.byteStream(), digest), progress)
                        extract(counting, staging)
                        // The tar reader can stop before the end of the compressed stream; hash all of it.
                        val rest = ByteArray(1 shl 16)
                        while (counting.read(rest) >= 0) Unit
                        spec.sha256?.let { expected -> check(digest.hex() == expected) { "checksum mismatch" } }
                    }
                }
                spec.extras.forEach { fetch(it, staging, self, progress) }
                spec.prepare(staging)
                spec.required.forEach { check(File(staging, it).exists()) { "download is missing $it" } }
                self.ensureActive()
                // The marker goes in before the move, so the installed folder is complete or absent.
                File(staging, ".complete").writeText(spec.name)
                check(staging.renameTo(dir)) { "could not move the model into place" }
                _state.value = State.Ready
            } catch (e: CancellationException) {
                staging.deleteRecursively()
                _state.value = State.Missing
                throw e
            } catch (e: Exception) {
                staging.deleteRecursively()
                // A read broken off by cancel() fails like a dropped connection; it was a stop all the same.
                _state.value = if (isActive) State.Failed(e.message ?: e.toString()) else State.Missing
            } finally {
                call = null
            }
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        call?.cancel()
    }

    /** Streams one file to disk, since model files can be hundreds of megabytes. */
    /**
     * Starts a request [cancel] can break off. A cancel() from before the request existed couldn't, so the
     * job is checked once the request is in [call], before it runs.
     */
    private fun execute(url: String, self: Job): Response {
        val request = http.newCall(Request.Builder().url(url).build())
        call = request
        self.ensureActive()
        return request.execute()
    }

    private fun fetch(extra: ModelSpec.Extra, target: File, self: Job, progress: (Long) -> Unit) {
        val out = File(target, extra.fileName)
        check(out.canonicalPath.startsWith(target.canonicalPath + File.separator)) { "unsafe file name: ${extra.fileName}" }
        out.parentFile?.mkdirs()
        execute(extra.url, self).use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for ${extra.fileName}" }
            val digest = MessageDigest.getInstance("SHA-256")
            val body = response.body ?: error("empty response")
            CountingStream(DigestInputStream(body.byteStream(), digest), progress).use { input ->
                out.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            check(digest.hex() == extra.sha256) { "checksum mismatch for ${extra.fileName}" }
        }
    }

    /** Unpacks only the files the app uses; the rest of the archive is skipped while streaming. */
    private fun extract(input: InputStream, target: File) {
        val tar = TarArchiveInputStream(BZip2CompressorInputStream(input.buffered(1 shl 16), true))
        while (true) {
            val entry = tar.nextEntry ?: break
            val relative = entry.name.removePrefix("${spec.name}/")
            if (relative.isEmpty() || !spec.wanted(relative)) continue
            val out = File(target, relative)
            check(out.canonicalPath.startsWith(target.canonicalPath + File.separator)) { "unsafe path in archive: ${entry.name}" }
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it) }
            }
        }
    }

    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }

    /** Reports every read's byte count to [onRead]. */
    private class CountingStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) onRead(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onRead(it.toLong()) }
    }
}
