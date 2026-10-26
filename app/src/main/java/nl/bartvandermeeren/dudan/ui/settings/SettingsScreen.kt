package nl.bartvandermeeren.dudan.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import nl.bartvandermeeren.dudan.BuildConfig
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.appContainer
import nl.bartvandermeeren.dudan.assist.ListenActivity
import nl.bartvandermeeren.dudan.chat.userMessage
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.LanguageVoice
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.data.SttEngine
import nl.bartvandermeeren.dudan.data.UploadClient
import nl.bartvandermeeren.dudan.data.VoiceEngine
import nl.bartvandermeeren.dudan.device.PhoneControl
import nl.bartvandermeeren.dudan.device.Tailnet
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.chat.ModelPickerSheet
import nl.bartvandermeeren.dudan.ui.chat.modelModeLabel
import nl.bartvandermeeren.dudan.ui.chat.prettyModelName
import nl.bartvandermeeren.dudan.ui.components.DudanMark
import nl.bartvandermeeren.dudan.ui.components.CtaButton
import nl.bartvandermeeren.dudan.ui.components.Segmented
import nl.bartvandermeeren.dudan.ui.components.GlassDropdownMenu
import nl.bartvandermeeren.dudan.ui.components.GlassSheet
import nl.bartvandermeeren.dudan.ui.components.SheetChoice
import nl.bartvandermeeren.dudan.ui.components.PlainIconButton
import nl.bartvandermeeren.dudan.ui.components.Toggle
import nl.bartvandermeeren.dudan.ui.components.glass
import nl.bartvandermeeren.dudan.ui.components.outlined
import nl.bartvandermeeren.dudan.ui.theme.Accent
import nl.bartvandermeeren.dudan.ui.theme.LocalAccent
import nl.bartvandermeeren.dudan.ui.theme.Palette
import nl.bartvandermeeren.dudan.ui.theme.Sky
import nl.bartvandermeeren.dudan.voice.WakePhrase
import nl.bartvandermeeren.dudan.voice.Endpointer
import nl.bartvandermeeren.dudan.voice.ModelPackage
import nl.bartvandermeeren.dudan.voice.KokoroVoice
import nl.bartvandermeeren.dudan.voice.SupertonicVoice
import nl.bartvandermeeren.dudan.voice.VoiceLanguages
import nl.bartvandermeeren.dudan.voice.VoiceSamples

private sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Ok(val model: String?) : TestState
    data class Failed(val message: String) : TestState
}

