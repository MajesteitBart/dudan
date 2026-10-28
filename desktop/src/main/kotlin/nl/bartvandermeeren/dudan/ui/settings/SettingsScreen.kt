package nl.bartvandermeeren.dudan.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.desktop.DesktopInfo
import nl.bartvandermeeren.dudan.desktop.res.*
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.chat.ModelPickerSheet
import nl.bartvandermeeren.dudan.ui.chat.modelModeLabel
import nl.bartvandermeeren.dudan.ui.chat.prettyModelName
import nl.bartvandermeeren.dudan.ui.components.PlainIconButton
import nl.bartvandermeeren.dudan.ui.components.Toggle
import nl.bartvandermeeren.dudan.ui.components.glass
import nl.bartvandermeeren.dudan.ui.components.outlined
import nl.bartvandermeeren.dudan.ui.theme.Accent
import nl.bartvandermeeren.dudan.ui.theme.LocalAccent
import nl.bartvandermeeren.dudan.ui.theme.Palette
import nl.bartvandermeeren.dudan.ui.theme.Sky
import org.jetbrains.compose.resources.stringResource

/**
 * Settings, as on the phone: the connection first, then the defaults, reading aloud and the look. The
 * phone's voice input, assistant and phone control sections have no counterpart here; keyboard shortcuts
 * take the assistant's place. [shortcutTaken] says another app holds the quick question's keys. The first
 * start shows [SetupScreen] instead.
 */
@Composable
fun SettingsScreen(vm: MainViewModel, settings: AppSettings, onBack: () -> Unit, shortcutTaken: Boolean = false) {
    val scope = rememberCoroutineScope()
    val form = rememberConnectionForm(vm, settings)
    val prefs by vm.prefs.collectAsState()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(64.dp)) {
                PlainIconButton(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    stringResource(Res.string.action_back),
                    onClick = onBack,
                    modifier = Modifier.outlined(CircleShape),
                    size = 44.dp,
                    iconSize = 24.dp,
                )
                Spacer(Modifier.size(14.dp))
                Text(stringResource(Res.string.settings), style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)
            }

            Section(stringResource(Res.string.section_connection)) {
                ConnectionFields(form)
                ConnectionActions(form, stringResource(Res.string.save_and_test))
            }

            Section(stringResource(Res.string.section_models)) {
                ModelProfile.entries.forEach { profile -> ModelDefaultRow(vm, settings, profile) }
            }

            Section(stringResource(Res.string.desktop_section_replies)) {
                Toggle(stringResource(Res.string.rich_replies), stringResource(Res.string.rich_replies_detail), settings.richReplies) { on ->
                    vm.update { setRichReplies(on) }
                }
            }

            ReadAloudSection(vm, settings)

            Section(stringResource(Res.string.section_appearance)) {
                SkyPicker(Sky.from(settings.sky)) { sky -> vm.update { setSky(sky.name) } }
                AccentPicker(Accent.from(settings.accent)) { accent -> vm.update { setAccent(accent.name) } }
                TextSizeRow(vm, prefs.textScale)
                Toggle(stringResource(Res.string.reduce_transparency), stringResource(Res.string.desktop_reduce_transparency_detail), settings.reduceTransparency) { on ->
                    vm.update { setReduceTransparency(on) }
                }
            }

            ShortcutsSection(vm, settings, shortcutTaken)

            Section(stringResource(Res.string.section_history)) {
                Toggle(stringResource(Res.string.show_all_channels), stringResource(Res.string.show_all_channels_detail), settings.showAllChannels) { on ->
                    scope.launch {
                        vm.store.setShowAllChannels(on)
                        vm.refreshSessions()
                    }
                }
                // Inside the card: on the bare lower sky this small print measured 1.7:1.
                Text("dudan for Windows ${DesktopInfo.version}", style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One default model: what it's used for, what's picked, and a tap to change it. */
@Composable
private fun ModelDefaultRow(vm: MainViewModel, settings: AppSettings, profile: ModelProfile) {
    var picking by remember { mutableStateOf(false) }
    val catalog by vm.modelCatalog.collectAsState()
    val choice = settings.modelFor(profile)
    val name = choice.label?.takeIf { it != choice.model }
        ?: choice.model?.let(::prettyModelName)
        ?: catalog?.currentModel?.let { stringResource(Res.string.model_server_default_named, prettyModelName(it)) }
        ?: stringResource(Res.string.model_default)
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
                stringResource(if (profile == ModelProfile.Chats) Res.string.profile_chats else Res.string.profile_assistant),
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextPrimary,
            )
            Text(
                stringResource(if (profile == ModelProfile.Chats) Res.string.profile_chats_detail else Res.string.desktop_profile_assistant_detail),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
        }
        Text(if (mode.isEmpty()) name else "$name · $mode", style = MaterialTheme.typography.labelLarge, color = LocalAccent.current.soft)
    }
    if (picking) ModelPickerSheet(vm, settings, initialProfile = profile, onDismiss = { picking = false })
}

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
        PickerLabel(stringResource(Res.string.background), stringResource(selected.label))
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

/** A swatch per accent, as a radio group: the chosen one wears a ring and a check, and its name shows beside the label. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(selected: Accent, onSelect: (Accent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PickerLabel(stringResource(Res.string.accent_color), stringResource(selected.label))
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
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
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
internal fun Notice(text: String, tint: Color, ok: Boolean = false, error: Boolean = false) {
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
