package nl.bartvandermeeren.aight.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.data.AppSettings
import nl.bartvandermeeren.aight.data.ModelChoice
import nl.bartvandermeeren.aight.data.ModelProfile
import nl.bartvandermeeren.aight.data.ReasoningMode
import nl.bartvandermeeren.aight.ui.MainViewModel
import nl.bartvandermeeren.aight.ui.components.BlurBehindWindow
import nl.bartvandermeeren.aight.ui.components.GlassDefaults
import nl.bartvandermeeren.aight.ui.components.glass
import nl.bartvandermeeren.aight.ui.theme.Palette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(vm: MainViewModel, settings: AppSettings, initialProfile: ModelProfile, onDismiss: () -> Unit) {
    val catalog by vm.modelCatalog.collectAsStateWithLifecycle()
    val error by vm.modelCatalogError.collectAsStateWithLifecycle()
    var profile by remember { mutableStateOf(initialProfile) }
    val selected = settings.modelFor(profile)
    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = shape,
        containerColor = Palette.Sheet,
        scrimColor = Palette.BackdropEdge.copy(alpha = 0.45f),
        // The content draws the lit edge, so it also takes the handle and the navigation bar inset;
        // otherwise the edge would stop short of both.
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        BlurBehindWindow()
        Column(Modifier.border(GlassDefaults.Border, shape).navigationBarsPadding().padding(bottom = 12.dp)) {
            BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
            // Two defaults: regular chats, and quick chats started from the assistant.
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelProfile.entries.forEach { option ->
                    Pill(
                        label = stringResource(if (option == ModelProfile.Chats) R.string.profile_chats else R.string.profile_assistant),
                        active = profile == option,
                        onClick = { profile = option },
                    )
                }
            }
            Text(
                stringResource(if (profile == ModelProfile.Chats) R.string.profile_chats_detail else R.string.profile_assistant_detail),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp),
            )
            Text(
                stringResource(R.string.reasoning_title),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp),
            )
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReasoningMode.entries.forEach { mode ->
                    Pill(
                        label = when (mode) {
                            ReasoningMode.Default -> stringResource(R.string.reasoning_default)
                            ReasoningMode.Fast -> stringResource(R.string.reasoning_fast)
                            ReasoningMode.Extended -> stringResource(R.string.reasoning_extended)
                        },
                        active = selected.reasoning == mode,
                        onClick = { vm.setModel(profile, selected.copy(reasoning = mode)) },
                    )
                }
            }
            Text(
                stringResource(R.string.model_picker_title),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 6.dp),
            )
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    ModelRow(
                        title = stringResource(R.string.model_default),
                        subtitle = catalog?.currentModel?.let { stringResource(R.string.model_default_detail, it) },
                        selected = selected.model == null,
                        onClick = { vm.setModel(profile, ModelChoice(reasoning = selected.reasoning)) },
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
                        ModelRow(
                            title = if (option.label == option.model) prettyModelName(option.model) else option.label,
                            subtitle = option.model,
                            selected = selected.model == option.model && (selected.provider == null || selected.provider == option.provider),
                            onClick = { vm.setModel(profile, ModelChoice(option.provider, option.model, option.label, selected.reasoning)) },
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
}

/** A glass toggle chip; the active one takes the orb's violet. */
@Composable
fun Pill(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .glass(CircleShape, if (active) Palette.Button else Palette.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) Palette.ButtonText else Palette.TextPrimary)
    }
}

@Composable
private fun ModelRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .then(if (selected) Modifier.glass(RoundedCornerShape(20.dp), Palette.Surface) else Modifier.clip(RoundedCornerShape(20.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Spacer(Modifier.size(12.dp))
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Palette.Link)
        }
    }
}