@Composable
fun SettingsScreen(vm: MainViewModel, settings: AppSettings, setupMode: Boolean, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(settings.serverUrl) }
    // Not rememberSaveable: the decrypted key must not end up in the saved-state Bundle.
    var key by remember { mutableStateOf(settings.apiKey) }
    var name by rememberSaveable { mutableStateOf(settings.userName) }
    var assistant by rememberSaveable { mutableStateOf(settings.assistantName) }
    var language by rememberSaveable { mutableStateOf(settings.speechLanguage) }
    var showKey by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }

    if (onBack != null) BackHandler(onBack = onBack)

    fun connect() {
        test = TestState.Testing
        scope.launch {
            val repo = vm.settingsRepository
            try {
                val model = context.appContainer.apiFor(HermesApi.ServerConfig(HermesApi.normalizeBaseUrl(url), key.trim())).verify()
                repo.saveSetup(url, key, name, assistant, language)
                test = TestState.Ok(model)
                vm.refreshSessions()
                vm.loadModels()
            } catch (e: Exception) {
                test = TestState.Failed(e.userMessage())
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(64.dp)) {
                if (onBack != null) {
                    PlainIconButton(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        stringResource(R.string.action_back),
                        onClick = onBack,
                        modifier = Modifier.outlined(CircleShape),
                        size = 44.dp,
                        iconSize = 24.dp,
                    )
                    Spacer(Modifier.size(14.dp))
                } else {
                    Spacer(Modifier.size(4.dp))
                }
                Text(
                    stringResource(if (setupMode) R.string.setup_title else R.string.settings),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Palette.TextPrimary,
                )
            }
            if (setupMode) {
                Row(
                    Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DudanMark(size = 40.dp, halo = true)
                    Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                }
            }

            Section(stringResource(R.string.section_connection)) {
                Field(url, { url = it; test = TestState.Idle }, stringResource(R.string.server_url), placeholder = "http://my-server:8642", keyboard = KeyboardType.Uri)
                if (isInsecureRemote(url)) Notice(stringResource(R.string.insecure_url_warning), Palette.SparkAmber)
                Field(
                    key, { key = it; test = TestState.Idle }, stringResource(R.string.api_key),
                    keyboard = KeyboardType.Password,
                    visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = Palette.TextSecondary)
                        }
                    },
                )
                Field(name, { name = it }, stringResource(R.string.your_name))
                Field(assistant, { assistant = it }, stringResource(R.string.assistant_name))

                val canConnect = url.isNotBlank() && key.isNotBlank()
                CtaButton(
                    stringResource(if (setupMode) R.string.connect else R.string.save_and_test),
                    onClick = ::connect,
                    enabled = canConnect && test != TestState.Testing,
                    modifier = Modifier.padding(top = 4.dp),
                    busy = if (test == TestState.Testing) {
                        { CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                )
                when (val t = test) {
                    is TestState.Ok -> Notice(stringResource(R.string.connection_ok, t.model ?: "Hermes"), Palette.Success, ok = true)
                    is TestState.Failed -> Notice(stringResource(R.string.connection_failed, t.message), Palette.Danger, error = true)
                    else -> Unit
                }
            }

            if (!setupMode) {
                Section(stringResource(R.string.section_models)) {
                    ModelProfile.entries.forEach { profile -> ModelDefaultRow(vm, settings, profile) }
                }
                Section(stringResource(R.string.section_voices)) { VoicesSettings(vm, settings) }
                Section(stringResource(R.string.section_speech_input)) {
                    SpeechInputSettings(vm, settings, language) { language = it; scope.launch { vm.settingsRepository.setSpeechLanguage(it) } }
                }

                Section(stringResource(R.string.section_assistant)) {
                    DefaultAssistantStatus()
                    Toggle(stringResource(R.string.listen_on_invoke), stringResource(R.string.listen_on_invoke_detail), settings.listenOnInvoke) {
                        scope.launch { vm.settingsRepository.setListenOnInvoke(it) }
                    }
                    WakeWordSettings(vm, settings)
                    ListenEntrySetting()
                    Toggle(stringResource(R.string.speak_replies), stringResource(R.string.speak_replies_detail), settings.speakReplies) {
                        scope.launch { vm.settingsRepository.setSpeakReplies(it) }
                    }
                }

                Section(stringResource(R.string.section_replies_files)) { RepliesAndFilesSettings(vm, settings) }

                Section(stringResource(R.string.section_phone_control)) { PhoneControlSettings(vm, settings) }

                Section(stringResource(R.string.section_appearance)) {
                    SkyPicker(Sky.from(settings.sky)) { scope.launch { vm.settingsRepository.setSky(it.name) } }
                    AccentPicker(Accent.from(settings.accent)) { scope.launch { vm.settingsRepository.setAccent(it.name) } }
                    Toggle(stringResource(R.string.reduce_transparency), stringResource(R.string.reduce_transparency_detail), settings.reduceTransparency) {
                        scope.launch { vm.settingsRepository.setReduceTransparency(it) }
                    }
                }

                Section(stringResource(R.string.section_history)) {
                    Toggle(stringResource(R.string.show_all_channels), stringResource(R.string.show_all_channels_detail), settings.showAllChannels) {
                        scope.launch {
                            vm.settingsRepository.setShowAllChannels(it)
                            vm.refreshSessions()
                        }
                    }
                    // Inside the card: on the bare lower sky this small print measured 1.7:1.
                    Text("dudan ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** One default model: what it's used for, what's picked, and a tap to change it. */
@Composable
private fun ModelDefaultRow(vm: MainViewModel, settings: AppSettings, profile: ModelProfile) {
    var picking by remember { mutableStateOf(false) }
    val catalog by vm.modelCatalog.collectAsStateWithLifecycle()
    val choice = settings.modelFor(profile)
    val name = choice.label?.takeIf { it != choice.model }
        ?: choice.model?.let(::prettyModelName)
        ?: catalog?.currentModel?.let { stringResource(R.string.model_server_default_named, prettyModelName(it)) }
        ?: stringResource(R.string.model_default)
    val mode = modelModeLabel(choice)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                vm.loadModels()
                picking = true
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                stringResource(if (profile == ModelProfile.Chats) R.string.profile_chats else R.string.profile_assistant),
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextPrimary,
            )
            Text(
                stringResource(if (profile == ModelProfile.Chats) R.string.profile_chats_detail else R.string.profile_assistant_detail),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
        }
        Text(if (mode.isEmpty()) name else "$name · $mode", style = MaterialTheme.typography.labelLarge, color = LocalAccent.current.soft)
    }
    if (picking) ModelPickerSheet(vm, settings, initialProfile = profile, onDismiss = { picking = false })
}

/**
 * A row for each of the phone's languages, English, and Other languages. Each is Automatic until the user
 * picks an engine or voice for it in its sheet.
 */
@Composable
private fun VoicesSettings(vm: MainViewModel, settings: AppSettings) {
    val rows = remember(settings.voices) { VoiceLanguages.rows(VoiceLanguages.phoneLanguages(), settings.voices) }
    val ready = rememberVoiceReady(vm)
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.voices_intro), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    Column {
        rows.forEach { language ->
            val choice = settings.voices[language] ?: LanguageVoice()
            val shown = choice.engine ?: VoiceLanguages.resolve(language, settings.voices, rows, ready).engine
            VoiceRow(
                title = languageName(language),
                detail = if (choice.engine == null) stringResource(R.string.voices_automatic_engine, engineName(shown)) else engineName(shown),
                value = voiceName(shown, VoiceLanguages.voiceFor(shown, choice.voice)),
                onClick = { editing = language },
            )
        }
        val other = settings.voices[VoiceLanguages.OTHER] ?: LanguageVoice()
        val supertonic = ready(VoiceEngine.Supertonic)
        VoiceRow(
            title = stringResource(R.string.voices_other),
            detail = when (val engine = other.engine) {
                null -> stringResource(
                    R.string.voices_automatic_engine,
                    if (supertonic) stringResource(R.string.voices_other_engines) else engineName(VoiceEngine.System),
                )
                else -> engineName(engine)
            },
            value = (other.engine ?: VoiceEngine.Supertonic.takeIf { supertonic })?.let { voiceName(it, VoiceLanguages.voiceFor(it, other.voice)) },
            onClick = { editing = VoiceLanguages.OTHER },
        )
    }
    val needed = VoiceLanguages.needed(rows, settings.voices)
    if (VoiceEngine.Kokoro in needed) ModelStatus(vm.kokoroModel, voiceModelTexts(engineName(VoiceEngine.Kokoro)))
    if (VoiceEngine.Supertonic in needed) ModelStatus(vm.supertonicModel, voiceModelTexts(engineName(VoiceEngine.Supertonic)))
    editing?.let { language -> VoiceSheet(vm, settings, language, rows, ready) { editing = null } }
}

/** Whether each engine can speak right now: its model downloaded and not crashed. */
@Composable
private fun rememberVoiceReady(vm: MainViewModel): (VoiceEngine) -> Boolean {
    val kokoro by vm.kokoroModel.state.collectAsStateWithLifecycle()
    val supertonic by vm.supertonicModel.state.collectAsStateWithLifecycle()
    return { engine ->
        when (engine) {
            VoiceEngine.Kokoro -> kokoro == ModelPackage.State.Ready
            VoiceEngine.Supertonic -> supertonic == ModelPackage.State.Ready
            VoiceEngine.System -> true
        }
    }
}

/** A language with who reads it underneath, and its voice in the accent color; tap to change. */
@Composable
private fun VoiceRow(title: String, detail: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        if (value != null) Text(value, style = MaterialTheme.typography.labelLarge, color = LocalAccent.current.soft)
    }
}

