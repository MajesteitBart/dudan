package nl.bartvandermeeren.dudan.data

import nl.bartvandermeeren.dudan.voice.Endpointer
import nl.bartvandermeeren.dudan.voice.WakePhrase

/** Reasoning efforts understood by the model picker and the runs API. */
enum class ReasoningEffort(val wire: String) {
    Off("none"), Minimal("minimal"), Low("low"), Medium("medium"), High("high"), ExtraHigh("xhigh"), Max("max"), Ultra("ultra");

    companion object {
        val legacy = listOf(Off, Low, Medium, High, ExtraHigh, Max)
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
    /** Provider service tier ID. Null selects standard processing. */
    val serviceTier: String? = null,
)

/** Both harnesses expose the Runs API. The assistant name identifies the agent inside the harness. */
enum class Harness(val defaultUrl: String, val exampleUrl: String = defaultUrl) {
    Booper("http://100.91.52.84:8652"),
    Hermes("", "http://my-server:8642");

    val defaultAssistantName: String get() = if (this == Booper) AppSettings.DEFAULT_ASSISTANT_NAME else name

    companion object {
        val Default = Booper
        fun fromName(name: String?) = entries.find { it.name == name } ?: Default
    }
}

data class HarnessConnection(val url: String, val key: String, val assistant: String)

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
    /** The upload service for attachments; empty uses the built-in Booper endpoint or the Hermes sidecar. */
    val uploadUrl: String = "",
    val harness: Harness = Harness.Default,
    val otherConnections: Map<Harness, HarnessConnection> = emptyMap(),
) {
    fun connectionFor(selected: Harness): HarnessConnection {
        if (selected == harness) return HarnessConnection(serverUrl.ifBlank { selected.defaultUrl }, apiKey, assistantName)
        return otherConnections[selected] ?: HarnessConnection(selected.defaultUrl, "", selected.defaultAssistantName)
    }

    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
    val server: HermesApi.ServerConfig get() = HermesApi.ServerConfig(serverUrl, apiKey)

    /** Uploads take the active connection’s API key. */
    val uploads: UploadClient.Config get() = UploadClient.Config(UploadClient.baseUrlFor(serverUrl, uploadUrl, harness), apiKey)

    fun modelFor(profile: ModelProfile): ModelChoice = when (profile) {
        ModelProfile.Chats -> model
        ModelProfile.Assistant -> assistantModel
    }

    companion object {
        const val DEFAULT_ASSISTANT_NAME = "Clark"
    }
}
