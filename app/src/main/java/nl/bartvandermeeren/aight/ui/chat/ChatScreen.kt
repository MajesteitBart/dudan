package nl.bartvandermeeren.aight.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.chat.ChatEngine
import nl.bartvandermeeren.aight.chat.Role
import nl.bartvandermeeren.aight.data.AppSettings
import nl.bartvandermeeren.aight.data.ReasoningMode
import nl.bartvandermeeren.aight.ui.MainViewModel
import nl.bartvandermeeren.aight.ui.components.AightIcons
import nl.bartvandermeeren.aight.ui.components.AightMark
import nl.bartvandermeeren.aight.ui.components.Avatar
import nl.bartvandermeeren.aight.ui.components.Composer
import nl.bartvandermeeren.aight.ui.components.GlassDialog
import nl.bartvandermeeren.aight.ui.components.GlassDropdownMenu
import nl.bartvandermeeren.aight.ui.components.LocalHazeState
import nl.bartvandermeeren.aight.ui.components.PlainIconButton
import nl.bartvandermeeren.aight.ui.components.glass
import nl.bartvandermeeren.aight.ui.theme.Palette

/** Callbacks the activity provides because they need permissions or activity result launchers. */
class ChatHostActions(
    val onMic: () -> Unit,
    val onStopListening: () -> Unit,
    val onSendWhileListening: () -> Unit,
    val onLive: () -> Unit,
    val onPickPhotos: () -> Unit,
    val onTakePhoto: () -> Unit,
)

private val GreetingBrush = Brush.linearGradient(listOf(Palette.TextPrimary, Color(0xFFD6CCFF), Color(0xFFA9BCFF)))

@Composable
fun ChatPane(
    vm: MainViewModel,
    settings: AppSettings,
    showMenuButton: Boolean,
    onMenu: () -> Unit,
    listening: Boolean,
    voiceLevel: Float,
    voiceText: String,
    actions: ChatHostActions,
    modifier: Modifier = Modifier,
) {
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val speakingId by vm.speaker.speakingId.collectAsStateWithLifecycle()
    val catalog by vm.modelCatalog.collectAsStateWithLifecycle()
    val messages = conversation.messages
    val empty = messages.isEmpty() && !conversation.loading
    var showModelPicker by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    val profile = ChatEngine.profileOf(conversation.sessionId)
    // The top bar and composer float over the messages; the list pads itself by their measured height.
    val density = LocalDensity.current
    var topBarHeight by remember { mutableIntStateOf(0) }
    var composerHeight by remember { mutableIntStateOf(0) }
    val topInset = with(density) { topBarHeight.toDp() }
    val bottomInset = with(density) { composerHeight.toDp() }

    Box(modifier.fillMaxSize()) {
        when {
            conversation.loading && messages.isEmpty() -> CircularProgressIndicator(
                color = Palette.TextSecondary,
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
            conversation.loadError != null && messages.isEmpty() -> LoadError(
                conversation.loadError!!,
                onRetry = vm::reloadCurrent,
                modifier = Modifier.padding(top = topInset, bottom = bottomInset),
            )
            empty -> Greeting(settings.userName, Modifier.align(Alignment.Center).padding(top = topInset, bottom = bottomInset))
            else -> MessageList(vm, settings, speakingId, topInset, bottomInset)
        }

        // Assistant chats (side key, overlay) run on the fast model; everything else on the normal one.
        val activeModel = settings.modelFor(profile)
        val modelTitle = activeModel.label?.takeIf { it != activeModel.model }
            ?: (activeModel.model ?: catalog?.currentModel)?.let(::prettyModelName)
            ?: stringResource(R.string.model_default)
        val modelSubtitle = when (activeModel.reasoning) {
            ReasoningMode.Default -> ""
            ReasoningMode.Fast -> stringResource(R.string.reasoning_fast)
            ReasoningMode.Extended -> stringResource(R.string.reasoning_extended)
        }
        TopBar(
            showMenuButton = showMenuButton,
            onMenu = onMenu,
            modelTitle = modelTitle,
            modelSubtitle = modelSubtitle,
            onModelClick = {
                vm.loadModels()
                showModelPicker = true
            },
            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { topBarHeight = it.height },
        ) {
            if (empty) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable { vm.screen = nl.bartvandermeeren.aight.ui.Screen.Settings },
                ) {
                    Avatar(settings.userName.ifBlank { "?" }, size = 44.dp, ring = false)
                }
            } else {
                Row(Modifier.glass(CircleShape, Palette.Surface, LocalHazeState.current)) {
                    PlainIconButton(AightIcons.NewChat, stringResource(R.string.new_chat), onClick = vm::newChat, size = 44.dp, iconSize = 24.dp)
                    ConversationMenu(vm, sessions.items.firstOrNull { it.id == conversation.sessionId })
                }
            }
        }

        Composer(
            text = vm.composerText,
            onTextChange = { vm.composerText = it },
            placeholder = stringResource(R.string.composer_hint, settings.assistantName),
            attachments = vm.attachments,
            onRemoveAttachment = vm::removeAttachment,
            listening = listening,
            voiceLevel = voiceLevel,
            voiceText = voiceText,
            busy = conversation.isBusy || vm.preparingSend,
            onAddClick = { showAddMenu = true },
            onMicClick = actions.onMic,
            onStopListening = actions.onStopListening,
            onSend = { if (listening) actions.onSendWhileListening() else vm.send() },
            onStop = vm::stop,
            onLiveClick = actions.onLive,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { composerHeight = it.height }
                .widthIn(max = 880.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp, top = 6.dp),
            addMenu = {
                GlassDropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_photos)) },
                        leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                        onClick = { showAddMenu = false; actions.onPickPhotos() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_camera)) },
                        leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) },
                        onClick = { showAddMenu = false; actions.onTakePhoto() },
                    )
                }
            },
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(vm, settings, initialProfile = profile, onDismiss = { showModelPicker = false })
    }
}

