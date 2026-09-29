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
    data class Voice(val id: String, val speakerId: Int, val label: String) {
        val british: Boolean get() = id.startsWith("b")
    }

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

    override fun synthesize(tts: OfflineTts, text: String, voiceId: String, sink: Sink) {
        tts.generateWithCallback(text, voiceFor(voiceId).speakerId, 1.0f, sink)
    }

    private fun voiceFor(id: String) = VOICES.firstOrNull { it.id == id } ?: VOICES.first()

    companion object {
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
    }
}
