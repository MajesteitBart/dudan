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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.bartvandermeeren.dudan.chat.ChatEngine
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.chat.SkillTags
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.DesktopState
import nl.bartvandermeeren.dudan.data.SessionSummary
import nl.bartvandermeeren.dudan.data.SkillInfo
import nl.bartvandermeeren.dudan.desktop.res.*
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.components.Composer
import nl.bartvandermeeren.dudan.ui.components.DudanIcons
import nl.bartvandermeeren.dudan.ui.components.DudanMark
import nl.bartvandermeeren.dudan.ui.components.GlassDropdownMenu
import nl.bartvandermeeren.dudan.ui.components.GlassMenuItem
import nl.bartvandermeeren.dudan.ui.components.ModelPickerButton
import nl.bartvandermeeren.dudan.ui.components.PlainIconButton
import nl.bartvandermeeren.dudan.ui.components.pane
import nl.bartvandermeeren.dudan.ui.openui.LocalOpenUiHost
import nl.bartvandermeeren.dudan.ui.openui.OpenUiHost
import nl.bartvandermeeren.dudan.ui.openui.openExternalUrl
import nl.bartvandermeeren.dudan.ui.theme.Palette
import org.jetbrains.compose.resources.stringResource

/** What the window does for the chat that needs AWT: file dialogs and screen capture. */
class ChatHostActions(
    val onPickImages: () -> Unit,
    val onScreenshot: () -> Unit,
)

/**
 * The phone's chat on the sky, in a window: a title bar, the conversation straight on the sky (which
 * deepens while it is open, see GlassBackdrop) and the prompt bar as a glass card at the foot. An empty
 * chat shows a large greeting above the prompt bar instead.
 */
@Composable
fun ChatPane(
    vm: MainViewModel,
    settings: AppSettings,
    actions: ChatHostActions,
    modifier: Modifier = Modifier,
    /** Shown while the docked chat list is closed, to open it again. */
    onOpenSidebar: (() -> Unit)? = null,
) {
    val conversation by vm.conversation.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val catalog by vm.modelCatalog.collectAsState()
    val skillState by vm.skillCatalog.state.collectAsState()
    val skills = skillState.skills.orEmpty()
    // The list loads at start; if Hermes was out of reach then, a typed `$` tries again.
    val typingTag = '$' in vm.composerText
    LaunchedEffect(typingTag) { if (typingTag) vm.skillCatalog.ensureLoaded() }
    val composerFocus = remember { FocusRequester() }
    LaunchedEffect(vm.focusComposer) {
        if (vm.focusComposer) runCatching { composerFocus.requestFocus() }
        vm.focusComposer = false
    }
    val messages = conversation.messages
    val empty = conversation.showGreeting
    var showModelPicker by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    val profile = ChatEngine.profileOf(conversation.sessionId)
    val session = sessions.items.firstOrNull { it.id == conversation.sessionId }

    // Quick questions run on the quick model; everything else on the normal one.
    val activeModel = settings.modelFor(profile)
    val modelTitle = activeModel.label?.takeIf { it != activeModel.model }
        ?: (activeModel.model ?: catalog?.currentModel)?.let(::prettyModelName)
        ?: stringResource(Res.string.model_default)
    val modelSubtitle = modelModeLabel(activeModel)

    Column(modifier.fillMaxSize()) {
        TopBar(title = if (empty) null else session?.displayTitle?.takeIf { it.isNotBlank() } ?: settings.assistantName, onMenu = onOpenSidebar) {
            if (!empty) {
                PlainIconButton(DudanIcons.NewChat, stringResource(Res.string.new_chat), onClick = { vm.newChat() }, size = 44.dp, iconSize = 24.dp)
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
                    else -> key(settings.serverUrl, settings.apiKey) { MessageList(vm, settings, skills, session?.displayTitle) }
                }
            }
        }

        Composer(
            text = vm.composerText,
            onTextChange = { vm.composerText = it },
            placeholder = stringResource(Res.string.composer_hint, settings.assistantName),
            attachments = vm.attachments,
            onRemoveAttachment = vm::removeAttachment,
            listening = false,
            voiceLevel = 0f,
            busy = conversation.isBusy || vm.preparingSend,
            onAddClick = { showAddMenu = true },
            onMicClick = null,
            onStopListening = {},
            onSend = vm::send,
            onStop = vm::stop,
            onLiveClick = null,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 880.dp)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 6.dp),
            focusRequester = composerFocus,
            modelPicker = {
                ModelPickerButton(modelTitle, modelSubtitle, onClick = {
                    vm.loadModels()
                    showModelPicker = true
                })
            },
            addMenu = {
                GlassDropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    GlassMenuItem(Icons.Outlined.Image, stringResource(Res.string.desktop_attach_images), stringResource(Res.string.desktop_attach_images_detail)) {
                        showAddMenu = false
                        actions.onPickImages()
                    }
                    GlassMenuItem(Icons.Outlined.Screenshot, stringResource(Res.string.desktop_attach_screenshot), stringResource(Res.string.desktop_attach_screenshot_detail)) {
                        showAddMenu = false
                        actions.onScreenshot()
                    }
                    if (skills.isNotEmpty()) {
                        GlassMenuItem(Icons.Outlined.AutoAwesome, stringResource(Res.string.attach_skill), stringResource(Res.string.attach_skill_detail)) {
                            showAddMenu = false
                            // A `$` at the end opens the skill list, the same as typing it.
                            val draft = vm.composerText
                            vm.composerText = if (draft.isEmpty() || draft.last().isWhitespace()) "$draft\$" else "$draft \$"
                            vm.focusComposer = true
                        }
                    }
                }
            },
            skills = skills,
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(vm, settings, initialProfile = profile, onDismiss = { showModelPicker = false })
    }
}

