package nl.bartvandermeeren.dudan.voice

import java.io.File

/**
 * Where an on-device sherpa-onnx model comes from and which of its files the apps need. The phone keeps
 * it with ModelPackage, the PC with ModelStore; both download the same files, so they sound the same.
 */
class ModelSpec(
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
) {
    /** [fileName] may include a folder, such as "voice_styles/F1.json". */
    class Extra(val url: String, val fileName: String, val sha256: String)
}

/** Kokoro-82M: English only, since Kokoro has no Dutch. */
object Kokoro {
    data class Voice(val id: String, val speakerId: Int, val label: String) {
        val british: Boolean get() = id.startsWith("b")
    }

    const val DEFAULT = "af_heart"

    /** English voices in the Kokoro v1.0 speaker table (ids from the model's id2speaker metadata). */
    val VOICES = listOf(
        Voice("af_heart", 3, "Heart · US"),
        Voice("af_bella", 2, "Bella · US"),
        Voice("af_nicole", 6, "Nicole · US"),
        Voice("af_sarah", 9, "Sarah · US"),
        Voice("am_michael", 16, "Michael · US"),
        Voice("am_fenrir", 14, "Fenrir · US"),
        Voice("am_puck", 18, "Puck · US"),
        Voice("bf_emma", 21, "Emma · UK"),
        Voice("bm_george", 26, "George · UK"),
        Voice("bm_fable", 25, "Fable · UK"),
    )

    fun voice(id: String?): Voice = VOICES.firstOrNull { it.id == id } ?: VOICES.first()

    /**
     * Kokoro-82M from the sherpa-onnx release, in full precision. The int8 build is a third of the
     * size but adds a constant whine at 4.8 and 9.6 kHz and loses the harmonics above 2 kHz, which
     * makes voices sound tinny; it's also slower. English synthesis only needs part of the archive.
     * The release replaced the archive on 2026-10-26 with the same files; the checksum is the one GitHub
     * publishes for that upload.
     */
    val MODEL = ModelSpec(
        folder = "kokoro",
        name = "kokoro-multi-lang-v1_0",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
        sizeBytes = 349_886_476L,
        sha256 = "60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4",
        required = listOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "espeak-ng-data/phontab"),
        // The Chinese lexicons and jieba dictionary stay out.
        wanted = { path ->
            path in listOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt", "LICENSE") ||
                path.startsWith("espeak-ng-data/")
        },
    )
}

/** Supertonic 3: 31 languages, Dutch and English among them. */
object Supertonic {
    data class Voice(val id: String, val speakerId: Int, val female: Boolean, val number: Int)

    const val DEFAULT = "M2"

    /** The ten preset styles, in voice.bin order (the style files sorted by name). */
    val VOICES = (1..5).map { Voice("F$it", it - 1, female = true, number = it) } +
        (1..5).map { Voice("M$it", it + 4, female = false, number = it) }

    fun voice(id: String?): Voice = VOICES.firstOrNull { it.id == id } ?: VOICES.first()

    /** Supertonic 3's languages, from its README. */
    val LANGUAGES = setOf(
        "ar", "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it",
        "ja", "ko", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
    )

    /** Denoising steps. Naturalness rises steeply up to 8 and barely after; each step costs time. */
    const val STEPS = 8
    /** Supertonic's own default pace. */
    const val SPEED = 1.05f
    const val PAUSE_SECONDS = 0.3f
    /** Longer pieces read more naturally but take longer before they play. */
    const val MAX_CHUNK = 200

    private const val FILES = "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/aafc6e32416a594460b32413efc49d7fe4ce6d46"

    /**
     * Supertonic 3 in full precision: Supertone's archived release, pinned to a Hugging Face revision.
     * sherpa-onnx only packages it in int8, which sounds worse. The published JSON is turned into the
     * binary index and voice files sherpa loads; see [SupertonicFiles].
     */
    val MODEL = ModelSpec(
        folder = "supertonic",
        name = "supertonic-3",
        url = null,
        sizeBytes = 401_291_751L,
        required = listOf(
            "duration_predictor.onnx", "text_encoder.onnx", "vector_estimator.onnx", "vocoder.onnx", "tts.json",
            SupertonicFiles.INDEXER, SupertonicFiles.VOICES,
        ),
        extras = listOf(
            ModelSpec.Extra("$FILES/onnx/duration_predictor.onnx", "duration_predictor.onnx", "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
            ModelSpec.Extra("$FILES/onnx/text_encoder.onnx", "text_encoder.onnx", "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
            ModelSpec.Extra("$FILES/onnx/vector_estimator.onnx", "vector_estimator.onnx", "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c"),
            ModelSpec.Extra("$FILES/onnx/vocoder.onnx", "vocoder.onnx", "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba"),
            ModelSpec.Extra("$FILES/onnx/tts.json", "tts.json", "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
            ModelSpec.Extra("$FILES/onnx/unicode_indexer.json", "unicode_indexer.json", "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f"),
            ModelSpec.Extra("$FILES/LICENSE", "LICENSE", "0d944a9110fed9a9602d60e0423a272903e7bd21ab060490774efc77c2275e9f"),
            ModelSpec.Extra("$FILES/voice_styles/F1.json", "${SupertonicFiles.STYLES_DIR}/F1.json", "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2"),
            ModelSpec.Extra("$FILES/voice_styles/F2.json", "${SupertonicFiles.STYLES_DIR}/F2.json", "7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6"),
            ModelSpec.Extra("$FILES/voice_styles/F3.json", "${SupertonicFiles.STYLES_DIR}/F3.json", "12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab"),
            ModelSpec.Extra("$FILES/voice_styles/F4.json", "${SupertonicFiles.STYLES_DIR}/F4.json", "c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b"),
            ModelSpec.Extra("$FILES/voice_styles/F5.json", "${SupertonicFiles.STYLES_DIR}/F5.json", "45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d"),
            ModelSpec.Extra("$FILES/voice_styles/M1.json", "${SupertonicFiles.STYLES_DIR}/M1.json", "e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b"),
            ModelSpec.Extra("$FILES/voice_styles/M2.json", "${SupertonicFiles.STYLES_DIR}/M2.json", "b76cbf62bac707c710cf0ae5aba5e31eea1a6339a9734bfae33ab98499534a50"),
            ModelSpec.Extra("$FILES/voice_styles/M3.json", "${SupertonicFiles.STYLES_DIR}/M3.json", "ea1ac35ccb91b0d7ecad533a2fbd0eec10c91513d8951e3b25fbba99954e159b"),
            ModelSpec.Extra("$FILES/voice_styles/M4.json", "${SupertonicFiles.STYLES_DIR}/M4.json", "ca8eefad4fcd989c9379032ff3e50738adc547eeb5e221b82593a6d7b3bac303"),
            ModelSpec.Extra("$FILES/voice_styles/M5.json", "${SupertonicFiles.STYLES_DIR}/M5.json", "dd22b92740314321f8ae11c5e87f8dd60d060f15dd3a632b5adf77f471f77af2"),
        ),
        prepare = SupertonicFiles::prepare,
    )
}
