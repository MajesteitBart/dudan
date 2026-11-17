package nl.bartvandermeeren.dudan.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import nl.bartvandermeeren.dudan.voice.Endpointer
import nl.bartvandermeeren.dudan.voice.WakePhrase

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

/** Language voices saved as "en:Kokoro:am_fenrir,nl::M1"; an empty field is null. */
internal fun encodeVoices(voices: Map<String, LanguageVoice>): String = voices
    .filterValues { !it.isDefault }
    .entries.joinToString(",") { (language, v) -> "$language:${v.engine?.name.orEmpty()}:${v.voice.orEmpty()}" }

internal fun decodeVoices(raw: String): Map<String, LanguageVoice> = raw.split(',').mapNotNull { entry ->
    val parts = entry.split(':')
    if (parts.size != 3 || parts[0].isBlank()) return@mapNotNull null
    val engine = parts[1].takeIf { it.isNotEmpty() }?.let { name -> VoiceEngine.entries.firstOrNull { it.name == name } }
    parts[0] to LanguageVoice(engine, parts[2].takeIf { it.isNotEmpty() })
}.toMap().filterValues { !it.isDefault }

/**
 * Before voices were set per language there was an English voice (Kokoro or Android) and a Dutch one
 * (Supertonic or Android). Kokoro and Supertonic were the defaults, so they become Automatic; a picked
 * voice or the Android voice carries over.
 */
internal fun legacyVoices(englishEngine: String?, kokoroVoice: String?, dutchEngine: String?, supertonicVoice: String?): Map<String, LanguageVoice> = mapOf(
    "en" to LanguageVoice(VoiceEngine.System.takeIf { englishEngine == "System" }, kokoroVoice?.takeIf { it.isNotBlank() }),
    "nl" to LanguageVoice(VoiceEngine.System.takeIf { dutchEngine == "System" }, supertonicVoice?.takeIf { it.isNotBlank() }),
).filterValues { !it.isDefault }

// The default preset keeps the original preference keys so existing installations keep their connection.
private class ProfileKeys(harness: Harness) {
    private val prefix = if (harness == Harness.Default) "" else "${harness.name}_"
    val url = stringPreferencesKey("${prefix}server_url")
    val key = stringPreferencesKey("${prefix}api_key_enc")
    val assistant = stringPreferencesKey("${prefix}assistant_name")
    val upload = stringPreferencesKey("${prefix}upload_url")
}

private val harnessKey = stringPreferencesKey("harness")

internal object LegacyHarnessMigration : DataMigration<Preferences> {
    private val legacy = ProfileKeys(Harness.Booper)

    override suspend fun shouldMigrate(currentData: Preferences) =
        currentData[harnessKey] == null && !currentData[legacy.url].isNullOrBlank()

    override suspend fun migrate(currentData: Preferences): Preferences {
        val harness = Harness.fromLegacyUrl(currentData[legacy.url])
        return currentData.toMutablePreferences().apply {
            this[harnessKey] = harness.name
            if (harness == Harness.Hermes) {
                val target = ProfileKeys(harness)
                fun move(from: Preferences.Key<String>, to: Preferences.Key<String>) {
                    remove(from)?.let { this[to] = it }
                }
                move(legacy.url, target.url)
                move(legacy.key, target.key)
                move(legacy.assistant, target.assistant)
                move(legacy.upload, target.upload)
            }
        }
    }

    override suspend fun cleanUp() = Unit
}

private val Context.dataStore by preferencesDataStore(name = "settings", produceMigrations = { listOf(LegacyHarnessMigration) })

class SettingsRepository(private val context: Context) {
    private class ModelKeys(prefix: String) {
        val provider = stringPreferencesKey("${prefix}model_provider")
        val id = stringPreferencesKey("${prefix}model_id")
        val label = stringPreferencesKey("${prefix}model_label")
        val effort = stringPreferencesKey("${prefix}effort")
        val serviceTier = stringPreferencesKey("${prefix}service_tier")
        val fast = booleanPreferencesKey("${prefix}fast")
        val legacyReasoning = stringPreferencesKey("${prefix}reasoning")
    }

