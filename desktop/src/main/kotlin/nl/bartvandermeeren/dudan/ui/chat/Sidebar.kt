package nl.bartvandermeeren.dudan.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.AttachmentNotes
import nl.bartvandermeeren.dudan.data.SessionSummary
import nl.bartvandermeeren.dudan.desktop.ChatGroup
import nl.bartvandermeeren.dudan.desktop.ChatRow
import nl.bartvandermeeren.dudan.desktop.ChatTimes
import nl.bartvandermeeren.dudan.desktop.SnoozeOption
import nl.bartvandermeeren.dudan.desktop.ThreadSort
import nl.bartvandermeeren.dudan.desktop.res.*
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.Screen
import nl.bartvandermeeren.dudan.ui.components.Avatar
import nl.bartvandermeeren.dudan.ui.components.DudanIcons
import nl.bartvandermeeren.dudan.ui.components.GlassDefaults
import nl.bartvandermeeren.dudan.ui.components.GlassDialog
import nl.bartvandermeeren.dudan.ui.components.GlassDropdownMenu
import nl.bartvandermeeren.dudan.ui.components.GlassMenuItem
import nl.bartvandermeeren.dudan.ui.components.PlainIconButton
import nl.bartvandermeeren.dudan.ui.components.glass
import nl.bartvandermeeren.dudan.ui.components.outlined
import nl.bartvandermeeren.dudan.ui.components.pane
import nl.bartvandermeeren.dudan.ui.theme.LocalAccent
import nl.bartvandermeeren.dudan.ui.theme.Palette
import org.jetbrains.compose.resources.stringResource

/**
 * The chat list beside the chat, after T3 Code's sidebar: search, sort and a new chat on top, the open
 * chats under them, and Working, Snoozed and Settled folded along the bottom. A chat's menu (right
 * click) pins, snoozes, settles, renames and deletes it.
 */
@Composable
fun Sidebar(vm: MainViewModel, settings: AppSettings, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val sessions by vm.sessions.collectAsState()
    val groups by vm.chatGroups.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val query by vm.searchQuery.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val now by vm.now.collectAsState()
    // A search looks through every group, folded or not.
    val collapsed = if (query.isBlank()) vm.collapsedGroups else emptySet()

    Column(
        modifier
            .fillMaxHeight()
            // A glass card on the sky beside the chat, like the panels in Superhuman's hero.
            .padding(start = 10.dp, top = 8.dp, bottom = 10.dp)
            .glass(GlassDefaults.CardShape),
    ) {
        Row(
            // The buttons share the sort button's size and right edge, so the two rows line up.
            Modifier.fillMaxWidth().height(60.dp).padding(start = 22.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(settings.assistantName, style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (sessions.loading) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.TextSecondary, modifier = Modifier.size(14.dp))
                }
            }
            HoverLabel(stringResource(Res.string.new_chat)) {
                PlainIconButton(DudanIcons.NewChat, stringResource(Res.string.new_chat), onClick = { vm.newChat() }, size = 38.dp, iconSize = 20.dp)
            }
            HoverLabel(stringResource(Res.string.action_close_sidebar)) {
                PlainIconButton(DudanIcons.SidePanel, stringResource(Res.string.action_close_sidebar), onClick = onClose, size = 38.dp, iconSize = 20.dp)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SearchField(
                query = query,
                onChange = { vm.searchQuery.value = it },
                focus = vm.focusSearch,
                onFocused = { vm.focusSearch = false },
                onSubmit = { groups.visible(collapsed).firstOrNull()?.let { vm.openSession(it.session.id) } },
                modifier = Modifier.weight(1f),
            )
            SortButton(prefs.threadSort) { sort -> vm.update { setThreadSort(sort) } }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val bottomMax = maxHeight * 0.55f
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    // The top fade stays within the top padding, so a selected first row is never faded at rest.
                    Modifier.weight(1f).fillMaxWidth().fadeEdges(top = 8.dp, bottom = 12.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 8.dp),
                ) {
                    val empty = groups.open.isEmpty() && groups.working.isEmpty() && groups.snoozed.isEmpty() && groups.settled.isEmpty()
                    if (empty && sessions.loadedOnce && !sessions.loading) {
                        item {
                            Text(
                                when {
                                    query.isNotBlank() -> stringResource(Res.string.desktop_no_matches)
                                    else -> sessions.error ?: stringResource(Res.string.no_chats)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.TextTertiary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                    }
                    chatRows(vm, groups.open, currentId, now)
                }
                // Docked along the bottom, as in T3 Code; they scroll on their own once they fill half the list.
                if (groups.working.isNotEmpty() || groups.snoozed.isNotEmpty() || groups.settled.isNotEmpty()) {
                    // A hairline keeps the open chats, which scroll under it, apart from the groups.
                    Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).height(1.dp).background(Palette.Hairline))
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = bottomMax).fadeEdges(top = 6.dp, bottom = 6.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp),
                    ) {
                        listOf(ChatGroup.Working to Res.string.desktop_group_working, ChatGroup.Snoozed to Res.string.desktop_group_snoozed, ChatGroup.Settled to Res.string.desktop_group_settled)
                            .forEach { (group, label) ->
                                val rows = groups.rows(group)
                                if (rows.isEmpty()) return@forEach
                                item(key = "group:$group") {
                                    GroupHeader(stringResource(label), rows.size, open = group !in collapsed) { vm.toggleGroup(group) }
                                }
                                if (group !in collapsed) chatRows(vm, rows, currentId, now)
                            }
                    }
                }
            }
        }

        // The avatar opens Settings, as it does on the phone's sidebar.
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            val settingsLabel = stringResource(Res.string.settings)
            HoverLabel(settingsLabel) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { vm.screen = Screen.Settings }
                        .semantics { contentDescription = settingsLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(settings.userName.ifBlank { "?" }, size = 40.dp, modifier = Modifier.clearAndSetSemantics { })
                }
            }
        }
    }
}