/** Who reads one language (Automatic or a fixed engine) and with which voice, with Preview. */
@Composable
private fun VoiceSheet(
    vm: MainViewModel,
    settings: AppSettings,
    language: String,
    rows: List<String>,
    ready: (VoiceEngine) -> Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val other = language == VoiceLanguages.OTHER
    val choice = settings.voices[language] ?: LanguageVoice()
    fun save(voice: LanguageVoice) {
        scope.launch { vm.settingsRepository.setVoice(language, voice) }
    }
    // Other languages previews in a language without a row of its own, German when it can.
    val spoken = if (other) PREVIEW_OTHER.firstOrNull { it !in rows } ?: PREVIEW_OTHER.first() else language
    val name = if (other) stringResource(R.string.voices_other) else languageName(language)
    GlassSheet(onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 10.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary)
            Text(
                if (other) stringResource(R.string.voices_sheet_other_detail) else stringResource(R.string.voices_sheet_detail, name),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
        }
        val automatic = VoiceLanguages.resolve(spoken, emptyMap(), listOf(spoken), ready).engine
        SheetChoice(
            title = stringResource(R.string.voices_automatic),
            subtitle = if (other) stringResource(R.string.voices_other_automatic_detail) else stringResource(R.string.voices_automatic_detail, engineName(automatic)),
            selected = choice.engine == null,
            onClick = { save(choice.copy(engine = null)) },
            singleLine = false,
        )
        VoiceLanguages.engines(language).forEach { engine ->
            SheetChoice(
                title = engineName(engine),
                subtitle = engineDetail(engine),
                selected = choice.engine == engine,
                onClick = { save(choice.copy(engine = engine)) },
                singleLine = false,
            )
        }
        val shown = VoiceLanguages.preferred(language, choice)
        val preview = VoiceSamples.text(spoken, settings.userName)
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown == VoiceEngine.System) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LinkAction(stringResource(R.string.tts_system_settings)) {
                        runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    LinkAction(stringResource(R.string.tts_preview)) { vm.speaker.preview(preview, spoken, choice) }
                }
            } else {
                VoicePicker(
                    voices = voiceList(shown),
                    selected = VoiceLanguages.voiceFor(shown, choice.voice).orEmpty(),
                    onSelect = { save(choice.copy(voice = it)) },
                    onPreview = { vm.speaker.preview(preview, spoken, choice) }.takeIf { ready(shown) },
                )
                ModelStatus(if (shown == VoiceEngine.Kokoro) vm.kokoroModel else vm.supertonicModel, voiceModelTexts(engineName(shown)))
            }
        }
    }
}