    private object Keys {
        val userName = stringPreferencesKey("user_name")
        val listenOnInvoke = booleanPreferencesKey("listen_on_invoke")
        val wakeWord = booleanPreferencesKey("wake_word")
        val wakePhrase = stringPreferencesKey("wake_phrase")
        val speakReplies = booleanPreferencesKey("speak_replies")
        val showAllChannels = booleanPreferencesKey("show_all_channels")
        val reduceTransparency = booleanPreferencesKey("reduce_transparency")
        val accent = stringPreferencesKey("accent")
        val sky = stringPreferencesKey("sky")
        val speechLanguage = stringPreferencesKey("speech_language")
        val chatsModel = ModelKeys("")
        val assistantModel = ModelKeys("fast_")
        val voices = stringPreferencesKey("language_voices")
        val legacyTtsEngine = stringPreferencesKey("tts_engine")
        val legacyKokoroVoice = stringPreferencesKey("kokoro_voice")
        val legacyDutchTtsEngine = stringPreferencesKey("dutch_tts_engine")
        val legacySupertonicVoice = stringPreferencesKey("supertonic_voice")
        val sttEngine = stringPreferencesKey("stt_engine")
        val speechPauseMs = intPreferencesKey("speech_pause_ms")
        val phoneControl = booleanPreferencesKey("phone_control")
        val phoneToken = stringPreferencesKey("phone_token_enc")
        val richReplies = booleanPreferencesKey("rich_replies")
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
        serviceTier = this[keys.serviceTier] ?: "priority".takeIf { this[keys.fast] == true },
    )

    private fun Preferences.readVoices(): Map<String, LanguageVoice> = this[Keys.voices]?.let(::decodeVoices)
        ?: legacyVoices(this[Keys.legacyTtsEngine], this[Keys.legacyKokoroVoice], this[Keys.legacyDutchTtsEngine], this[Keys.legacySupertonicVoice])

    private fun Preferences.connection(harness: Harness): HarnessConnection {
        val keys = ProfileKeys(harness)
        return HarnessConnection(
            this[keys.url].orEmpty(),
            this[keys.key]?.let(SecretBox::decrypt).orEmpty(),
            this[keys.assistant]?.takeIf { it.isNotBlank() && it != "aight" } ?: harness.defaultAssistantName,
        )
    }

    private fun Preferences.toSettings(): AppSettings {
        val harness = Harness.fromName(this[harnessKey])
        val active = connection(harness)
        return AppSettings(
            harness = harness,
            otherConnections = Harness.entries.filter { it != harness && this[ProfileKeys(it).url] != null }.associateWith { connection(it) },
            serverUrl = active.url,
            apiKey = active.key,
            userName = this[Keys.userName].orEmpty(),
            assistantName = active.assistant,
            listenOnInvoke = this[Keys.listenOnInvoke] ?: true,
            wakeWord = this[Keys.wakeWord] ?: false,
            wakePhrase = this[Keys.wakePhrase]?.takeIf { it.isNotBlank() } ?: WakePhrase.DEFAULT,
            speakReplies = this[Keys.speakReplies] ?: true,
            showAllChannels = this[Keys.showAllChannels] ?: false,
            reduceTransparency = this[Keys.reduceTransparency] ?: false,
            accent = this[Keys.accent] ?: "Moon",
            sky = this[Keys.sky] ?: "Dusk",
            speechLanguage = this[Keys.speechLanguage].orEmpty(),
            model = readModel(Keys.chatsModel, null),
            assistantModel = readModel(Keys.assistantModel, ReasoningEffort.Low),
            voices = readVoices(),
            sttEngine = this[Keys.sttEngine]?.let { runCatching { SttEngine.valueOf(it) }.getOrNull() } ?: SttEngine.Orukeet,
            speechPauseMs = this[Keys.speechPauseMs]?.coerceIn(Endpointer.END_SILENCE_RANGE_MS) ?: Endpointer.DEFAULT_END_SILENCE_MS,
            phoneControl = this[Keys.phoneControl] ?: false,
            phoneToken = this[Keys.phoneToken]?.let(SecretBox::decrypt).orEmpty(),
            richReplies = this[Keys.richReplies] ?: true,
            uploadUrl = this[ProfileKeys(harness).upload].orEmpty(),
        )
    }