/** The bar on the sky: the chat's title and [actions], plain like Superhuman's nav. */
@Composable
private fun TopBar(title: String?, onMenu: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMenu != null) {
            PlainIconButton(DudanIcons.SidePanel, stringResource(Res.string.action_open_sidebar), onClick = onMenu, size = 44.dp, iconSize = 24.dp)
        }
        Text(
            title.orEmpty(),
            style = MaterialTheme.typography.titleMedium.copy(lineHeight = 21.sp),
            color = Palette.TextPrimary,
            maxLines = 2,
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
            if (userName.isNotBlank()) stringResource(Res.string.greeting_named, userName) else stringResource(Res.string.greeting_anonymous),
            style = MaterialTheme.typography.displayMedium,
            color = Palette.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(Res.string.greeting_subtitle),
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
            Text(stringResource(Res.string.action_retry))
        }
    }
}

@Composable
private fun MessageList(vm: MainViewModel, settings: AppSettings, skills: List<SkillInfo>, chatTitle: String?) {
    val conversation by vm.conversation.collectAsState()
    val speakingId by vm.speaker.speakingId.collectAsState()
    val saves = rememberAttachmentSaves()
    val desktops = rememberConversationDesktops(vm.api, conversation.sessionId)
    // A desktop shows while it works; a stopped one leaves the conversation.
    val working = remember(desktops.sessions) { desktops.sessions.filter { it.state != DesktopState.Stopped } }
    val screens = desktops.client?.let { client -> remember(client, conversation.sessionId) { AgentScreens(client) } }
    val skillSlugs = remember(skills) { skills.mapTo(HashSet()) { SkillTags.slug(it.name) } }
    val messages = conversation.messages
    val retryId = conversation.retryId
    val reviewId = conversation.reviewId
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var previousCount by remember(conversation.sessionId) { mutableIntStateOf(0) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) follow = !listState.canScrollForward
    }
    LaunchedEffect(conversation.sessionId, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (previousCount == 0) listState.requestScrollToItem(messages.size) else listState.animateScrollToItem(messages.size)
        previousCount = messages.size
        follow = true
    }
    val last = messages.lastOrNull()
    LaunchedEffect(last?.text?.length, last?.steps?.size, last?.state, last?.approval) {
        // Requested for the next layout rather than run now: a reply with OpenUI lists in it can still be
        // composing, and scrolling in the middle of that is a Compose runtime error.
        if (follow && messages.isNotEmpty()) listState.requestScrollToItem(messages.size)
    }
    LaunchedEffect(follow) {
        if (follow && messages.isNotEmpty() && listState.canScrollForward) listState.animateScrollToItem(messages.size)
    }
    LaunchedEffect(working.size) {
        if (follow && working.isNotEmpty()) listState.animateScrollToItem(messages.size + working.size)
    }

    val openUiHost = remember(vm) { OpenUiHost(send = vm::sendFromReply, openUrl = ::openExternalUrl, onBusy = vm::replyBusy) }
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
                    Role.User -> UserMessageItem(message, skillSlugs = skillSlugs)
                    Role.Background -> BackgroundResultItem(
                        message = message,
                        assistantName = settings.assistantName,
                        reviewing = conversation.reviewing,
                        reviewError = conversation.reviewError,
                        onReview = vm::reviewBackground.takeIf { message.id == reviewId },
                    )
                    Role.Assistant -> AssistantMessageItem(
                        message = message,
                        isLast = index == messages.lastIndex,
                        canRetry = message.id == retryId,
                        assistantName = settings.assistantName,
                        speaking = speakingId == message.id,
                        onSpeak = { vm.toggleSpeak(message.id, message.text) },
                        onRetry = vm::retry,
                        onApproval = vm::resolveApproval,
                        chatTitle = chatTitle,
                        attachments = { OutboundAttachments(vm.api, conversation.sessionId, message.attachments, saves) },
                    )
                }
            }
            if (screens != null && desktops.capability != null) {
                working.forEach { desktop ->
                    item(key = "desktop:${desktop.id}") { AgentScreenCard(screens.controller(desktop), onOpen = { screens.open = desktop.id }) }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
        }
      }
        // Beside the list, not in it: a card that scrolls away must not take an open Agent screen window with it.
        val capability = desktops.capability
        if (screens != null && capability != null) {
            AgentScreenWindows(screens, working, capability, settings.assistantName) { id ->
                listState.layoutInfo.visibleItemsInfo.any { it.key == "desktop:$id" }
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
                Icon(Icons.Rounded.ArrowDownward, stringResource(Res.string.action_scroll_down), tint = Palette.Icon, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Fades a scrolling list out at its top and bottom edges, instead of cutting it off. */
internal fun Modifier.fadeEdges(top: Dp, bottom: Dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
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

/** The chat's ⋯: the same menu as its row in the chat list, which a chat not saved yet can only delete. */
@Composable
private fun ConversationMenu(vm: MainViewModel, session: SessionSummary?) {
    var open by remember { mutableStateOf(false) }
    val currentId by vm.currentId.collectAsState()
    Box {
        PlainIconButton(Icons.Rounded.MoreHoriz, stringResource(Res.string.action_more), onClick = { open = true }, size = 44.dp, iconSize = 24.dp)
        ChatMenu(vm, session, expanded = open, onDismiss = { open = false }, deleteId = currentId)
    }
}