/** Languages the Other languages sheet can preview in, first choice first. */
private val PREVIEW_OTHER = listOf("de", "fr", "es", "it", "pt")

/** [language]'s name in the language Settings is shown in, which isn't always the phone's. */
@Composable
private fun languageName(language: String): String {
    val ui = stringResource(R.string.ui_language)
    return remember(language, ui) { VoiceLanguages.displayName(language, Locale.forLanguageTag(ui)) }
}

@Composable
private fun engineName(engine: VoiceEngine): String = stringResource(
    when (engine) {
        VoiceEngine.Kokoro -> R.string.tts_kokoro
        VoiceEngine.Supertonic -> R.string.tts_supertonic
        VoiceEngine.System -> R.string.tts_system
    },
)

@Composable
private fun engineDetail(engine: VoiceEngine): String = stringResource(
    when (engine) {
        VoiceEngine.Kokoro -> R.string.tts_kokoro_detail
        VoiceEngine.Supertonic -> R.string.tts_supertonic_detail
        VoiceEngine.System -> R.string.tts_system_detail
    },
)

/** The engine's voices as id to label; none for Android's voice. */
@Composable
private fun voiceList(engine: VoiceEngine): List<Pair<String, String>> = when (engine) {
    VoiceEngine.Kokoro -> KokoroVoice.VOICES.map { it.id to it.label }
    VoiceEngine.Supertonic -> SupertonicVoice.VOICES.map { voice ->
        voice.id to stringResource(if (voice.female) R.string.voice_female else R.string.voice_male, voice.number)
    }
    VoiceEngine.System -> emptyList()
}

@Composable
private fun voiceName(engine: VoiceEngine, id: String?): String? = voiceList(engine).firstOrNull { it.first == id }?.second

