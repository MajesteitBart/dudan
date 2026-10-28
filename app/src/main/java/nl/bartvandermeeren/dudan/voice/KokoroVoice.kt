package nl.bartvandermeeren.dudan.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * Kokoro-82M speech on the phone through sherpa-onnx, streamed sentence by sentence. English only:
 * Kokoro has no Dutch.
 */
class KokoroVoice(context: Context, model: ModelPackage) : SherpaVoice(context, model, "kokoro") {
    // British and American voices need different lexicons, so they load different engines.
    override fun engineKey(voiceId: String): Any = voiceFor(voiceId).british

    override fun loadEngine(voiceId: String): OfflineTts {
        val voice = voiceFor(voiceId)
        fun path(name: String) = File(model.dir, name).absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = path("model.onnx"),
                    voices = path("voices.bin"),
                    tokens = path("tokens.txt"),
                    dataDir = path("espeak-ng-data"),
                    lexicon = path(if (voice.british) "lexicon-gb-en.txt" else "lexicon-us-en.txt"),
                    lang = if (voice.british) "en-gb" else "en-us",
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
            // sherpa cuts every pause over 0.2 s to a fifth by default, which makes Kokoro sound rushed.
            // 1.0 keeps the pauses the model makes, as in the reference implementation.
            silenceScale = 1.0f,
        )
        return OfflineTts(config = config)
    }

    override fun synthesize(tts: OfflineTts, text: String, voiceId: String, language: String, sink: Sink) {
        tts.generateWithCallback(text, voiceFor(voiceId).speakerId, 1.0f, sink)
    }

    private fun voiceFor(id: String) = Kokoro.voice(id)

    companion object {
        const val DEFAULT = Kokoro.DEFAULT
        val VOICES = Kokoro.VOICES
    }
}
