package nl.bartvandermeeren.dudan.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File

/**
 * Supertonic 3 speech on the phone through sherpa-onnx, in any of its 31 languages. In Dutch, numbers,
 * times and amounts are written out first, since Supertonic misreads digits. The first sentence is
 * synthesized on its own so speech starts quickly; the rest follows in larger pieces while the previous
 * one plays.
 */
class SupertonicVoice(context: Context, model: ModelPackage) : SherpaVoice(context, model, "supertonic") {
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

    override fun synthesize(tts: OfflineTts, text: String, voiceId: String, language: String, sink: Sink) {
        val config = GenerationConfig(
            silenceScale = 1.0f,
            speed = SPEED,
            sid = voiceFor(voiceId).speakerId,
            numSteps = STEPS,
            extra = mapOf("lang" to language),
        )
        val spoken = if (language == "nl") DutchText.normalize(text) else text
        SpeechText.streamingChunks(spoken, MAX_CHUNK).forEachIndexed { index, chunk ->
            if (!sink.active) return
            if (index > 0) sink.pause(PAUSE_SECONDS)
            tts.generateWithConfigAndCallback(chunk, config, sink)
        }
    }

    private fun voiceFor(id: String) = Supertonic.voice(id)

    companion object {
        const val DEFAULT = Supertonic.DEFAULT
        val VOICES = Supertonic.VOICES
        private const val STEPS = Supertonic.STEPS
        private const val SPEED = Supertonic.SPEED
        private const val PAUSE_SECONDS = Supertonic.PAUSE_SECONDS
        private const val MAX_CHUNK = Supertonic.MAX_CHUNK
    }
}