private fun voiceModelTexts(engine: String) =
    ModelTexts(R.string.tts_model_ready, R.string.tts_model_downloading, R.string.tts_model_download, R.string.tts_model_crashed, name = engine)

/** The voice in use with a menu of [voices] (id to label), and Preview once the model is in. */
@Composable
private fun VoicePicker(voices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, onPreview: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    val current = voices.firstOrNull { it.first == selected } ?: voices.first()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { open = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(R.string.tts_voice), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                    Text(current.second, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                }
            }
            GlassDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                voices.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            open = false
                            onSelect(id)
                        },
                    )
                }
            }
        }
        onPreview?.let { LinkAction(stringResource(R.string.tts_preview), it) }
    }
}

@Composable
private fun SpeechInputSettings(vm: MainViewModel, settings: AppSettings, language: String, onLanguage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    Segmented(
        options = listOf(SttEngine.Orukeet to stringResource(R.string.stt_orukeet), SttEngine.System to stringResource(R.string.stt_system)),
        selected = settings.sttEngine,
        onSelect = { scope.launch { vm.settingsRepository.setSttEngine(it) } },
    )
    if (settings.sttEngine == SttEngine.Orukeet) {
        Text(stringResource(R.string.stt_orukeet_detail), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        ModelStatus(
            vm.orukeetModel,
            ModelTexts(R.string.stt_orukeet_ready, R.string.stt_orukeet_downloading, R.string.stt_orukeet_download, R.string.stt_orukeet_crashed),
        )
    }
    Field(language, onLanguage, stringResource(R.string.speech_language), placeholder = stringResource(R.string.speech_language_hint))
    SpeechPause(settings.speechPauseMs, systemRecognizer = settings.sttEngine == SttEngine.System) {
        scope.launch { vm.settingsRepository.setSpeechPause(it) }
    }
}

private const val PAUSE_STEP_MS = 500

/** How long a silence ends voice input, in half-second steps. */
@Composable
private fun SpeechPause(savedMs: Int, systemRecognizer: Boolean, onSave: (Int) -> Unit) {
    val range = Endpointer.END_SILENCE_RANGE_MS
    // Saved when the thumb is let go, not at every step of the drag.
    var ms by remember(savedMs) { mutableIntStateOf(savedMs) }
    val title = stringResource(R.string.speech_pause)
    val value = stringResource(R.string.speech_pause_value, ms / 1000f)
    val accent = LocalAccent.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = accent.soft)
        }
        Text(
            stringResource(if (systemRecognizer) R.string.speech_pause_detail_system else R.string.speech_pause_detail),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextSecondary,
        )
        Slider(
            value = ms.toFloat(),
            onValueChange = { ms = (it / PAUSE_STEP_MS).roundToInt() * PAUSE_STEP_MS },
            onValueChangeFinished = { onSave(ms) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / PAUSE_STEP_MS - 1,
            colors = SliderDefaults.colors(
                thumbColor = accent.color,
                activeTrackColor = accent.color,
                inactiveTrackColor = Palette.Outline,
                activeTickColor = accent.on,
                inactiveTickColor = Palette.TextTertiary,
            ),
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = value
            },
        )
    }
}

/** The wording for one on-device model's status. With a [name], each text takes it as its first argument. */
private class ModelTexts(
    @param:StringRes val ready: Int,
    @param:StringRes val downloading: Int,
    @param:StringRes val download: Int,
    @param:StringRes val crashed: Int,
    val name: String? = null,
)

