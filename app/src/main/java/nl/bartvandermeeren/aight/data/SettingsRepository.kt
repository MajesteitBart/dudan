package nl.bartvandermeeren.aight.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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

/** Saved in place of an effort when the user leaves it to Hermes. */
private const val HERMES_EFFORT = "default"

/**
 * The effort saved in [saved]. Before 0.6.3 the app had one Default/Fast/Extended setting that only
 * ever set the effort; [legacy] carries that over. Nothing saved at all means [default].
 */
internal fun storedEffort(saved: String?, legacy: String?, default: ReasoningEffort?): ReasoningEffort? = when {
    saved == HERMES_EFFORT -> null
    saved != null -> ReasoningEffort.fromWire(saved) ?: default
    legacy == "Default" -> null
    legacy == "Fast" -> ReasoningEffort.Low
    legacy == "Extended" -> ReasoningEffort.High
    else -> default
}

/** Which default model a chat uses: regular chats, or chats started from the assistant (side key, overlay). */
enum class ModelProfile { Chats, Assistant }

/** How replies are read aloud. Kokoro runs on the phone; System is Android's text-to-speech. */
enum class TtsEngine { Kokoro, System }

/** How Dutch replies are read aloud. Supertonic runs on the phone; System is Android's text-to-speech. */
enum class DutchTtsEngine { Supertonic, System }

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
    val ttsEngine: TtsEngine = TtsEngine.Kokoro,
    val kokoroVoice: String = DEFAULT_KOKORO_VOICE,
    val dutchTtsEngine: DutchTtsEngine = DutchTtsEngine.Supertonic,
    val supertonicVoice: String = DEFAULT_SUPERTONIC_VOICE,
    val sttEngine: SttEngine = SttEngine.Orukeet,
    /** Hermes may act on this phone through its MCP server; see device/PhoneControl. */
    val phoneControl: Boolean = false,
    /** The bearer token Hermes sends to that server. Created when phone control is first turned on. */
    val phoneToken: String = "",
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
    val server: HermesApi.ServerConfig get() = HermesApi.ServerConfig(serverUrl, apiKey)

    fun modelFor(profile: ModelProfile): ModelChoice = when (profile) {
        ModelProfile.Chats -> model
        ModelProfile.Assistant -> assistantModel
    }

    companion object {
        const val DEFAULT_ASSISTANT_NAME = "aight"
        const val DEFAULT_KOKORO_VOICE = "af_heart"
        const val DEFAULT_SUPERTONIC_VOICE = "M2"
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private class ModelKeys(prefix: String) {
        val provider = stringPreferencesKey("${prefix}model_provider")
        val id = stringPreferencesKey("${prefix}model_id")
        val label = stringPreferencesKey("${prefix}model_label")
        val effort = stringPreferencesKey("${prefix}effort")
        val fast = booleanPreferencesKey("${prefix}fast")
        val legacyReasoning = stringPreferencesKey("${prefix}reasoning")
    }

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val apiKey = stringPreferencesKey("api_key_enc")
        val userName = stringPreferencesKey("user_name")
        val assistantName = stringPreferencesKey("assistant_name")
        val listenOnInvoke = booleanPreferencesKey("listen_on_invoke")
        val speakReplies = booleanPreferencesKey("speak_replies")
        val showAllChannels = booleanPreferencesKey("show_all_channels")
        val reduceTransparency = booleanPreferencesKey("reduce_transparency")
        val accent = stringPreferencesKey("accent")
        val sky = stringPreferencesKey("sky")
        val speechLanguage = stringPreferencesKey("speech_language")
        val chatsModel = ModelKeys("")
        val assistantModel = ModelKeys("fast_")
        val ttsEngine = stringPreferencesKey("tts_engine")
        val kokoroVoice = stringPreferencesKey("kokoro_voice")
        val dutchTtsEngine = stringPreferencesKey("dutch_tts_engine")
        val supertonicVoice = stringPreferencesKey("supertonic_voice")
        val sttEngine = stringPreferencesKey("stt_engine")
        val phoneControl = booleanPreferencesKey("phone_control")
        val phoneToken = stringPreferencesKey("phone_token_enc")
    }

    private fun keysFor(profile: ModelProfile) = when (profile) {
        ModelProfile.Chats -> Keys.chatsModel
        ModelProfile.Assistant -> Keys.assistantModel
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun current(): AppSettings = flow.first()

    private fun Preferences.readModel(keys: ModelKeys, defaultEffort: ReasoningEffort?) = ModelChoice(
        provider = this[keys.provider],
        model = this[keys.id],
        label = this[keys.label],
        effort = storedEffort(this[keys.effort], this[keys.legacyReasoning], defaultEffort),
        fast = this[keys.fast] ?: false,
    )

    private fun Preferences.toSettings() = AppSettings(
        serverUrl = this[Keys.serverUrl].orEmpty(),
        apiKey = this[Keys.apiKey]?.let(SecretBox::decrypt).orEmpty(),
        userName = this[Keys.userName].orEmpty(),
        assistantName = this[Keys.assistantName]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_ASSISTANT_NAME,
        listenOnInvoke = this[Keys.listenOnInvoke] ?: true,
        speakReplies = this[Keys.speakReplies] ?: true,
        showAllChannels = this[Keys.showAllChannels] ?: false,
        reduceTransparency = this[Keys.reduceTransparency] ?: false,
        accent = this[Keys.accent] ?: "Moon",
        sky = this[Keys.sky] ?: "Dusk",
        speechLanguage = this[Keys.speechLanguage].orEmpty(),
        model = readModel(Keys.chatsModel, null),
        assistantModel = readModel(Keys.assistantModel, ReasoningEffort.Low),
        ttsEngine = this[Keys.ttsEngine]?.let { runCatching { TtsEngine.valueOf(it) }.getOrNull() } ?: TtsEngine.Kokoro,
        kokoroVoice = this[Keys.kokoroVoice]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_KOKORO_VOICE,
        dutchTtsEngine = this[Keys.dutchTtsEngine]?.let { runCatching { DutchTtsEngine.valueOf(it) }.getOrNull() } ?: DutchTtsEngine.Supertonic,
        supertonicVoice = this[Keys.supertonicVoice]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_SUPERTONIC_VOICE,
        sttEngine = this[Keys.sttEngine]?.let { runCatching { SttEngine.valueOf(it) }.getOrNull() } ?: SttEngine.Orukeet,
        phoneControl = this[Keys.phoneControl] ?: false,
        phoneToken = this[Keys.phoneToken]?.let(SecretBox::decrypt).orEmpty(),
    )

    /**
     * Saves everything from the setup form in one edit. Saving the connection flips the app out of
     * setup mode, which disposes the form's coroutine scope, so separate writes after it would be lost.
     */
    suspend fun saveSetup(serverUrl: String, apiKey: String, userName: String, assistantName: String, speechLanguage: String) {
        context.dataStore.edit {
            it[Keys.userName] = userName.trim()
            it[Keys.assistantName] = assistantName.trim()
            it[Keys.speechLanguage] = speechLanguage.trim()
            val url = HermesApi.normalizeBaseUrl(serverUrl)
            if (it[Keys.serverUrl] != url) {
                // Models picked on another server may not exist on this one, nor their thinking level or fast mode.
                ModelProfile.entries.forEach { profile -> it.clearModel(keysFor(profile)) }
            }
            it[Keys.serverUrl] = url
            it[Keys.apiKey] = SecretBox.encrypt(apiKey.trim())
        }
    }

    private fun MutablePreferences.clearModel(keys: ModelKeys) {
        remove(keys.provider)
        remove(keys.id)
        remove(keys.label)
        remove(keys.effort)
        remove(keys.fast)
        remove(keys.legacyReasoning)
    }

    suspend fun setListenOnInvoke(value: Boolean) = context.dataStore.edit { it[Keys.listenOnInvoke] = value }
    suspend fun setSpeakReplies(value: Boolean) = context.dataStore.edit { it[Keys.speakReplies] = value }
    suspend fun setShowAllChannels(value: Boolean) = context.dataStore.edit { it[Keys.showAllChannels] = value }
    suspend fun setReduceTransparency(value: Boolean) = context.dataStore.edit { it[Keys.reduceTransparency] = value }
    suspend fun setAccent(name: String) = context.dataStore.edit { it[Keys.accent] = name }
    suspend fun setSky(name: String) = context.dataStore.edit { it[Keys.sky] = name }
    suspend fun setSpeechLanguage(value: String) = context.dataStore.edit { it[Keys.speechLanguage] = value.trim() }
    suspend fun setTtsEngine(value: TtsEngine) = context.dataStore.edit { it[Keys.ttsEngine] = value.name }
    suspend fun setKokoroVoice(value: String) = context.dataStore.edit { it[Keys.kokoroVoice] = value }
    suspend fun setDutchTtsEngine(value: DutchTtsEngine) = context.dataStore.edit { it[Keys.dutchTtsEngine] = value.name }
    suspend fun setSupertonicVoice(value: String) = context.dataStore.edit { it[Keys.supertonicVoice] = value }
    suspend fun setSttEngine(value: SttEngine) = context.dataStore.edit { it[Keys.sttEngine] = value.name }

    suspend fun setPhoneControl(enabled: Boolean) = context.dataStore.edit {
        it[Keys.phoneControl] = enabled
        if (enabled && it[Keys.phoneToken]?.let(SecretBox::decrypt).isNullOrBlank()) it[Keys.phoneToken] = SecretBox.encrypt(newToken())
    }

    /** Replaces the token; Hermes can't reach the phone until it has the new one. */
    suspend fun renewPhoneToken() = context.dataStore.edit { it[Keys.phoneToken] = SecretBox.encrypt(newToken()) }

    private fun newToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    suspend fun setModel(profile: ModelProfile, choice: ModelChoice) {
        val keys = keysFor(profile)
        context.dataStore.edit { prefs ->
            fun put(key: Preferences.Key<String>, value: String?) {
                if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
            }
            put(keys.provider, choice.provider)
            put(keys.id, choice.model)
            put(keys.label, choice.label)
            prefs[keys.effort] = choice.effort?.wire ?: HERMES_EFFORT
            prefs[keys.fast] = choice.fast
            prefs.remove(keys.legacyReasoning)
        }
    }
}
