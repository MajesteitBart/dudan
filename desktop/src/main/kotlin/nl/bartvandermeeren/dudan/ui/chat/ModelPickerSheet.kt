package nl.bartvandermeeren.dudan.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import nl.bartvandermeeren.dudan.desktop.res.*
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.ModelChoice
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.data.ReasoningEffort
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.components.GlassDropdownMenu
import nl.bartvandermeeren.dudan.ui.components.GlassSheet
import nl.bartvandermeeren.dudan.ui.components.Segmented
import nl.bartvandermeeren.dudan.ui.components.SheetChoice
import nl.bartvandermeeren.dudan.ui.components.Toggle
import nl.bartvandermeeren.dudan.ui.theme.Palette

@Composable
fun ModelPickerSheet(vm: MainViewModel, settings: AppSettings, initialProfile: ModelProfile, onDismiss: () -> Unit) {
    val catalog by vm.modelCatalog.collectAsState()
    val error by vm.modelCatalogError.collectAsState()
    var profile by remember { mutableStateOf(initialProfile) }
    val selected = settings.modelFor(profile)
    GlassSheet(onDismiss) {
        // Two defaults: regular chats, and quick chats started from the assistant.
        Segmented(
            options = ModelProfile.entries.map { it to stringResource(if (it == ModelProfile.Chats) Res.string.profile_chats else Res.string.profile_assistant) },
            selected = profile,
            onSelect = { profile = it },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            stringResource(if (profile == ModelProfile.Chats) Res.string.profile_chats_detail else Res.string.desktop_profile_assistant_detail),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextSecondary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp),
        )
        // Two separate Hermes options: how long the model thinks, and priority processing. A model
        // that reports no support for one doesn't get its control.
        val runsOn = catalog?.optionFor(selected)
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
            if (runsOn?.reasoning != false) {
                EffortPicker(selected.effort, onSelect = { vm.setModel(profile, selected.copy(effort = it)) })
            }
            if (runsOn?.fast == true) {
                Toggle(stringResource(Res.string.fast_mode), stringResource(Res.string.fast_mode_detail), selected.fast) {
                    vm.setModel(profile, selected.copy(fast = it))
                }
            }
        }
        // Switching models keeps the thinking level and fast mode where the new model takes them.
        fun keepOptions(choice: ModelChoice): ModelChoice {
            val moved = choice.copy(effort = selected.effort, fast = selected.fast)
            return catalog?.supported(moved) ?: moved
        }
        Text(
            stringResource(Res.string.model_picker_title),
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextSecondary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 6.dp),
        )
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            item {
                SheetChoice(
                    title = stringResource(Res.string.model_default),
                    subtitle = catalog?.currentModel?.let { stringResource(Res.string.model_default_detail, it) },
                    selected = selected.model == null,
                    onClick = { vm.setModel(profile, keepOptions(ModelChoice())) },
                )
            }
            val options = catalog?.options.orEmpty()
            val grouped = options.groupBy { it.providerName }
            grouped.forEach { (provider, models) ->
                item(key = "h_$provider") {
                    Text(
                        provider,
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.TextTertiary,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(models, key = { "${it.provider}/${it.model}" }) { option ->
                    SheetChoice(
                        title = if (option.label == option.model) prettyModelName(option.model) else option.label,
                        subtitle = option.model,
                        selected = selected.model == option.model && (selected.provider == null || selected.provider == option.provider),
                        onClick = { vm.setModel(profile, keepOptions(ModelChoice(option.provider, option.model, option.label))) },
                    )
                }
            }
            if (catalog == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        if (error != null) {
                            Text(error!!, color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.TextSecondary, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The thinking level in use, with a menu of the levels Hermes accepts (Hermes' dashboard offers the same). */
@Composable
private fun EffortPicker(selected: ReasoningEffort?, onSelect: (ReasoningEffort?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { open = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(Res.string.reasoning_title), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                Text(effortLabel(selected), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = Palette.TextSecondary)
        }
        GlassDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf(null) + ReasoningEffort.entries).forEach { effort ->
                DropdownMenuItem(
                    text = { Text(effortLabel(effort)) },
                    trailingIcon = {
                        if (effort == selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Palette.TextPrimary, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        open = false
                        onSelect(effort)
                    },
                )
            }
        }
    }
}

@Composable
fun effortLabel(effort: ReasoningEffort?): String = stringResource(
    when (effort) {
        null -> Res.string.reasoning_default
        ReasoningEffort.Off -> Res.string.effort_off
        ReasoningEffort.Low -> Res.string.effort_low
        ReasoningEffort.Medium -> Res.string.effort_medium
        ReasoningEffort.High -> Res.string.effort_high
        ReasoningEffort.ExtraHigh -> Res.string.effort_xhigh
        ReasoningEffort.Max -> Res.string.effort_max
    },
)

/** What [choice] sets beyond the model, such as "Low · Fast"; empty when it leaves both to Hermes. */
@Composable
fun modelModeLabel(choice: ModelChoice): String = listOfNotNull(
    choice.effort?.let { effortLabel(it) },
    stringResource(Res.string.fast_short).takeIf { choice.fast },
).joinToString(" · ")
