package nl.bartvandermeeren.aight.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File

/**
 * Supertonic 3 speech on the phone through sherpa-onnx, for Dutch replies. Numbers, times and amounts
 * are written out first, since Supertonic misreads digits. The first sentence is synthesized on its
 * own so speech starts quickly; the rest follows in larger pieces while the previous one plays.
 */
class SupertonicVoice(context: Context, model: ModelPackage) : SherpaVoice(context, model, "supertonic") {
    data class Voice(val id: String, val speakerId: Int, val female: Boolean, val number: Int)

    // One engine holds every voice; the voice is picked per call.
    override fun engineKey(voiceId: String): Any = Unit

    override fun loadEngine(voiceId: String): OfflineTts {
        fun path(name: String) = File(model.dir, name).absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = path("duration_predictor.onnx"),
                    textEncoder = path("text_encoder.onnx"),
                    vectorEstimator = path("vector_estimator.onnx"),
                    vocoder = path("vocoder.onnx"),
                    ttsJson = path("tts.json"),
                    unicodeIndexer = path(SupertonicFiles.INDEXER),
                    voiceStyle = path(SupertonicFiles.VOICES),
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu",
            ),
            silenceScale = 1.0f,
        )
        return OfflineTts(config = config)
    }

    override fun synthesize(tts: OfflineTts, text: String, voiceId: String, sink: Sink) {
        val config = GenerationConfig(
            silenceScale = 1.0f,
            speed = SPEED,
            sid = voiceFor(voiceId).speakerId,
            numSteps = STEPS,
            extra = mapOf("lang" to "nl"),
        )
        SpeechText.streamingChunks(DutchText.normalize(text), MAX_CHUNK).forEachIndexed { index, chunk ->
            if (!sink.active) return
            if (index > 0) sink.pause(PAUSE_SECONDS)
            tts.generateWithConfigAndCallback(chunk, config, sink)
        }
    }

    private fun voiceFor(id: String) = VOICES.firstOrNull { it.id == id } ?: VOICES.first()

    companion object {
        /** Denoising steps. Naturalness rises steeply up to 8 and barely after; each step costs time. */
        private const val STEPS = 8
        /** Supertonic's own default pace. */
        private const val SPEED = 1.05f
        private const val PAUSE_SECONDS = 0.3f
        /** Longer pieces read more naturally but take longer before they play. */
        private const val MAX_CHUNK = 200

        /** The ten preset styles, in voice.bin order (the style files sorted by name). */
        val VOICES = (1..5).map { Voice("F$it", it - 1, female = true, number = it) } +
            (1..5).map { Voice("M$it", it + 4, female = false, number = it) }
    }
}
