package nl.bartvandermeeren.dudan.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.desktop.DesktopSettings
import nl.bartvandermeeren.dudan.desktop.KeyCombo
import nl.bartvandermeeren.dudan.desktop.ShortcutAction
import nl.bartvandermeeren.dudan.desktop.ShortcutKey
import nl.bartvandermeeren.dudan.desktop.ShortcutProblem
import nl.bartvandermeeren.dudan.desktop.res.*
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.components.GlassDropdownMenu
import nl.bartvandermeeren.dudan.ui.components.PlainIconButton
import nl.bartvandermeeren.dudan.ui.components.Toggle
import nl.bartvandermeeren.dudan.ui.components.outlined
import nl.bartvandermeeren.dudan.ui.theme.LocalAccent
import nl.bartvandermeeren.dudan.ui.theme.Palette
import nl.bartvandermeeren.dudan.voice.DesktopSpeaker
import nl.bartvandermeeren.dudan.voice.Kokoro
import nl.bartvandermeeren.dudan.voice.ModelStore
import nl.bartvandermeeren.dudan.voice.Supertonic
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A combo as Settings, the tray and the help texts show it, with the space bar named in the user's language. */
@Composable
fun KeyCombo.label(): String =
    if (key == ShortcutKey.Space) toString().removeSuffix(ShortcutKey.Space.label) + stringResource(Res.string.desktop_key_space) else toString()

private val ShortcutAction.title: StringResource
    get() = when (this) {
        ShortcutAction.QuickAsk -> Res.string.desktop_shortcut_quick_ask
        ShortcutAction.NewChat -> Res.string.new_chat
        ShortcutAction.SearchChats -> Res.string.search_chats
        ShortcutAction.ToggleSidebar -> Res.string.desktop_shortcut_toggle_sidebar
        ShortcutAction.PreviousChat -> Res.string.desktop_shortcut_previous_chat
        ShortcutAction.NextChat -> Res.string.desktop_shortcut_next_chat
        ShortcutAction.SettleChat -> Res.string.desktop_shortcut_settle_chat
        ShortcutAction.ReadAloud -> Res.string.desktop_shortcut_read_aloud
        ShortcutAction.TextBigger -> Res.string.desktop_shortcut_text_bigger
        ShortcutAction.TextSmaller -> Res.string.desktop_shortcut_text_smaller
        ShortcutAction.TextReset -> Res.string.desktop_shortcut_text_reset
        ShortcutAction.OpenSettings -> Res.string.settings
    }