/** Installed, downloading, missing, failed or crashed, with the action that fits. */
@Composable
private fun ModelStatus(model: ModelPackage, texts: ModelTexts) {
    val modelState by model.state.collectAsStateWithLifecycle()
    @Composable
    fun text(@StringRes id: Int, vararg args: Any): String = stringResource(id, *listOfNotNull(texts.name).toTypedArray(), *args)
    when (val state = modelState) {
        ModelPackage.State.Ready -> Notice(text(texts.ready), Palette.Success, ok = true)
        is ModelPackage.State.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text(texts.downloading, (state.fraction * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
            )
            LinearProgressIndicator(
                progress = { state.fraction },
                color = LocalAccent.current.soft,
                trackColor = Palette.Surface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ModelPackage.State.Missing -> LinkAction(text(texts.download, (model.sizeBytes / 1_000_000).toInt())) { model.download() }
        is ModelPackage.State.Failed -> {
            Notice(stringResource(R.string.model_failed, state.message), Palette.Danger, error = true)
            LinkAction(stringResource(R.string.action_retry)) { model.download() }
        }
        ModelPackage.State.Crashed -> {
            Notice(text(texts.crashed), Palette.Danger, error = true)
            LinkAction(stringResource(R.string.action_retry)) { model.retryAfterCrash() }
        }
    }
}

@Composable
private fun LinkAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalAccent.current.soft,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

@Composable
private fun DefaultAssistantStatus() {
    val context = LocalContext.current
    val isDefault = rememberIsDefaultAssistant()
    // Plain content in the Assistant section: a card inside the section's pane stacked two tints
    // under this text and brought it down to 4.5:1.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (isDefault) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                null,
                tint = if (isDefault) Palette.Success else Palette.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                stringResource(if (isDefault) R.string.default_assistant_on else R.string.default_assistant_off),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextPrimary,
            )
        }
        Text(stringResource(R.string.default_assistant_detail), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        Text(
            stringResource(R.string.open_assistant_settings),
            style = MaterialTheme.typography.labelLarge,
            color = LocalAccent.current.soft,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    val intents = listOf(
                        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                        Intent(Settings.ACTION_SETTINGS),
                    )
                    for (intent in intents) {
                        try {
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            break
                        } catch (_: ActivityNotFoundException) {
                            continue
                        }
                    }
                }
                .padding(vertical = 6.dp),
        )
    }
}

/** The wake word on or off, its phrase, its model, and what it needs from Android. */
@Composable
private fun WakeWordSettings(vm: MainViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val micDenied = stringResource(R.string.mic_permission_needed)
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { vm.settingsRepository.setWakeWord(true) } else Toast.makeText(context, micDenied, Toast.LENGTH_LONG).show()
    }
    Toggle(stringResource(R.string.wake_word), stringResource(R.string.wake_word_detail, settings.wakePhrase), settings.wakeWord) { on ->
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (on && !hasMic) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        else scope.launch { vm.settingsRepository.setWakeWord(on) }
    }
    if (!settings.wakeWord) return
    var phrase by rememberSaveable { mutableStateOf(settings.wakePhrase) }
    Field(
        phrase,
        {
            phrase = it
            if (WakePhrase.isUsable(it)) scope.launch { vm.settingsRepository.setWakePhrase(it) }
        },
        stringResource(R.string.wake_phrase),
        placeholder = WakePhrase.DEFAULT,
    )
    when (WakePhrase.problem(phrase)) {
        null -> Text(stringResource(R.string.wake_phrase_detail), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        WakePhrase.Problem.TooShort -> Notice(stringResource(R.string.wake_phrase_too_short), Palette.SparkAmber)
        WakePhrase.Problem.TooLong -> Notice(stringResource(R.string.wake_phrase_too_long), Palette.SparkAmber)
    }
    ModelStatus(
        context.appContainer.wakeWordModel,
        ModelTexts(R.string.wake_word_ready, R.string.wake_word_downloading, R.string.wake_word_download, R.string.wake_word_crashed),
    )
    // Only Orukeet gets the audio right after the phrase.
    val orukeet by vm.orukeetModel.state.collectAsStateWithLifecycle()
    if (settings.sttEngine != SttEngine.Orukeet || orukeet != ModelPackage.State.Ready) {
        Notice(stringResource(R.string.wake_word_wait_for_tone), Palette.SparkAmber)
    }
    if (!rememberIsDefaultAssistant()) Notice(stringResource(R.string.wake_word_needs_assistant), Palette.SparkAmber)
}

/**
 * Clark in the app list, for Bixby to open by name. Android keeps the switch as the entry's state.
 * Clark always listens, so switching it on asks for the microphone, as the wake word does.
 */
