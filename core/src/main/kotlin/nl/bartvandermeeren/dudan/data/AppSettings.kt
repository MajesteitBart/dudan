package nl.bartvandermeeren.dudan.data

import nl.bartvandermeeren.dudan.voice.Endpointer
import nl.bartvandermeeren.dudan.voice.WakePhrase

/**
 * Hermes' `reasoning_effort` levels the picker offers, weakest first. A level the model doesn't
 * support is lowered by Hermes to the nearest one below it, or to the model's weakest level.
 */
enum class ReasoningEffort(val wire: String) {
    Off("none"), Low("low"), Medium("medium"), High("high"), ExtraHigh("xhigh"), Max("max");

    companion object {
        fun fromWire(value: String?): ReasoningEffort? = entries.firstOrNull { it.wire == value }
    }
}

/** Which default model a chat uses: regular chats, or chats started from the assistant (side key, overlay). */
enum class ModelProfile { Chats, Assistant }

/** Who reads replies aloud. Kokoro and Supertonic run on the phone; System is Android's text-to-speech. */
enum class VoiceEngine { Kokoro, Supertonic, System }

/**
 * The voice for one language's replies. A null [engine] is Automatic: the best voice the phone has for
 * that language (see voice/VoiceLanguages). A null [voice], or one the engine doesn't have, is the
 * engine's default voice.
 */
data class LanguageVoice(val engine: VoiceEngine? = null, val voice: String? = null) {
    val isDefault: Boolean get() = engine == null && voice == null
}

/** Who turns speech into text. Orukeet runs on the phone; System is Android's speech recognizer. */
enum class SttEngine { Orukeet, System }

/** A model the user picked in the model sheet. Null model means "whatever Hermes is configured to use". */
data class ModelChoice(
    val provider: String? = null,
    val model: String? = null,
    val label: String? = null,
    /** Null leaves the effort to Hermes' own setting. */
    val effort: ReasoningEffort? = null,
    /** Priority processing (Hermes' `fast` option). The picker only offers it for models that report it. */
    val fast: Boolean = false,
)

data class AppSettings(
    val serverUrl: String = "",
    val apiKey: String = "",
    val userName: String = "",
    val assistantName: String = DEFAULT_ASSISTANT_NAME,
    val listenOnInvoke: Boolean = true,
    /** Open the assistant by saying [wakePhrase]; see voice/WakeWordListener. */
    val wakeWord: Boolean = false,
    val wakePhrase: String = WakePhrase.DEFAULT,
    val speakReplies: Boolean = true,
    val showAllChannels: Boolean = false,
    /** Solid panels instead of frosted glass (see LocalReduceTransparency). */
    val reduceTransparency: Boolean = false,
    /** The name of the accent color (ui.theme.Accent). */
    val accent: String = "Moon",
    /** The name of the background (ui.theme.Sky). */
    val sky: String = "Dusk",
    val speechLanguage: String = "",
    /** Default for chats started in the app. */
    val model: ModelChoice = ModelChoice(),
    /** Default for chats started from the assistant; quick answers matter more there. */
    val assistantModel: ModelChoice = ModelChoice(effort = ReasoningEffort.Low),
    /** Voices by language code ("en", "nl", or VoiceLanguages.OTHER); a language left out is Automatic. */
    val voices: Map<String, LanguageVoice> = emptyMap(),
    val sttEngine: SttEngine = SttEngine.Orukeet,
    /** How long the user can pause before voice input decides they're done. */
    val speechPauseMs: Int = Endpointer.DEFAULT_END_SILENCE_MS,
    /** Hermes may act on this phone through its MCP server; see device/PhoneControl. */
    val phoneControl: Boolean = false,
    /** The bearer token Hermes sends to that server. Created when phone control is first turned on. */
    val phoneToken: String = "",
    /** Ask Hermes for OpenUI blocks (tables, charts, forms) in replies; see openui/OpenUiPrompt. */
    val richReplies: Boolean = true,
    /** The upload service for attachments; empty means the Hermes host on port 8645. */
    val uploadUrl: String = "",
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
    val server: HermesApi.ServerConfig get() = HermesApi.ServerConfig(serverUrl, apiKey)

    /** The upload service runs next to Hermes and takes the same API key. */
    val uploads: UploadClient.Config get() = UploadClient.Config(UploadClient.baseUrlFor(serverUrl, uploadUrl), apiKey)

    fun modelFor(profile: ModelProfile): ModelChoice = when (profile) {
        ModelProfile.Chats -> model
        ModelProfile.Assistant -> assistantModel
    }

    companion object {
        const val DEFAULT_ASSISTANT_NAME = "dudan"
    }
}
