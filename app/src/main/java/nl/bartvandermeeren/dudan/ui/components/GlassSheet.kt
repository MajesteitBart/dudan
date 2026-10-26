package nl.bartvandermeeren.dudan.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.bartvandermeeren.dudan.ui.theme.LocalReduceTransparency
import nl.bartvandermeeren.dudan.ui.theme.Palette

/** A bottom sheet of window glass that blurs the screen behind it, with a lit edge and a drag handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val edge = if (LocalReduceTransparency.current) GlassDefaults.SolidBorder else GlassDefaults.Border
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = shape,
        containerColor = windowGlass(Palette.Sheet, Palette.SheetSolid),
        scrimColor = Palette.BackdropEdge.copy(alpha = 0.45f),
        // The content draws the lit edge, so it also takes the handle and the navigation bar inset;
        // otherwise the edge would stop short of both.
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        BlurBehindWindow()
        Column(Modifier.border(edge, shape).navigationBarsPadding().padding(bottom = 12.dp)) {
            BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
            content()
        }
    }
}

/** One choice in a sheet, as in beautifului.dev's model picker: the name, a quieter line under it, a check on the one in use. */
@Composable
fun SheetChoice(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, singleLine: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .then(if (selected) Modifier.pane(RoundedCornerShape(16.dp), Palette.Surface, outline = Palette.Hairline) else Modifier.clip(RoundedCornerShape(16.dp)))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.size(12.dp))
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Palette.TextPrimary, modifier = Modifier.size(20.dp))
        }
    }
}
