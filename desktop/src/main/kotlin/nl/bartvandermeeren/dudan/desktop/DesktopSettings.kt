package nl.bartvandermeeren.dudan.desktop

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.ModelChoice
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.data.ReasoningEffort
import nl.bartvandermeeren.dudan.voice.Kokoro
import nl.bartvandermeeren.dudan.voice.Supertonic

/** How the chat list orders the chats in each of its groups. Pinned chats come first in all of them. */
enum class ThreadSort(val id: String) {
    Activity("activity"), Created("created"), Title("title");

    companion object {
        fun fromId(id: String): ThreadSort = entries.firstOrNull { it.id == id } ?: Activity
    }
}

/** The settings only the Windows app has: shortcuts, the text size, the chat list's order and reading aloud. */
data class DesktopPrefs(
    /** Multiplies every text size; see [DesktopSettings.TEXT_SCALES]. */
    val textScale: Float = 1f,
    /** Every action's combo; null is none. */
    val shortcuts: Map<ShortcutAction, KeyCombo?> = Shortcuts.resolve(emptyMap()),
    val threadSort: ThreadSort = ThreadSort.Activity,
    /** Read each new answer aloud as it comes in. */
    val readAloud: Boolean = false,
    /** The Kokoro voice for English replies. */
    val englishVoice: String = Kokoro.DEFAULT,
    /** The Supertonic voice for Dutch and the other languages Kokoro can't read. */
    val otherVoice: String = Supertonic.DEFAULT,
) {
    fun shortcut(action: ShortcutAction): KeyCombo? = shortcuts[action]
}

/**
 * The Windows app's settings, in settings.json under %APPDATA%\dudan (or DUDAN_HOME, for a test
 * profile). The API key is encrypted with DPAPI, so only this Windows account can read it back, as the
 * phone keeps it under an Android Keystore key.
 */
class DesktopSettings(val dir: File = defaultDir()) {
    @Serializable
    private data class StoredModel(
        val provider: String? = null,
        val model: String? = null,
        val label: String? = null,
        /** Hermes' wire name, "default" to leave it to Hermes, or null for the profile's default. */
        val effort: String? = null,
        val fast: Boolean = false,
    )

    @Serializable
    private data class Stored(
        val serverUrl: String = "",
        /** DPAPI-encrypted, Base64. */
        val apiKey: String = "",
        val userName: String = "",
        val assistantName: String = AppSettings.DEFAULT_ASSISTANT_NAME,
        val reduceTransparency: Boolean = false,
        val accent: String = "Moon",
        val sky: String = "Dusk",
        val showAllChannels: Boolean = false,
        // Rich replies, on as on the phone. A new key: versions before OpenUI was drawn here stored
        // richReplies = false without the user choosing it, and that stays ignored.
        val openUiReplies: Boolean = true,
        val model: StoredModel = StoredModel(),
        val assistantModel: StoredModel = StoredModel(),
        /** False switched the quick question's shortcut off before shortcuts could be changed; see [shortcuts]. */
        val quickAskShortcut: Boolean = true,
        val textScale: Float = 1f,
        /** Shortcuts the user changed, by ShortcutAction id; an empty combo is none. */
        val shortcuts: Map<String, String> = emptyMap(),
        val threadSort: String = ThreadSort.Activity.id,
        val readAloud: Boolean = false,
        val englishVoice: String = Kokoro.DEFAULT,
        val otherVoice: String = Supertonic.DEFAULT,
    )