    /**
     * Saves everything from the setup form in one edit. Saving the connection flips the app out of
     * setup mode, which disposes the form's coroutine scope, so separate writes after it would be lost.
     */
    suspend fun saveSetup(serverUrl: String, apiKey: String, userName: String, assistantName: String, speechLanguage: String, harness: Harness) {
        context.dataStore.edit {
            val url = HermesApi.normalizeBaseUrl(serverUrl)
            val previous = Harness.fromName(it[harnessKey])
            val keys = ProfileKeys(harness)
            if (it[keys.url] != url) it.remove(keys.upload)
            it[harnessKey] = harness.name
            it[Keys.userName] = userName.trim()
            it[keys.assistant] = assistantName.trim()
            it[Keys.speechLanguage] = speechLanguage.trim()
            if (previous != harness || it[keys.url] != url) {
                // Models picked on another server may not exist on this one, nor their thinking level or fast mode.
                ModelProfile.entries.forEach { profile -> it.clearModel(keysFor(profile)) }
            }
            it[keys.url] = url
            it[keys.key] = SecretBox.encrypt(apiKey.trim())
        }
    }

    private fun MutablePreferences.clearModel(keys: ModelKeys) {
        remove(keys.provider)
        remove(keys.id)
        remove(keys.label)
        remove(keys.effort)
        remove(keys.serviceTier)
        remove(keys.fast)
        remove(keys.legacyReasoning)
    }

    suspend fun setListenOnInvoke(value: Boolean) = context.dataStore.edit { it[Keys.listenOnInvoke] = value }
    suspend fun setWakeWord(value: Boolean) = context.dataStore.edit { it[Keys.wakeWord] = value }
    suspend fun setWakePhrase(value: String) = context.dataStore.edit { it[Keys.wakePhrase] = value.trim() }
    suspend fun setSpeakReplies(value: Boolean) = context.dataStore.edit { it[Keys.speakReplies] = value }
    suspend fun setShowAllChannels(value: Boolean) = context.dataStore.edit { it[Keys.showAllChannels] = value }
    suspend fun setReduceTransparency(value: Boolean) = context.dataStore.edit { it[Keys.reduceTransparency] = value }
    suspend fun setAccent(name: String) = context.dataStore.edit { it[Keys.accent] = name }
    suspend fun setSky(name: String) = context.dataStore.edit { it[Keys.sky] = name }
    suspend fun setSpeechLanguage(value: String) = context.dataStore.edit { it[Keys.speechLanguage] = value.trim() }

    /** Saves [language]'s voice; Automatic removes its entry. The old per-engine keys go on the first save. */
    suspend fun setVoice(language: String, voice: LanguageVoice) = context.dataStore.edit {
        val voices = it.readVoices().toMutableMap()
        if (voice.isDefault) voices.remove(language) else voices[language] = voice
        it[Keys.voices] = encodeVoices(voices)
        it.remove(Keys.legacyTtsEngine)
        it.remove(Keys.legacyKokoroVoice)
        it.remove(Keys.legacyDutchTtsEngine)
        it.remove(Keys.legacySupertonicVoice)
    }
    suspend fun setSttEngine(value: SttEngine) = context.dataStore.edit { it[Keys.sttEngine] = value.name }
    suspend fun setSpeechPause(ms: Int) = context.dataStore.edit { it[Keys.speechPauseMs] = ms.coerceIn(Endpointer.END_SILENCE_RANGE_MS) }
    suspend fun setRichReplies(value: Boolean) = context.dataStore.edit { it[Keys.richReplies] = value }
    suspend fun setUploadUrl(value: String) = context.dataStore.edit {
        val url = value.trim()
        val upload = ProfileKeys(Harness.fromName(it[harnessKey])).upload
        if (url.isEmpty()) it.remove(upload) else it[upload] = HermesApi.normalizeBaseUrl(url)
    }

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
            prefs.remove(keys.fast)
            put(keys.serviceTier, choice.serviceTier)
            prefs.remove(keys.legacyReasoning)
        }
    }
}