private fun LazyListScope.chatRows(vm: MainViewModel, rows: List<ChatRow>, currentId: String, now: Long) {
    items(rows, key = { it.session.id }) { row ->
        ChatRowItem(vm, row, selected = row.session.id == currentId, now = now)
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, focus: Boolean, onFocused: () -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) runCatching { requester.requestFocus() }
        onFocused()
    }
    val accent = LocalAccent.current
    val label = stringResource(Res.string.search_chats)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.TextPrimary),
        cursorBrush = SolidColor(accent.soft),
        modifier = modifier
            .focusRequester(requester)
            .semantics { contentDescription = label }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    onSubmit()
                    true
                } else {
                    false
                }
            },
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .outlined(RoundedCornerShape(14.dp), fill = Palette.Surface, outline = Palette.Hairline)
                    .padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(Res.string.desktop_search), style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary, maxLines = 1)
                    field()
                }
                if (query.isNotEmpty()) {
                    PlainIconButton(Icons.Rounded.Close, stringResource(Res.string.cancel), onClick = { onChange("") }, size = 30.dp, iconSize = 16.dp)
                }
            }
        },
    )
}

@Composable
private fun SortButton(selected: ThreadSort, onSelect: (ThreadSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.desktop_sort)
    Box {
        HoverLabel(label) { PlainIconButton(Icons.AutoMirrored.Rounded.Sort, label, onClick = { open = true }, size = 38.dp, iconSize = 20.dp) }
        GlassDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                ThreadSort.Activity to Res.string.desktop_sort_activity,
                ThreadSort.Created to Res.string.desktop_sort_created,
                ThreadSort.Title to Res.string.desktop_sort_title,
            ).forEach { (sort, name) ->
                DropdownMenuItem(
                    text = { Text(stringResource(name), color = Palette.TextPrimary) },
                    trailingIcon = { if (sort == selected) Icon(Icons.Rounded.Check, null, tint = LocalAccent.current.soft, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        open = false
                        onSelect(sort)
                    },
                )
            }
        }
    }
}

/** "Working   2 ⌄": a group's name and size, smaller and quieter than a chat's title; a click folds it. */
@Composable
private fun GroupHeader(label: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .semantics(mergeDescendants = true) {}
            // The name lines up with the titles of the rows under it.
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.3.sp), color = Palette.TextTertiary, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        Spacer(Modifier.width(4.dp))
        Icon(if (open) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Palette.TextTertiary, modifier = Modifier.size(18.dp))
    }
}

/** Selected rows sit on a quiet pane; the rest are bare text on the sidebar's glass. */
private fun Modifier.selectedPane(selected: Boolean, shape: Shape): Modifier =
    if (selected) pane(shape, Palette.Surface, outline = Palette.Hairline) else clip(shape)

