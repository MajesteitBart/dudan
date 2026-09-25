package nl.bartvandermeeren.aight.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * An on-device model in sherpa-onnx packaging (Kokoro, Orukeet): downloaded once, unpacked into app
 * storage, and kept across app updates.
 */
class ModelPackage(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope, private val spec: Spec) {
    /** Where a model comes from and which of its files the app needs. */
    class Spec(
        /** Folder under the app's files, and the name of the crash guard file. */
        val folder: String,
        /** Top folder inside the archive, and the installed folder's name. */
        val name: String,
        val url: String,
        val sizeBytes: Long,
        /** SHA-256 of the archive when the publisher gives one. */
        val sha256: String? = null,
        val required: List<String>,
        val wanted: (String) -> Boolean = { it in required },
        /** Separate single files, such as the voice activity model next to a recognizer. */
        val extras: List<Extra> = emptyList(),
    )

    class Extra(val url: String, val fileName: String, val sha256: String)

    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State

        /** Loading the native engine killed the app last time; the app falls back until a retry. */
        data object Crashed : State
    }

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, spec.folder)
    val dir = File(root, spec.name)
    private val marker = File(dir, ".complete")

    /** Exists only while the native engine loads. Still there at the next start means the load crashed the app. */
    private val loadGuard = File(appContext.noBackupFilesDir, "${spec.folder}-loading")

    private val _state = MutableStateFlow(
        when {
            !marker.exists() -> State.Missing
            loadGuard.exists() -> State.Crashed
            else -> State.Ready
        },
    )
    val state: StateFlow<State> = _state.asStateFlow()

    val isReady: Boolean get() = _state.value == State.Ready
    val sizeBytes: Long get() = spec.sizeBytes
    private var job: Job? = null
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private var networkWatch: ConnectivityManager.NetworkCallback? = null

    fun file(name: String) = File(dir, name)

    /**
     * Downloads as soon as the phone is on an unmetered network, now or later, so a large model never
     * eats mobile data by surprise. A failed download waits for the Retry button instead of looping.
     */
    @Synchronized
    fun downloadWhenFree() {
        if (isReady || networkWatch != null || connectivity == null) return
        val watch = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val free = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                // Validated: behind a captive portal (hotel, train) the download would fail before login.
                val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (free && online && _state.value == State.Missing) download()
            }
        }
        networkWatch = watch
        connectivity.registerDefaultNetworkCallback(watch)
    }

    /**
     * Runs the native engine load. A crash in native code can't be caught, so this leaves a file behind
     * that the next start turns into Crashed, instead of taking the app down every time.
     */
    fun <T> guardLoad(load: () -> T): T {
        // Counted, so a second load running alongside (recognizer and voice detector) keeps the guard up.
        synchronized(loadGuard) { if (loading++ == 0) loadGuard.writeText(spec.name) }
        try {
            return load()
        } finally {
            synchronized(loadGuard) { if (--loading == 0) loadGuard.delete() }
        }
    }

    private var loading = 0

    /** Settings' retry after a crash: the files are fine, so allow loading again. */
    fun retryAfterCrash() {
        loadGuard.delete()
        _state.value = if (marker.exists()) State.Ready else State.Missing
    }

    @Synchronized
    fun stopWaitingForNetwork() {
        networkWatch?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        networkWatch = null
    }

    @Synchronized
    fun download() {
        if (isReady || job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = State.Downloading(0f)
            val staging = File(root, "${spec.name}.partial")
            try {
                // An unmarked install is left over from an interrupted one; clear it so the disk only holds one copy.
                dir.deleteRecursively()
                staging.deleteRecursively()
                staging.mkdirs()
                http.newCall(Request.Builder().url(spec.url).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body ?: error("empty response")
                    val total = body.contentLength().takeIf { it > 0 } ?: spec.sizeBytes
                    val digest = MessageDigest.getInstance("SHA-256")
                    val counting = CountingStream(DigestInputStream(body.byteStream(), digest)) { read ->
                        _state.value = State.Downloading((read.toFloat() / total).coerceIn(0f, 0.99f))
                    }
                    extract(counting, staging)
                    // The tar reader can stop before the end of the compressed stream; hash all of it.
                    val rest = ByteArray(1 shl 16)
                    while (counting.read(rest) >= 0) Unit
                    spec.sha256?.let { expected -> check(digest.hex() == expected) { "checksum mismatch" } }
                }
                spec.extras.forEach { fetch(it, staging) }
                // The marker goes in before the move, so the installed folder is complete or absent.
                File(staging, ".complete").writeText(spec.name)
                check(staging.renameTo(dir)) { "could not move the model into place" }
                _state.value = State.Ready
                stopWaitingForNetwork()
            } catch (e: CancellationException) {
                staging.deleteRecursively()
                _state.value = State.Missing
                throw e
            } catch (e: Exception) {
                staging.deleteRecursively()
                _state.value = State.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun fetch(extra: Extra, target: File) {
        http.newCall(Request.Builder().url(extra.url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for ${extra.fileName}" }
            val bytes = (response.body ?: error("empty response")).bytes()
            check(MessageDigest.getInstance("SHA-256").digest(bytes).hex() == extra.sha256) { "checksum mismatch for ${extra.fileName}" }
            File(target, extra.fileName).writeBytes(bytes)
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
        spec.required.forEach { check(File(target, it).exists()) { "archive is missing $it" } }
    }

    private fun MessageDigest.hex() = digest().hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private class CountingStream(input: InputStream, private val onProgress: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var reported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        private fun advance(bytes: Long) {
            count += bytes
            if (count - reported > 512 * 1024) {
                reported = count
                onProgress(count)
            }
        }
    }

    companion object {
        /** Kokoro-82M int8 from the sherpa-onnx release. English synthesis only needs part of it. */
        val KOKORO = Spec(
            folder = "kokoro",
            name = "kokoro-int8-multi-lang-v1_0",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
            sizeBytes = 132_303_094L,
            required = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "espeak-ng-data/phontab"),
            // The Chinese lexicons and jieba dictionary stay out.
            wanted = { path ->
                path in listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt", "LICENSE") ||
                    path.startsWith("espeak-ng-data/")
            },
        )

        /**
         * Orukeet v0.1.0, Oruk's multilingual Parakeet TDT v3 fine-tune (25 languages, Dutch and English
         * among them). Pinned to a Hugging Face revision; the checksum is the one Oruk publishes.
         */
        val ORUKEET = Spec(
            folder = "orukeet",
            name = "sherpa-onnx-orukeet-v0.1.0-int8",
            url = "https://huggingface.co/oruk/orukeet/resolve/55a984d46f68323301837194ce647c702f55facc/onnx/sherpa-onnx-orukeet-v0.1.0-int8.tar.bz2",
            sizeBytes = 486_807_585L,
            sha256 = "f9191f30178cc9122ce2f023bf9fefafc822028307b0efa4caff645ba3fe8d0a",
            required = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            wanted = { path ->
                path in listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "LICENSE-WEIGHTS", "NOTICE.md")
            },
            // Silero VAD tells when you start and stop talking.
            extras = listOf(
                Extra(
                    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                    fileName = OrukeetEngine.VAD_MODEL,
                    sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                ),
            ),
        )
    }
}