/** Floating glass controls: the sidebar button, the model chip and [actions], with the chat visible between them. */
@Composable
private fun TopBar(
    showMenuButton: Boolean,
    onMenu: () -> Unit,
    modelTitle: String,
    modelSubtitle: String,
    onModelClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val haze = LocalHazeState.current
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(68.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showMenuButton) {
            PlainIconButton(
                AightIcons.Menu,
                stringResource(R.string.action_open_sidebar),
                onClick = onMenu,
                modifier = Modifier.glass(CircleShape, Palette.Surface, haze),
                size = 44.dp,
                iconSize = 24.dp,
            )
        }
        // The box takes all free space; the chip inside wraps its content and only ellipsizes when it must.
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .height(44.dp)
                    .glass(CircleShape, Palette.Surface, haze)
                    .clickable(onClick = onModelClick)
                    .padding(start = 18.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modelTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    color = Palette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (modelSubtitle.isNotEmpty()) {
                    Text(
                        " $modelSubtitle",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        color = Palette.TextSecondary,
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.model_picker_title),
                    tint = Palette.TextSecondary,
                    modifier = Modifier.padding(start = 4.dp).size(22.dp),
                )
            }
        }
        actions()
    }
}

@Composable
private fun Greeting(userName: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AightMark(size = 52.dp, halo = true)
        Spacer(Modifier.height(24.dp))
        Text(
            if (userName.isNotBlank()) stringResource(R.string.greeting_named, userName) else stringResource(R.string.greeting_anonymous),
            style = MaterialTheme.typography.displaySmall.copy(brush = GreetingBrush),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Icon(Icons.Rounded.Refresh, null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun MessageList(vm: MainViewModel, settings: AppSettings, speakingId: String?, topInset: Dp, bottomInset: Dp) {
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val messages = conversation.messages
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var previousCount by remember(conversation.sessionId) { mutableIntStateOf(0) }
    val haze = LocalHazeState.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) follow = !listState.canScrollForward
    }
    LaunchedEffect(conversation.sessionId, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (previousCount == 0) listState.scrollToItem(messages.size) else listState.animateScrollToItem(messages.size)
        previousCount = messages.size
        follow = true
    }
    val last = messages.lastOrNull()
    LaunchedEffect(last?.text?.length, last?.steps?.size, last?.state, last?.approval) {
        if (follow && messages.isNotEmpty()) listState.scrollToItem(messages.size)
    }
    LaunchedEffect(follow) {
        if (follow && messages.isNotEmpty() && listState.canScrollForward) listState.animateScrollToItem(messages.size)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = topInset + 8.dp, bottom = bottomInset + 20.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp)
                // Above the backdrop, so the glass frosts the messages on top of it.
                .then(if (haze != null) Modifier.hazeSource(haze, zIndex = 1f) else Modifier)
                .fadeUnder(statusBar, topInset, bottomInset),
        ) {
            itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                if (message.role == Role.User) {
                    UserMessageItem(message)
                } else {
                    AssistantMessageItem(
                        message = message,
                        isLast = index == messages.lastIndex,
                        assistantName = settings.assistantName,
                        speaking = speakingId == message.id,
                        onSpeak = { vm.toggleSpeak(message.id, message.text) },
                        onRetry = vm::retry,
                        onApproval = vm::resolveApproval,
                    )
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
        }
        AnimatedVisibility(
            visible = listState.canScrollForward && !follow,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 8.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .glass(CircleShape, Palette.Card, haze)
                    .clickable { follow = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.action_scroll_down), tint = Palette.Icon, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * Hides scrolling content under the status bar and the upper part of the [top] controls, then fades it
 * in by their bottom edge; at the bottom it fades out behind the lower half of the composer. Text
 * shows through the glass without crowding the status bar, the gaps between the controls or the gap
 * under the composer.
 */
private fun Modifier.fadeUnder(statusBar: Dp, top: Dp, bottom: Dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val hiddenPx = statusBar.toPx() + (top.toPx() - statusBar.toPx()).coerceAtLeast(0f) / 3f
        val hidden = (hiddenPx / h).coerceIn(0f, 0.4f)
        val topEnd = (top.toPx() / h).coerceIn(hidden, 0.5f)
        val bottomStart = ((h - bottom.toPx() + 6.dp.toPx()) / h).coerceIn(topEnd, 1f)
        val bottomEnd = ((h - bottom.toPx() / 2f) / h).coerceIn(bottomStart, 1f)
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                hidden to Color.Transparent,
                topEnd to Color.Black,
                bottomStart to Color.Black,
                bottomEnd to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun ConversationMenu(vm: MainViewModel, session: nl.bartvandermeeren.aight.data.SessionSummary?) {
    var open by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Box {
        PlainIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.action_more), onClick = { open = true }, size = 44.dp, iconSize = 24.dp)
        GlassDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (session != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (session.pinned) R.string.unpin else R.string.pin)) },
                    leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                    onClick = { open = false; vm.setPinned(session.id, !session.pinned) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename)) },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                    onClick = { open = false; renaming = true },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { open = false; confirmDelete = true },
            )
        }
    }
    if (renaming && session != null) {
        RenameDialog(session.displayTitle, onDismiss = { renaming = false }, onConfirm = {
            renaming = false
            vm.renameSession(session.id, it)
        })
    }
    if (confirmDelete) {
        GlassDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_chat_title)) },
            text = { Text(stringResource(R.string.delete_chat_body, session?.displayTitle.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSession(vm.currentId.value)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