/** Icon-only buttons say what they are when the pointer rests on them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoverLabel(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}

/**
 * One chat: its title and how long ago it was active, and under it what it's doing or how it began.
 * Unread chats wear a dot in the accent and a heavier title.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChatRowItem(vm: MainViewModel, row: ChatRow, selected: Boolean, now: Long) {
    val session = row.session
    var menu by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val title = session.displayTitle.ifBlank { stringResource(Res.string.untitled_chat) }
    val status = statusLine(vm, row, now)
    val unreadLabel = stringResource(Res.string.desktop_unread)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 1.dp)
                .heightIn(min = 44.dp)
                .selectedPane(selected, RoundedCornerShape(16.dp))
                .clickable { vm.openSession(session.id) }
                // The chat's menu is a right click away.
                .onPointerEvent(PointerEventType.Press) { if (it.buttons.isSecondaryPressed) menu = true }
                .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Superhuman's selection mark: a short bar in the accent at the start of the row. Unread is a dot there.
            Box(Modifier.width(8.dp), contentAlignment = Alignment.Center) {
                when {
                    selected -> Box(Modifier.width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(accent.color))
                    row.unread -> Box(Modifier.size(7.dp).clip(CircleShape).background(accent.soft).semantics { contentDescription = unreadLabel })
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = if (row.unread) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (selected || row.unread) Palette.TextPrimary else Palette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TrailingTime(row, now)
                }
                if (status != null || session.pinned || session.jobId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (row.group == ChatGroup.Working) {
                            CircularProgressIndicator(strokeWidth = 1.5.dp, color = accent.soft, modifier = Modifier.size(10.dp))
                            Spacer(Modifier.width(2.dp))
                        }
                        Text(
                            status.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.group == ChatGroup.Working) Palette.TextSecondary else Palette.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // A chat Clark keeps for a scheduled task's results.
                        if (session.jobId != null) {
                            Icon(Icons.Outlined.Schedule, contentDescription = stringResource(Res.string.session_scheduled_task), tint = Palette.TextTertiary, modifier = Modifier.size(15.dp))
                        }
                        if (session.pinned) Icon(Icons.Outlined.PushPin, contentDescription = stringResource(Res.string.pin), tint = Palette.TextTertiary, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
        ChatMenu(vm, session, expanded = menu, onDismiss = { menu = false })
    }
}

/** Snoozed chats show when they're back, in the accent and with an alarm, as T3 Code does; the rest how long ago they moved. */
@Composable
private fun TrailingTime(row: ChatRow, now: Long) {
    val until = row.snoozedUntil
    if (until != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Outlined.Alarm, contentDescription = null, tint = LocalAccent.current.soft, modifier = Modifier.size(13.dp))
            Text(ChatTimes.left(until, now), style = MaterialTheme.typography.labelSmall, color = LocalAccent.current.soft)
        }
    } else {
        Text(ChatTimes.ago(row.session.lastActive, now, stringResource(Res.string.desktop_time_now)), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
    }
}

/** What the second line says: what the chat is doing while it works, else how it began when the title doesn't say. */
@Composable
private fun statusLine(vm: MainViewModel, row: ChatRow, now: Long): String? {
    val activity = row.activity
    if (activity != null) {
        val conversation by remember(row.session.id) { vm.engine.conversation(row.session.id) }.collectAsState()
        val waiting = conversation.messages.lastOrNull()?.approval != null
        val running = ChatTimes.running(activity.since, now)
        return when {
            waiting -> stringResource(Res.string.desktop_status_waiting, running)
            activity.turn -> stringResource(Res.string.desktop_status_working, running)
            else -> stringResource(Res.string.desktop_status_background, running)
        }
    }
    return firstLine(row.session)
}

/** The chat's first question, when the title isn't simply that. */
private fun firstLine(session: SessionSummary): String? =
    session.preview?.let(AttachmentNotes::clean)?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        ?.takeIf { !session.displayTitle.startsWith(it.take(40)) }

private enum class MenuPage { Main, Snooze }

/**
 * A chat's menu, from a right click on its row or from the chat's own ⋯: pin, read state, snooze,
 * settle, rename and delete. Snooze opens its times in the same menu.
 */