/** Text size as a percentage between − and +, with Default once it's changed. */
@Composable
fun TextSizeRow(vm: MainViewModel, scale: Float) {
    val percent = "${(scale * 100).roundToInt()}%"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(Res.string.desktop_text_size), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Text(stringResource(Res.string.desktop_text_size_detail), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        if (scale != 1f) {
            TextButton(onClick = vm::resetTextSize) { Text(stringResource(Res.string.desktop_text_reset)) }
        }
        Row(
            Modifier.outlined(RoundedCornerShape(16.dp)).semantics { stateDescription = percent },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val steps = DesktopSettings.TEXT_SCALES
            StepButton(Icons.Rounded.Remove, stringResource(Res.string.desktop_text_smaller), enabled = scale > steps.first()) { vm.stepTextSize(-1) }
            Text(percent, style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary, modifier = Modifier.widthIn(min = 48.dp).padding(horizontal = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepButton(Icons.Rounded.Add, stringResource(Res.string.desktop_text_bigger), enabled = scale < steps.last()) { vm.stepTextSize(1) }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    PlainIconButton(icon, label, onClick = { if (enabled) onClick() }, size = 40.dp, iconSize = 20.dp, tint = if (enabled) Palette.Icon else Palette.TextTertiary.copy(alpha = 0.4f))
}

/**
 * Every shortcut with its keys. A click on the keys waits for new ones; the window's key handler sends
 * them to [MainViewModel.record]. The quick question's keys work in every app, so another app may hold
 * them already: [shortcutTaken] says so.
 */
@Composable
fun ShortcutsSection(vm: MainViewModel, settings: AppSettings, shortcutTaken: Boolean) {
    val prefs by vm.prefs.collectAsState()
    // Leaving Settings mid-recording mustn't leave the window swallowing keys.
    DisposableEffect(Unit) { onDispose { vm.stopRecording() } }
    Section(stringResource(Res.string.desktop_section_shortcuts)) {
        Text(stringResource(Res.string.desktop_shortcuts_intro), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        ShortcutAction.entries.forEach { action ->
            val combo = prefs.shortcut(action)
            ShortcutRow(
                vm = vm,
                action = action,
                combo = combo,
                detail = if (action == ShortcutAction.QuickAsk) stringResource(Res.string.desktop_quick_ask_shortcut_detail, settings.assistantName) else null,
            )
            if (action == ShortcutAction.QuickAsk && combo != null && shortcutTaken && vm.recording != action) {
                Notice(stringResource(Res.string.desktop_quick_ask_taken, combo.label()), Palette.SparkAmber)
            }
            if (vm.recording == action) RecordingHint(vm, action)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                vm.stopRecording()
                vm.update { resetShortcuts() }
            }) { Text(stringResource(Res.string.desktop_shortcuts_reset)) }
        }
    }
}

@Composable
private fun ShortcutRow(vm: MainViewModel, action: ShortcutAction, combo: KeyCombo?, detail: String?) {
    val recording = vm.recording == action
    val accent = LocalAccent.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(recording) { if (recording) runCatching { focus.requestFocus() } }
    val title = stringResource(action.title)
    val keys = if (recording) stringResource(Res.string.desktop_shortcut_press) else combo?.label() ?: stringResource(Res.string.desktop_shortcut_none)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        Box(
            Modifier
                .widthIn(min = 120.dp)
                .outlined(
                    RoundedCornerShape(12.dp),
                    fill = if (recording) accent.color.copy(alpha = 0.16f) else Palette.Surface,
                    outline = if (recording) accent.color.copy(alpha = 0.7f) else Palette.Outline,
                )
                .focusRequester(focus)
                // Clicking anything else that takes the focus ends the wait.
                .onFocusChanged { if (!it.hasFocus && vm.recording == action) vm.stopRecording() }
                .clickable(role = Role.Button) { if (recording) vm.stopRecording() else vm.startRecording(action) }
                .semantics { contentDescription = "$title: $keys" }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                keys,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    recording -> accent.soft
                    combo == null -> Palette.TextTertiary
                    else -> Palette.TextPrimary
                },
            )
        }
    }
}

@Composable
private fun RecordingHint(vm: MainViewModel, action: ShortcutAction) {
    val problem = vm.recordingProblem
    val text = when (problem?.first) {
        null -> stringResource(Res.string.desktop_shortcut_recording)
        ShortcutProblem.NeedsModifier -> stringResource(
            if (action.global) Res.string.desktop_shortcut_needs_modifier_global else Res.string.desktop_shortcut_needs_modifier,
            problem.second.label(),
        )
        ShortcutProblem.Reserved -> stringResource(Res.string.desktop_shortcut_reserved, problem.second.label())
        ShortcutProblem.InUse -> {
            val owner = vm.prefs.value.shortcuts.entries.firstOrNull { it.value == problem.second }?.key
            stringResource(Res.string.desktop_shortcut_in_use, problem.second.label(), owner?.let { stringResource(it.title) }.orEmpty())
        }
    }
    Notice(text, if (problem == null) LocalAccent.current.soft else Palette.SparkAmber)
}

/**
 * Reading aloud: new replies by themselves or not, and the two voices. Each voice downloads once and
 * then offers its speakers, with a preview.
 */
