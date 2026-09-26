package nl.bartvandermeeren.aight.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.chat.SessionsState
import nl.bartvandermeeren.aight.data.SessionSummary
import nl.bartvandermeeren.aight.ui.components.Avatar
import nl.bartvandermeeren.aight.ui.components.AightIcons
import nl.bartvandermeeren.aight.ui.components.GlassDefaults
import nl.bartvandermeeren.aight.ui.components.GlassDialog
import nl.bartvandermeeren.aight.ui.components.GlassDropdownMenu
import nl.bartvandermeeren.aight.ui.components.PlainIconButton
import nl.bartvandermeeren.aight.ui.components.glass
import nl.bartvandermeeren.aight.ui.components.pane
import nl.bartvandermeeren.aight.ui.theme.Palette

enum class SidebarMode { Docked, Overlay }

@Composable
fun Sidebar(
    mode: SidebarMode,
    assistantName: String,
    userName: String,
    serverLabel: String,
    sessions: SessionsState,
    currentSessionId: String,
    onNewChatSelected: Boolean,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onSearch: () -> Unit,
    onSkills: () -> Unit,
    onJobs: () -> Unit,
    onOpenSession: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onTogglePin: (SessionSummary) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frame = when (mode) {
        // A glass card on the sky beside the chat, like the panels in Superhuman's hero.
        SidebarMode.Docked -> Modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 10.dp, top = 8.dp, bottom = 10.dp)
            .glass(GlassDefaults.CardShape)
        // A sheet of frosted sky over the whole chat on the cover screen.
        SidebarMode.Overlay -> Modifier
            .glass(RectangleShape, Palette.SidebarGlass, blurRadius = GlassDefaults.SheetBlur, border = false, solid = Palette.SheetSolid)
            .statusBarsPadding()
            .navigationBarsPadding()
    }
    Column(modifier.fillMaxHeight().then(frame)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(assistantName, style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp), color = Palette.TextPrimary)
            Spacer(Modifier.weight(1f))
            PlainIconButton(
                if (mode == SidebarMode.Docked) AightIcons.SidePanel else Icons.Rounded.Close,
                stringResource(R.string.action_close_sidebar),
                onClick = onClose,
            )
        }
        Spacer(Modifier.height(12.dp))
        NavItem(AightIcons.NewChat, stringResource(R.string.new_chat), selected = onNewChatSelected, onClick = onNewChat)
        NavItem(Icons.Rounded.Search, stringResource(R.string.search_chats), onClick = onSearch)
        NavItem(Icons.Outlined.AutoAwesome, stringResource(R.string.skills), onClick = onSkills)
        NavItem(Icons.Outlined.Schedule, stringResource(R.string.scheduled_tasks), onClick = onJobs)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.padding(start = 32.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.recent), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
            Spacer(Modifier.weight(1f))
            if (sessions.loading) CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.TextSecondary, modifier = Modifier.size(14.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (sessions.items.isEmpty() && sessions.loadedOnce && !sessions.loading) {
                item {
                    Text(
                        sessions.error ?: stringResource(R.string.no_chats),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextTertiary,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                    )
                }
            }
            items(sessions.items, key = { it.id }) { session ->
                SessionItem(
                    session = session,
                    selected = session.id == currentSessionId,
                    onClick = { onOpenSession(session.id) },
                    onRename = { onRename(session.id, it) },
                    onDelete = { onDelete(session.id) },
                    onTogglePin = { onTogglePin(session) },
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(userName.ifBlank { "?" }, size = 48.dp)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(
                    userName.ifBlank { stringResource(R.string.you) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    serverLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlainIconButton(Icons.Outlined.Settings, stringResource(R.string.settings), onClick = onSettings)
        }
    }
}

/** Selected rows sit on a quiet pane; the rest are bare text on the sidebar's glass. */
private fun Modifier.selectedPane(selected: Boolean, shape: Shape): Modifier =
    if (selected) pane(shape, Palette.Surface, outline = Palette.Hairline) else clip(shape)

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(56.dp)
            .selectedPane(selected, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.Icon, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = Palette.TextPrimary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItem(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(52.dp)
                .selectedPane(selected, RoundedCornerShape(26.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Superhuman's selection mark: a short bar of light at the start of the row.
            Box(
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (selected) Palette.Primary else Color.Transparent),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                session.displayTitle.ifBlank { stringResource(R.string.untitled_chat) },
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                color = if (selected) Palette.TextPrimary else Palette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (session.pinned) {
                Icon(Icons.Outlined.PushPin, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
        GlassDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (session.pinned) R.string.unpin else R.string.pin)) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                onClick = { menu = false; onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                onClick = { menu = false; renaming = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { menu = false; confirmDelete = true },
            )
        }
    }
    if (renaming) {
        RenameDialog(session.displayTitle, onDismiss = { renaming = false }, onConfirm = { renaming = false; onRename(it) })
    }
    if (confirmDelete) {
        GlassDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_chat_title)) },
            text = { Text(stringResource(R.string.delete_chat_body, session.displayTitle)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_chat)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Link,
                    unfocusedBorderColor = Palette.Outline,
                    focusedContainerColor = Palette.Surface,
                    unfocusedContainerColor = Palette.Surface,
                    cursorColor = Palette.Link,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
