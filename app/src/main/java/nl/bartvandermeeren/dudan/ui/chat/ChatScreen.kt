package nl.bartvandermeeren.dudan.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.chat.ChatEngine
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.components.DudanIcons
import nl.bartvandermeeren.dudan.ui.components.DudanMark
import nl.bartvandermeeren.dudan.ui.components.Avatar
import nl.bartvandermeeren.dudan.ui.components.Composer
import nl.bartvandermeeren.dudan.ui.components.GlassDialog
import nl.bartvandermeeren.dudan.ui.components.GlassDropdownMenu
import nl.bartvandermeeren.dudan.ui.components.GlassMenuItem
import nl.bartvandermeeren.dudan.ui.components.ModelPickerButton
import nl.bartvandermeeren.dudan.ui.components.PlainIconButton
import nl.bartvandermeeren.dudan.ui.components.pane
import nl.bartvandermeeren.dudan.ui.openui.LocalOpenUiHost
import nl.bartvandermeeren.dudan.ui.openui.OpenUiHost
import nl.bartvandermeeren.dudan.ui.openui.openExternalUrl
import nl.bartvandermeeren.dudan.ui.theme.Palette

/** Callbacks the activity provides because they need permissions or activity result launchers. */
class ChatHostActions(
    val onMic: () -> Unit,
    val onStopListening: () -> Unit,
    val onSendWhileListening: () -> Unit,
    val onLive: () -> Unit,
    val onPickPhotos: () -> Unit,
    val onTakePhoto: () -> Unit,
    val onPickFiles: () -> Unit,
)

/**
 * The chat on the sky: a title bar, the conversation straight on the sky (which deepens while it is
 * open, see GlassBackdrop) and the prompt bar as a glass card at the foot. An empty chat shows a large
 * greeting above the prompt bar instead.
 */
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
    val empty = conversation.showGreeting
    var showModelPicker by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    val profile = ChatEngine.profileOf(conversation.sessionId)
    val session = sessions.items.firstOrNull { it.id == conversation.sessionId }

    // Assistant chats (side key, overlay) run on the fast model; everything else on the normal one.
    val activeModel = settings.modelFor(profile)
    val modelTitle = activeModel.label?.takeIf { it != activeModel.model }
        ?: (activeModel.model ?: catalog?.currentModel)?.let(::prettyModelName)
        ?: stringResource(R.string.model_default)
    val modelSubtitle = modelModeLabel(activeModel)

    Column(modifier.fillMaxSize()) {
        TopBar(
            showMenuButton = showMenuButton,
            onMenu = onMenu,
            title = if (empty) null else session?.displayTitle?.takeIf { it.isNotBlank() } ?: settings.assistantName,
        ) {
            if (empty) {
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .clip(CircleShape)
                        .clickable { vm.screen = nl.bartvandermeeren.dudan.ui.Screen.Settings },
                ) {
                    Avatar(settings.userName.ifBlank { "?" }, size = 40.dp, ring = false)
                }
            } else {
                PlainIconButton(DudanIcons.NewChat, stringResource(R.string.new_chat), onClick = vm::newChat, size = 44.dp, iconSize = 24.dp)
                ConversationMenu(vm, session)
            }
        }

        if (empty) {
            Greeting(settings.userName, Modifier.weight(1f).fillMaxWidth())
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                when {
                    conversation.loading && messages.isEmpty() -> CircularProgressIndicator(
                        color = Palette.TextSecondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.align(Alignment.Center).size(28.dp),
                    )
                    conversation.loadError != null && messages.isEmpty() -> LoadError(conversation.loadError!!, onRetry = vm::reloadCurrent)
                    else -> MessageList(vm, settings, speakingId)
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
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 880.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 6.dp),
            modelPicker = {
                ModelPickerButton(modelTitle, modelSubtitle, onClick = {
                    vm.loadModels()
                    showModelPicker = true
                })
            },
            addMenu = {
                GlassDropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    GlassMenuItem(Icons.Outlined.PhotoLibrary, stringResource(R.string.attach_photos), stringResource(R.string.attach_photos_detail)) {
                        showAddMenu = false
                        actions.onPickPhotos()
                    }
                    GlassMenuItem(Icons.Outlined.PhotoCamera, stringResource(R.string.attach_camera), stringResource(R.string.attach_camera_detail)) {
                        showAddMenu = false
                        actions.onTakePhoto()
                    }
                    GlassMenuItem(Icons.Outlined.AttachFile, stringResource(R.string.attach_files), stringResource(R.string.attach_files_detail)) {
                        showAddMenu = false
                        actions.onPickFiles()
                    }
                }
            },
            onRetryAttachment = vm::retryUpload,
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(vm, settings, initialProfile = profile, onDismiss = { showModelPicker = false })
    }
}

/** The bar on the sky: the sidebar button, the chat's title and [actions], plain like Superhuman's nav. */
@Composable
private fun TopBar(
    showMenuButton: Boolean,
    onMenu: () -> Unit,
    title: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMenuButton) {
            PlainIconButton(DudanIcons.Menu, stringResource(R.string.action_open_sidebar), onClick = onMenu, size = 44.dp, iconSize = 26.dp)
        } else {
            Spacer(Modifier.size(10.dp))
        }
        Text(
            title.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        actions()
    }
}

/** Superhuman's hero on the sky: the mark, a large greeting and a quieter line under it. */
@Composable
private fun Greeting(userName: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DudanMark(size = 56.dp, halo = true)
        Spacer(Modifier.height(26.dp))
        Text(
            if (userName.isNotBlank()) stringResource(R.string.greeting_named, userName) else stringResource(R.string.greeting_anonymous),
            style = MaterialTheme.typography.displayMedium,
            color = Palette.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.greeting_subtitle),
            style = MaterialTheme.typography.titleLarge,
            color = Palette.TextPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
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
private fun MessageList(vm: MainViewModel, settings: AppSettings, speakingId: String?) {
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val messages = conversation.messages
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var previousCount by remember(conversation.sessionId) { mutableIntStateOf(0) }

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

    val context = LocalContext.current
    val openUiHost = remember(vm) { OpenUiHost(send = vm::sendFromReply, openUrl = { openExternalUrl(context, it) }) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        CompositionLocalProvider(LocalOpenUiHost provides openUiHost) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize().widthIn(max = 880.dp).fadeEdges(top = 18.dp, bottom = 14.dp),
            ) {
                itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                    when (message.role) {
                        Role.User -> UserMessageItem(message)
                        Role.Background -> BackgroundResultItem(
                            message = message,
                            assistantName = settings.assistantName,
                            reviewing = conversation.reviewing,
                            reviewError = conversation.reviewError,
                            onReview = vm::reviewBackground.takeIf { index == messages.lastIndex && conversation.awaitingReview },
                        )
                        Role.Assistant -> AssistantMessageItem(
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
        }
        AnimatedVisibility(
            visible = listState.canScrollForward && !follow,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .pane(CircleShape, Palette.MenuSolid)
                    .border(1.dp, Palette.Outline, CircleShape)
                    .clickable { follow = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.action_scroll_down), tint = Palette.Icon, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Fades the messages out under the title bar and just above the prompt bar, instead of cutting them off. */
private fun Modifier.fadeEdges(top: Dp, bottom: Dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val topEnd = (top.toPx() / h).coerceIn(0f, 0.5f)
        val bottomStart = (1f - bottom.toPx() / h).coerceIn(topEnd, 1f)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, topEnd to Color.Black, bottomStart to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun ConversationMenu(vm: MainViewModel, session: nl.bartvandermeeren.dudan.data.SessionSummary?) {
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