@Composable
fun ReadAloudSection(vm: MainViewModel, settings: AppSettings) {
    val prefs by vm.prefs.collectAsState()
    Section(stringResource(Res.string.desktop_section_read_aloud)) {
        Text(stringResource(Res.string.desktop_read_aloud_intro), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        Toggle(stringResource(Res.string.desktop_read_aloud_auto), stringResource(Res.string.desktop_read_aloud_auto_detail), prefs.readAloud) { on ->
            vm.update { setReadAloud(on) }
        }
        VoiceRow(
            vm = vm,
            engine = DesktopSpeaker.Engine.Kokoro,
            title = stringResource(Res.string.desktop_voice_english),
            engineName = stringResource(Res.string.tts_kokoro),
            voices = Kokoro.VOICES.map { it.id to it.label },
            selected = prefs.englishVoice,
            userName = settings.userName,
            onSelect = { id -> vm.update { setEnglishVoice(id) } },
        )
        VoiceRow(
            vm = vm,
            engine = DesktopSpeaker.Engine.Supertonic,
            title = stringResource(Res.string.desktop_voice_other),
            engineName = stringResource(Res.string.tts_supertonic),
            voices = Supertonic.VOICES.map { it.id to stringResource(if (it.female) Res.string.voice_female else Res.string.voice_male, it.number) },
            selected = prefs.otherVoice,
            userName = settings.userName,
            onSelect = { id -> vm.update { setOtherVoice(id) } },
        )
    }
}

@Composable
private fun VoiceRow(
    vm: MainViewModel,
    engine: DesktopSpeaker.Engine,
    title: String,
    engineName: String,
    voices: List<Pair<String, String>>,
    selected: String,
    userName: String,
    onSelect: (String) -> Unit,
) {
    val model = vm.speaker.store(engine)
    val state by model.state.collectAsState()
    val speakingId by vm.speaker.speakingId.collectAsState()
    val megabytes = ((model.spec.sizeBytes + 500_000) / 1_000_000).toInt()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                Text(
                    when (state) {
                        ModelStore.State.Ready -> stringResource(Res.string.tts_model_ready, engineName)
                        else -> "$engineName · $megabytes MB"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
            }
            when (val s = state) {
                ModelStore.State.Ready -> {
                    VoicePicker(voices, selected, onSelect)
                    val previewId = "preview:$selected"
                    val playing = speakingId == previewId
                    PlainIconButton(
                        if (playing) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                        stringResource(if (playing) Res.string.action_stop_reading else Res.string.tts_preview),
                        onClick = { if (playing) vm.stopReading() else vm.previewVoice(engine, selected) },
                        size = 40.dp, iconSize = 20.dp,
                        tint = if (playing) LocalAccent.current.soft else Palette.Icon,
                    )
                }
                ModelStore.State.Missing -> TextButton(onClick = model::download) { Text(stringResource(Res.string.desktop_action_download)) }
                is ModelStore.State.Downloading -> TextButton(onClick = model::cancel) { Text(stringResource(Res.string.desktop_voice_cancel)) }
                is ModelStore.State.Failed -> TextButton(onClick = model::download) { Text(stringResource(Res.string.desktop_voice_try_again)) }
                ModelStore.State.Crashed -> TextButton(onClick = model::retryAfterCrash) { Text(stringResource(Res.string.desktop_voice_try_again)) }
            }
        }
        when (val s = state) {
            is ModelStore.State.Downloading -> {
                LinearProgressIndicator(
                    progress = { s.fraction },
                    color = LocalAccent.current.soft,
                    trackColor = Palette.Surface,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                Text(stringResource(Res.string.tts_model_downloading, engineName, (s.fraction * 100).roundToInt()), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
            is ModelStore.State.Failed -> Notice(stringResource(Res.string.model_failed, s.message), Palette.Danger, error = true)
            ModelStore.State.Crashed -> Notice(stringResource(Res.string.desktop_voice_crashed, engineName), Palette.SparkAmber)
            else -> Unit
        }
    }
}

@Composable
private fun VoicePicker(voices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = voices.firstOrNull { it.first == selected }?.second ?: voices.first().second
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { open = true }
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = LocalAccent.current.soft)
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = LocalAccent.current.soft, modifier = Modifier.size(18.dp))
        }
        GlassDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            voices.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = Palette.TextPrimary) },
                    trailingIcon = { if (id == selected) Icon(Icons.Rounded.Check, null, tint = LocalAccent.current.soft, modifier = Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp)) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}