@Composable
private fun ListenEntrySetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(ListenActivity.isInAppList(context)) }
    fun turn(value: Boolean) {
        ListenActivity.setInAppList(context, value)
        on = value
    }
    val micDenied = stringResource(R.string.mic_permission_needed)
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) turn(true) else Toast.makeText(context, micDenied, Toast.LENGTH_LONG).show()
    }
    val name = stringResource(R.string.listen_entry_label)
    Toggle(stringResource(R.string.listen_entry, name), stringResource(R.string.listen_entry_detail, name), on) { value ->
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (value && !hasMic) micPermission.launch(Manifest.permission.RECORD_AUDIO) else turn(value)
    }
    if (on && !rememberIsDefaultAssistant()) Notice(stringResource(R.string.listen_entry_needs_assistant, name), Palette.SparkAmber)
}

/** Whether dudan is the default assistant. The user changes that in Android's settings, so it's checked again on each return. */
@Composable
private fun rememberIsDefaultAssistant(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var isDefault by remember { mutableStateOf(isDefaultAssistant(context)) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { isDefault = isDefaultAssistant(context) }
    }
    return isDefault
}

private fun isDefaultAssistant(context: Context) =
    context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

/** A setting's label with the chosen value's name beside it. */
@Composable
private fun PickerLabel(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
    }
}