    private val file = File(dir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Mutex()
    private var stored: Stored = load()

    private val _flow = MutableStateFlow(stored.toSettings())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()

    private val _prefs = MutableStateFlow(stored.toPrefs())
    val prefs: StateFlow<DesktopPrefs> = _prefs.asStateFlow()

    fun current(): AppSettings = _flow.value

    private fun load(): Stored = runCatching { json.decodeFromString<Stored>(file.readText()) }.getOrElse { Stored() }

    private suspend fun edit(change: (Stored) -> Stored) = lock.withLock {
        val next = change(stored)
        if (next == stored) return@withLock
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            // Write beside the file and move it over, so a crash mid-write never leaves half a settings file.
            val temp = File(dir, "settings.json.tmp")
            temp.writeText(json.encodeToString(Stored.serializer(), next))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        stored = next
        _flow.value = next.toSettings()
        _prefs.value = next.toPrefs()
    }

    /** Saves the setup form in one go. Models picked on another server may not exist on this one. */
    suspend fun saveSetup(serverUrl: String, apiKey: String, userName: String, assistantName: String) = edit {
        val url = HermesApi.normalizeBaseUrl(serverUrl)
        val sameServer = url == it.serverUrl
        it.copy(
            serverUrl = url,
            apiKey = protect(apiKey.trim()),
            userName = userName.trim(),
            assistantName = assistantName.trim().ifBlank { AppSettings.DEFAULT_ASSISTANT_NAME },
            model = if (sameServer) it.model else StoredModel(),
            assistantModel = if (sameServer) it.assistantModel else StoredModel(),
        )
    }

    suspend fun setModel(profile: ModelProfile, choice: ModelChoice) = edit {
        val stored = StoredModel(choice.provider, choice.model, choice.label, choice.effort?.wire ?: HERMES_EFFORT, choice.fast)
        when (profile) {
            ModelProfile.Chats -> it.copy(model = stored)
            ModelProfile.Assistant -> it.copy(assistantModel = stored)
        }
    }

    suspend fun setAccent(name: String) = edit { it.copy(accent = name) }
    suspend fun setSky(name: String) = edit { it.copy(sky = name) }
    suspend fun setReduceTransparency(value: Boolean) = edit { it.copy(reduceTransparency = value) }
    suspend fun setShowAllChannels(value: Boolean) = edit { it.copy(showAllChannels = value) }
    /** Sets [action]'s combo; null is none. The default isn't saved, so a later version can change it. */
    suspend fun setShortcut(action: ShortcutAction, combo: KeyCombo?) = edit {
        val rest = it.shortcuts - action.id
        it.copy(
            shortcuts = if (combo == action.default) rest else rest + (action.id to (combo?.toString() ?: "")),
            // Any choice for the quick question replaces the old on/off switch.
            quickAskShortcut = it.quickAskShortcut || action == ShortcutAction.QuickAsk,
        )
    }

    suspend fun resetShortcuts() = edit { it.copy(shortcuts = emptyMap(), quickAskShortcut = true) }

    /** Snaps to the nearest of [TEXT_SCALES]. */
    suspend fun setTextScale(value: Float) = edit { it.copy(textScale = TEXT_SCALES.minBy { step -> kotlin.math.abs(step - value) }) }

    suspend fun setThreadSort(value: ThreadSort) = edit { it.copy(threadSort = value.id) }
    suspend fun setReadAloud(value: Boolean) = edit { it.copy(readAloud = value) }
    suspend fun setEnglishVoice(id: String) = edit { it.copy(englishVoice = Kokoro.voice(id).id) }
    suspend fun setOtherVoice(id: String) = edit { it.copy(otherVoice = Supertonic.voice(id).id) }
    suspend fun setRichReplies(value: Boolean) = edit { it.copy(openUiReplies = value) }

    private fun Stored.toSettings() = AppSettings(
        serverUrl = serverUrl,
        apiKey = unprotect(apiKey),
        userName = userName,
        assistantName = assistantName.ifBlank { AppSettings.DEFAULT_ASSISTANT_NAME },
        reduceTransparency = reduceTransparency,
        accent = accent,
        sky = sky,
        showAllChannels = showAllChannels,
        richReplies = openUiReplies,
        model = model.toChoice(null),
        assistantModel = assistantModel.toChoice(ReasoningEffort.Low),
        // Nothing on the PC acts for Hermes, and nothing reads aloud yet.
        phoneControl = false,
        speakReplies = false,
    )

    private fun Stored.toPrefs(): DesktopPrefs {
        // Switched off in the version before shortcuts could change: no quick question shortcut, until one is set.
        val saved = if (!quickAskShortcut && ShortcutAction.QuickAsk.id !in shortcuts) shortcuts + (ShortcutAction.QuickAsk.id to "") else shortcuts
        return DesktopPrefs(
            textScale = textScale.takeIf { it in TEXT_SCALES } ?: 1f,
            shortcuts = Shortcuts.resolve(saved),
            threadSort = ThreadSort.fromId(threadSort),
            readAloud = readAloud,
            englishVoice = Kokoro.voice(englishVoice).id,
            otherVoice = Supertonic.voice(otherVoice).id,
        )
    }

    private fun StoredModel.toChoice(defaultEffort: ReasoningEffort?) = ModelChoice(
        provider = provider,
        model = model,
        label = label,
        effort = when (effort) {
            HERMES_EFFORT -> null
            null -> defaultEffort
            else -> ReasoningEffort.fromWire(effort) ?: defaultEffort
        },
        fast = fast,
    )

    companion object {
        /** The text sizes Settings and the shortcuts step through, smallest first. */
        val TEXT_SCALES = listOf(0.85f, 0.9f, 1f, 1.1f, 1.2f, 1.35f, 1.5f)

        /** Saved in place of an effort when the user leaves it to Hermes, as on the phone. */
        private const val HERMES_EFFORT = "default"

        fun defaultDir(): File {
            System.getenv("DUDAN_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "\\AppData\\Roaming")
            return File(appData, "dudan")
        }

        /**
         * Where the voice models go: hundreds of megabytes that shouldn't roam with a Windows profile, so under
         * %LOCALAPPDATA%, beside the folder the installer owns. A DUDAN_HOME profile keeps its own.
         */
        fun voicesDir(): File {
            System.getenv("DUDAN_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it, "voices") }
            val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "\\AppData\\Local")
            return File(local, "dudan-voices")
        }

        // UTF-8 both ways: Java 17 on Windows decodes with windows-1252 by default.
        private fun protect(secret: String): String =
            if (secret.isEmpty()) "" else Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(secret.toByteArray(Charsets.UTF_8)))

        /** Empty when the key can't be read back, for instance after copying the profile to another account. */
        private fun unprotect(stored: String): String =
            if (stored.isEmpty()) "" else runCatching { String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored)), Charsets.UTF_8) }.getOrDefault("")
    }
}
