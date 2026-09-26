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
 * An on-device model for sherpa-onnx (Kokoro, Orukeet, Supertonic): downloaded once, unpacked into app
 * storage, and kept across app updates.
 */
class ModelPackage(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope, private val spec: Spec) {
    /** Where a model comes from and which of its files the app needs. */
    class Spec(
        /** Folder under the app's files, and the name of the crash guard file. */
        val folder: String,
        /** Top folder inside the archive, and the installed folder's name. */
        val name: String,
        /** A tar.bz2 archive, or null when the model is only single files. */
        val url: String?,
        /** Everything downloaded, archive and single files together; drives the progress bar. */
        val sizeBytes: Long,
        /** SHA-256 of the archive when the publisher gives one. */
        val sha256: String? = null,
        val required: List<String>,
        val wanted: (String) -> Boolean = { it in required },
        /** Separate single files, such as the voice activity model next to a recognizer. */
        val extras: List<Extra> = emptyList(),
        /** Runs on the downloaded files before they're marked complete, to derive files the model needs. */
        val prepare: (File) -> Unit = {},
    )

    /** [fileName] may include a folder, such as "voice_styles/F1.json". */
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
                // Anything already here is an interrupted install or an older version of the model; clear
                // it so the disk only holds one copy.
                root.deleteRecursively()
                staging.mkdirs()
                var received = 0L
                var reported = 0L
                val progress: (Long) -> Unit = { bytes ->
                    received += bytes
                    if (received - reported > 512 * 1024) {
                        reported = received
                        _state.value = State.Downloading((received.toFloat() / spec.sizeBytes).coerceIn(0f, 0.99f))
                    }
                }
                spec.url?.let { url ->
                    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
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
                spec.extras.forEach { fetch(it, staging, progress) }
                spec.prepare(staging)
                spec.required.forEach { check(File(staging, it).exists()) { "download is missing $it" } }
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

    /** Streams one file to disk, since model files can be hundreds of megabytes. */
    private fun fetch(extra: Extra, target: File, progress: (Long) -> Unit) {
        val out = File(target, extra.fileName)
        check(out.canonicalPath.startsWith(target.canonicalPath + File.separator)) { "unsafe file name: ${extra.fileName}" }
        out.parentFile?.mkdirs()
        http.newCall(Request.Builder().url(extra.url).build()).execute().use { response ->
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

    private fun MessageDigest.hex() = digest().hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** Reports every read's byte count to [onRead]. */
    private class CountingStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) onRead(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onRead(it.toLong()) }
    }

    companion object {
        /**
         * Kokoro-82M from the sherpa-onnx release, in full precision. The int8 build is a third of the
         * size but adds a constant whine at 4.8 and 9.6 kHz and loses the harmonics above 2 kHz, which
         * makes voices sound tinny; it's also slower. English synthesis only needs part of the archive.
         * The checksum is of the archive as downloaded on 2026-09-25; the release doesn't publish one.
         */
        val KOKORO = Spec(
            folder = "kokoro",
            name = "kokoro-multi-lang-v1_0",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
            sizeBytes = 349_906_910L,
            sha256 = "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298",
            required = listOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "espeak-ng-data/phontab"),
            // The Chinese lexicons and jieba dictionary stay out.
            wanted = { path ->
                path in listOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt", "LICENSE") ||
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

        private const val SUPERTONIC_FILES = "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/aafc6e32416a594460b32413efc49d7fe4ce6d46"

        /**
         * Supertonic 3 in full precision, for Dutch: Supertone's archived release, pinned to a Hugging Face
         * revision. sherpa-onnx only packages it in int8, which sounds worse. The published JSON is turned
         * into the binary index and voice files sherpa loads; see [SupertonicFiles].
         */
        val SUPERTONIC = Spec(
            folder = "supertonic",
            name = "supertonic-3",
            url = null,
            sizeBytes = 401_291_751L,
            required = listOf(
                "duration_predictor.onnx", "text_encoder.onnx", "vector_estimator.onnx", "vocoder.onnx", "tts.json",
                SupertonicFiles.INDEXER, SupertonicFiles.VOICES,
            ),
            extras = listOf(
                Extra("$SUPERTONIC_FILES/onnx/duration_predictor.onnx", "duration_predictor.onnx", "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
                Extra("$SUPERTONIC_FILES/onnx/text_encoder.onnx", "text_encoder.onnx", "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
                Extra("$SUPERTONIC_FILES/onnx/vector_estimator.onnx", "vector_estimator.onnx", "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c"),
                Extra("$SUPERTONIC_FILES/onnx/vocoder.onnx", "vocoder.onnx", "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba"),
                Extra("$SUPERTONIC_FILES/onnx/tts.json", "tts.json", "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
                Extra("$SUPERTONIC_FILES/onnx/unicode_indexer.json", "unicode_indexer.json", "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f"),
                Extra("$SUPERTONIC_FILES/LICENSE", "LICENSE", "0d944a9110fed9a9602d60e0423a272903e7bd21ab060490774efc77c2275e9f"),
                Extra("$SUPERTONIC_FILES/voice_styles/F1.json", "${SupertonicFiles.STYLES_DIR}/F1.json", "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2"),
                Extra("$SUPERTONIC_FILES/voice_styles/F2.json", "${SupertonicFiles.STYLES_DIR}/F2.json", "7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6"),
                Extra("$SUPERTONIC_FILES/voice_styles/F3.json", "${SupertonicFiles.STYLES_DIR}/F3.json", "12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab"),
                Extra("$SUPERTONIC_FILES/voice_styles/F4.json", "${SupertonicFiles.STYLES_DIR}/F4.json", "c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b"),
                Extra("$SUPERTONIC_FILES/voice_styles/F5.json", "${SupertonicFiles.STYLES_DIR}/F5.json", "45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d"),
                Extra("$SUPERTONIC_FILES/voice_styles/M1.json", "${SupertonicFiles.STYLES_DIR}/M1.json", "e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b"),
                Extra("$SUPERTONIC_FILES/voice_styles/M2.json", "${SupertonicFiles.STYLES_DIR}/M2.json", "b76cbf62bac707c710cf0ae5aba5e31eea1a6339a9734bfae33ab98499534a50"),
                Extra("$SUPERTONIC_FILES/voice_styles/M3.json", "${SupertonicFiles.STYLES_DIR}/M3.json", "ea1ac35ccb91b0d7ecad533a2fbd0eec10c91513d8951e3b25fbba99954e159b"),
                Extra("$SUPERTONIC_FILES/voice_styles/M4.json", "${SupertonicFiles.STYLES_DIR}/M4.json", "ca8eefad4fcd989c9379032ff3e50738adc547eeb5e221b82593a6d7b3bac303"),
                Extra("$SUPERTONIC_FILES/voice_styles/M5.json", "${SupertonicFiles.STYLES_DIR}/M5.json", "dd22b92740314321f8ae11c5e87f8dd60d060f15dd3a632b5adf77f471f77af2"),
            ),
            prepare = SupertonicFiles::prepare,
        )
    }
}