@Composable
fun ChatMenu(vm: MainViewModel, session: SessionSummary?, expanded: Boolean, onDismiss: () -> Unit, deleteId: String? = session?.id) {
    var page by remember { mutableStateOf(MenuPage.Main) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // From every chat, not the search's: a chat the search hides is still settled or snoozed.
    val groups by vm.allChats.collectAsState()
    val now by vm.now.collectAsState()
    val row = session?.let { groups.row(it.id) }
    fun close() {
        onDismiss()
        page = MenuPage.Main
    }
    GlassDropdownMenu(expanded = expanded, onDismissRequest = ::close) {
        when (page) {
            MenuPage.Main -> {
                if (session != null) {
                    MenuEntry(Icons.Outlined.PushPin, stringResource(if (session.pinned) Res.string.unpin else Res.string.pin)) {
                        close()
                        vm.setPinned(session.id, !session.pinned)
                    }
                    if (row?.unread == true) {
                        MenuEntry(Icons.Outlined.MarkChatRead, stringResource(Res.string.desktop_mark_read)) { close(); vm.markRead(session.id) }
                    } else {
                        MenuEntry(Icons.Outlined.MarkChatUnread, stringResource(Res.string.desktop_mark_unread)) { close(); vm.markUnread(session.id) }
                    }
                    if (row?.group == ChatGroup.Snoozed) {
                        MenuEntry(Icons.Outlined.Snooze, stringResource(Res.string.desktop_unsnooze)) { close(); vm.unsnooze(session.id) }
                    } else {
                        MenuEntry(Icons.Outlined.Snooze, stringResource(Res.string.desktop_snooze), more = true) { page = MenuPage.Snooze }
                    }
                    if (row?.group == ChatGroup.Settled) {
                        MenuEntry(Icons.Outlined.Inbox, stringResource(Res.string.desktop_reopen)) { close(); vm.unsettle(session.id) }
                    } else {
                        MenuEntry(Icons.Outlined.TaskAlt, stringResource(Res.string.desktop_settle)) { close(); vm.settle(session.id) }
                    }
                    MenuEntry(Icons.Outlined.DriveFileRenameOutline, stringResource(Res.string.rename)) { close(); renaming = true }
                }
                if (deleteId != null) {
                    MenuEntry(Icons.Outlined.Delete, stringResource(Res.string.delete)) { close(); confirmDelete = true }
                }
            }
            MenuPage.Snooze -> if (session != null) {
                listOf(
                    SnoozeOption.OneHour to Res.string.desktop_snooze_hour,
                    SnoozeOption.ThreeHours to Res.string.desktop_snooze_three_hours,
                    SnoozeOption.Tomorrow to Res.string.desktop_snooze_tomorrow,
                    SnoozeOption.NextWeek to Res.string.desktop_snooze_next_week,
                ).forEach { (option, label) ->
                    GlassMenuItem(Icons.Outlined.Alarm, stringResource(label), snoozeTime(option.until(now), now)) {
                        close()
                        vm.snooze(session.id, option)
                    }
                }
            }
        }
    }
    if (renaming && session != null) {
        RenameDialog(session.displayTitle, onDismiss = { renaming = false }, onConfirm = {
            renaming = false
            vm.renameSession(session.id, it)
        })
    }
    if (confirmDelete && deleteId != null) {
        GlassDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.delete_chat_title)) },
            text = { Text(stringResource(Res.string.delete_chat_body, session?.displayTitle.orEmpty())) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSession(deleteId) }) { Text(stringResource(Res.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

@Composable
private fun MenuEntry(icon: ImageVector, label: String, more: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = Palette.TextPrimary) },
        leadingIcon = { Icon(icon, null, tint = Palette.Icon) },
        trailingIcon = if (more) ({ Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.TextTertiary, modifier = Modifier.size(18.dp)) }) else null,
        onClick = onClick,
    )
}

/** When a snooze ends: "21:40" today, else "Thu 9:00". */
private fun snoozeTime(untilMs: Long, nowMs: Long): String {
    val zone = ZoneId.systemDefault()
    val until = Instant.ofEpochMilli(untilMs).atZone(zone)
    val sameDay = until.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return until.format(DateTimeFormatter.ofPattern(if (sameDay) "H:mm" else "EEE H:mm", Locale.getDefault()))
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.rename_chat)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LocalAccent.current.color,
                    unfocusedBorderColor = Palette.Outline,
                    focusedContainerColor = Palette.Surface,
                    unfocusedContainerColor = Palette.Surface,
                    cursorColor = LocalAccent.current.soft,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
