package nl.bartvandermeeren.dudan.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.bartvandermeeren.dudan.ui.theme.LocalReduceTransparency
import nl.bartvandermeeren.dudan.ui.theme.Palette

/**
 * The phone's bottom sheet as a panel in the middle of the window, with the same lit edge. It stays
 * open until a choice closes it, Esc, or a click beside it.
 */
@Composable
fun GlassSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val edge = if (LocalReduceTransparency.current) GlassDefaults.SolidBorder else GlassDefaults.Border
    Overlay(onDismiss) {
        Column(
            Modifier
                .widthIn(min = 360.dp, max = 480.dp)
                .heightIn(max = 680.dp)
                .clip(shape)
                .background(windowGlass(Palette.Sheet, Palette.SheetSolid))
                .border(edge, shape)
                // Clicks inside the panel don't reach the scrim behind it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(top = 12.dp, bottom = 12.dp),
            content = content,
        )
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