/**
 * A small sky per background, like a wallpaper picker, as a radio group of two rows of three. The
 * chosen one wears a ring and a check.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkyPicker(selected: Sky, onSelect: (Sky) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PickerLabel(stringResource(R.string.background), stringResource(selected.label))
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
            Sky.entries.forEach { sky ->
                val chosen = sky == selected
                val name = stringResource(sky.label)
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .selectable(selected = chosen, role = Role.RadioButton) { onSelect(sky) }
                        .semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .then(if (chosen) Modifier.border(2.dp, Palette.TextPrimary, RoundedCornerShape(18.dp)) else Modifier)
                            .padding(4.dp)
                            .clip(shape)
                            .background(Brush.verticalGradient(0f to sky.top, 0.45f to sky.mid, 1f to sky.horizon))
                            .background(Brush.radialGradient(listOf(sky.glow.copy(alpha = 0.45f), Color.Transparent), center = Offset(0f, Float.POSITIVE_INFINITY)))
                            .border(1.dp, Palette.Hairline, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (chosen) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Text(name, style = MaterialTheme.typography.bodySmall, color = if (chosen) Palette.TextPrimary else Palette.TextSecondary, maxLines = 1)
                }
            }
        }
    }
}

/** Whether Hermes may act on this phone, where it can reach it, and the setup to paste on the Hermes host. */
@Composable
private fun PhoneControlSettings(vm: MainViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val control = context.appContainer.phoneControl
    val state by control.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var address by remember { mutableStateOf<String?>(null) }
    var opensFromBackground by remember { mutableStateOf(true) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            opensFromBackground = control.canStartFromBackground()
            // Tailscale can connect while this screen is open.
            while (true) {
                address = withContext(Dispatchers.IO) { Tailnet.ownAddress()?.hostAddress }
                delay(3_000)
            }
        }
    }
    Toggle(stringResource(R.string.phone_control), stringResource(R.string.phone_control_detail), settings.phoneControl) {
        scope.launch { vm.settingsRepository.setPhoneControl(it) }
    }
    if (!settings.phoneControl) return
    when (val s = state) {
        is PhoneControl.State.Running -> address?.let {
            Notice(stringResource(R.string.phone_control_reachable, "http://$it:${s.port}/mcp"), Palette.Success, ok = true)
        } ?: Notice(stringResource(R.string.phone_control_no_tailscale), Palette.SparkAmber)
        is PhoneControl.State.Failed -> Notice(stringResource(R.string.phone_control_failed, s.message), Palette.Danger, error = true)
        PhoneControl.State.Off -> Notice(stringResource(R.string.phone_control_starting), Palette.TextSecondary)
    }
    if (!opensFromBackground) {
        Notice(stringResource(R.string.phone_control_background), Palette.SparkAmber)
        LinkAction(stringResource(R.string.phone_control_background_allow)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    val copied = stringResource(R.string.phone_control_copied)
    val renewed = stringResource(R.string.phone_control_renewed)
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        LinkAction(stringResource(R.string.phone_control_copy)) {
            val clip = ClipData.newPlainText("Hermes setup", control.hermesSetup(address, settings.phoneToken))
            // Keeps the token out of the clipboard preview and keyboard suggestions.
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
            Toast.makeText(context, copied, Toast.LENGTH_LONG).show()
        }
        LinkAction(stringResource(R.string.phone_control_renew)) {
            scope.launch {
                vm.settingsRepository.renewPhoneToken()
                Toast.makeText(context, renewed, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** OpenUI replies on or off, and where attachments are uploaded, with a check that the service answers. */
@Composable
private fun RepliesAndFilesSettings(vm: MainViewModel, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    Toggle(stringResource(R.string.rich_replies), stringResource(R.string.rich_replies_detail), settings.richReplies) {
        scope.launch { vm.settingsRepository.setRichReplies(it) }
    }
    var address by rememberSaveable { mutableStateOf(settings.uploadUrl) }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    Field(
        address,
        {
            address = it
            result = null
            scope.launch { vm.settingsRepository.setUploadUrl(it) }
        },
        stringResource(R.string.upload_server),
        placeholder = UploadClient.baseUrlFor(settings.serverUrl, "").ifBlank { null },
        keyboard = KeyboardType.Uri,
    )
    if (address.isNotBlank() && isInsecureRemote(address)) {
        Notice(stringResource(R.string.insecure_url_warning), Palette.SparkAmber)
    }
    Text(stringResource(R.string.upload_server_detail), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    result?.let { Notice(it, if (failed) Palette.Danger else Palette.Success, ok = !failed, error = failed) }
    val ok = stringResource(R.string.upload_ok)
    LinkAction(stringResource(if (checking) R.string.upload_checking else R.string.upload_check)) {
        if (checking) return@LinkAction
        scope.launch {
            checking = true
            val problem = vm.checkUploads()
            failed = problem != null
            result = problem ?: ok
            checking = false
        }
    }
}

/** A swatch per accent, as a radio group: the chosen one wears a ring and a check, and its name shows beside the label. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(selected: Accent, onSelect: (Accent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PickerLabel(stringResource(R.string.accent_color), stringResource(selected.label))
        // Two rows of four, so the eight swatches never break unevenly on the narrow cover screen.
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 4) {
            Accent.entries.forEach { accent ->
                val chosen = accent == selected
                val name = stringResource(accent.label)
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .selectable(selected = chosen, role = Role.RadioButton) { onSelect(accent) }
                        .semantics { contentDescription = name }
                        .then(if (chosen) Modifier.border(2.dp, accent.color, CircleShape) else Modifier)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(accent.color),
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) Icon(Icons.Rounded.Check, contentDescription = null, tint = accent.on, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** A glass card on the sky holding one group of settings. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The title sits inside the card, like "Team workspace" on Superhuman's document panel.
        Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary)
        content()
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Palette.TextTertiary) } },
        singleLine = true,
        visualTransformation = visual,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        trailingIcon = trailing,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LocalAccent.current.color,
            unfocusedBorderColor = Palette.Outline,
            focusedLabelColor = Palette.TextPrimary,
            unfocusedLabelColor = Palette.TextSecondary,
            focusedContainerColor = Palette.Surface,
            unfocusedContainerColor = Palette.Surface,
            cursorColor = LocalAccent.current.soft,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Notice(text: String, tint: Color, ok: Boolean = false, error: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            when {
                ok -> Icons.Outlined.CheckCircle
                error -> Icons.Outlined.ErrorOutline
                else -> Icons.Outlined.Info
            },
            null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
    }
}

/** http:// is fine inside the tailnet (WireGuard encrypts it); anywhere else the key travels in clear text. */
fun isInsecureRemote(raw: String): Boolean {
    val url = HermesApi.normalizeBaseUrl(raw)
    if (!url.startsWith("http://")) return false
    val host = url.removePrefix("http://").substringBefore('/').substringBefore(':').lowercase()
    if (host.isEmpty()) return false
    if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") return false
    if (host.endsWith(".ts.net") || !host.contains('.')) return false // MagicDNS names
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size == 4 && parts[0] == 100 && parts[1] in 64..127) return false // Tailscale CGNAT range
    return true
}
