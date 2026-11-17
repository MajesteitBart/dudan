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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.ModelChoice
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.data.ReasoningEffort
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.components.GlassDropdownMenu
import nl.bartvandermeeren.dudan.ui.components.GlassSheet
import nl.bartvandermeeren.dudan.ui.components.Segmented
import nl.bartvandermeeren.dudan.ui.components.SheetChoice
import nl.bartvandermeeren.dudan.ui.theme.LocalHarness
import nl.bartvandermeeren.dudan.ui.theme.Palette

@Composable
fun ModelPickerSheet(vm: MainViewModel, settings: AppSettings, initialProfile: ModelProfile, onDismiss: () -> Unit) {
    val catalog by vm.modelCatalog.collectAsStateWithLifecycle()
    val error by vm.modelCatalogError.collectAsStateWithLifecycle()
    var profile by remember { mutableStateOf(initialProfile) }
    val selected = settings.modelFor(profile)
    GlassSheet(onDismiss) {
        // Two defaults: regular chats, and quick chats started from the assistant.
        Segmented(
            options = ModelProfile.entries.map { it to stringResource(if (it == ModelProfile.Chats) R.string.profile_chats else R.string.profile_assistant) },
            selected = profile,
            onSelect = { profile = it },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            stringResource(if (profile == ModelProfile.Chats) R.string.profile_chats_detail else R.string.profile_assistant_detail),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextSecondary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp),
        )
        val runsOn = catalog?.optionFor(selected)
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
            val efforts = runsOn?.efforts.orEmpty()
            if (efforts.isNotEmpty()) {
                OptionPicker(
                    title = stringResource(R.string.reasoning_title),
                    selected = selected.effort,
                    choices = listOf(null) + efforts,
                    label = { effortLabel(it) },
                    detail = { effort -> effort?.let { runsOn?.effortDescriptions?.get(it.wire) } },
                    onSelect = { vm.setModel(profile, selected.copy(effort = it)) },
                )
            }
            val tiers = runsOn?.tiers.orEmpty()
            if (tiers.isNotEmpty()) {
                OptionPicker(
                    title = stringResource(R.string.service_tier),
                    selected = selected.serviceTier,
                    choices = listOf(null) + tiers.map { it.id },
                    label = { id -> tiers.firstOrNull { it.id == id && runsOn?.serviceTiers != null }?.name ?: tierLabel(id) },
                    detail = { id -> tiers.firstOrNull { it.id == id }?.description },
                    onSelect = { vm.setModel(profile, selected.copy(serviceTier = it)) },
                )
            }
        }
        fun keepOptions(choice: ModelChoice): ModelChoice {
            val moved = choice.copy(effort = selected.effort, serviceTier = selected.serviceTier)
            return catalog?.supported(moved) ?: moved
        }
        Text(
            stringResource(R.string.model_picker_title),
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextSecondary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 6.dp),
        )
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            item {
                SheetChoice(
                    title = stringResource(R.string.model_default, LocalHarness.current.name),
                    subtitle = catalog?.currentModel?.let { stringResource(R.string.model_default_detail, it) },
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

@Composable
private fun <T> OptionPicker(
    title: String,
    selected: T,
    choices: List<T>,
    label: @Composable (T) -> String,
    detail: (T) -> String? = { null },
    onSelect: (T) -> Unit,
) {
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
                Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                Text(label(selected), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = Palette.TextSecondary)
        }
        GlassDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(label(choice), color = Palette.TextPrimary)
                            detail(choice)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
                        }
                    },
                    trailingIcon = {
                        if (choice == selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Palette.TextPrimary, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        open = false
                        onSelect(choice)
                    },
                )
            }
        }
    }
}

@Composable
fun effortLabel(effort: ReasoningEffort?): String = stringResource(
    when (effort) {
        null -> R.string.reasoning_default
        ReasoningEffort.Off -> R.string.effort_off
        ReasoningEffort.Low -> R.string.effort_low
        ReasoningEffort.Medium -> R.string.effort_medium
        ReasoningEffort.High -> R.string.effort_high
        ReasoningEffort.ExtraHigh -> R.string.effort_xhigh
        ReasoningEffort.Minimal -> R.string.effort_minimal
        ReasoningEffort.Ultra -> R.string.effort_ultra
        ReasoningEffort.Max -> R.string.effort_max
    }, LocalHarness.current.name,
)

@Composable
private fun tierLabel(id: String?): String = when (id) {
    null -> stringResource(R.string.tier_standard)
    "priority" -> stringResource(R.string.fast_short)
    else -> id.replaceFirstChar { it.uppercase() }
}

/** What [choice] sets beyond the model, such as "Low · Fast"; empty when it leaves both to Hermes. */
@Composable
fun modelModeLabel(choice: ModelChoice): String = listOfNotNull(
    choice.effort?.let { effortLabel(it) },
    choice.serviceTier?.let { tierLabel(it) },
).joinToString(" · ")
