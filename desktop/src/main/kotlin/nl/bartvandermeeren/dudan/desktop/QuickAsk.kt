package nl.bartvandermeeren.dudan.desktop

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import nl.bartvandermeeren.dudan.ui.components.GlassBackdrop
import nl.bartvandermeeren.dudan.ui.components.GlassDefaults
import nl.bartvandermeeren.dudan.ui.components.LocalOverlayHost
import nl.bartvandermeeren.dudan.ui.components.OverlayHost
import nl.bartvandermeeren.dudan.ui.theme.Accent
import nl.bartvandermeeren.dudan.ui.theme.DudanTheme
import nl.bartvandermeeren.dudan.ui.theme.Sky
import nl.bartvandermeeren.dudan.ui.openui.LocalOpenUiHost
import nl.bartvandermeeren.dudan.ui.openui.OpenUiHost
import nl.bartvandermeeren.dudan.ui.openui.openExternalUrl
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.dudan.chat.Conversation
import nl.bartvandermeeren.dudan.chat.PreparedImage
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.chat.TurnOrigin
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.desktop.res.*
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.Notice
import nl.bartvandermeeren.dudan.ui.components.PlainIconButton
import nl.bartvandermeeren.dudan.ui.components.pane
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.TextButton
import kotlinx.coroutines.delay
import nl.bartvandermeeren.dudan.ui.chat.AssistantMessageItem
import nl.bartvandermeeren.dudan.ui.chat.BackgroundResultItem
import nl.bartvandermeeren.dudan.ui.chat.UserMessageItem
import nl.bartvandermeeren.dudan.ui.components.Attachment
import nl.bartvandermeeren.dudan.ui.components.Composer
import nl.bartvandermeeren.dudan.ui.components.DudanMark
import nl.bartvandermeeren.dudan.ui.components.outlined
import nl.bartvandermeeren.dudan.ui.theme.LocalAccent
import nl.bartvandermeeren.dudan.ui.theme.Palette
import org.jetbrains.compose.resources.stringResource

/**
 * The quick question: the Windows counterpart of the phone's side-key overlay. Every opening starts a new
 * chat on the quick model, as every press of the side key does. The screen under it is grabbed as the
 * window opens and kept in memory only, until the window closes; it's sent only when the user asks
 * about the screen.
 */
class QuickAsk(private val vm: MainViewModel, private val scope: CoroutineScope) {
    var visible by mutableStateOf(false)
        private set
    var sessionId by mutableStateOf(vm.engine.startNew(ModelProfile.Assistant))
        private set
    val conversation: StateFlow<Conversation> get() = vm.engine.conversation(sessionId)
    var text by mutableStateOf("")
    var screen by mutableStateOf<PreparedImage?>(null)
        private set
    /** The screen's thumbnail for the prompt bar, kept here rather than in the shared image cache. */
    var screenPreview by mutableStateOf<ImageBitmap?>(null)
        private set
    var attachScreen by mutableStateOf(false)
    /** Bumped on every opening, so the window takes the keyboard focus again. */
    var openings by mutableIntStateOf(0)
        private set
    private var preparing: Job? = null

    fun open() {
        val shot = ScreenCapture.grab()
        newQuestion()
        visible = true
        vm.quickOnScreen.value = sessionId
        openings++
        preparing?.cancel()
        screen = null
        screenPreview = null
        if (shot == null) return
        preparing = scope.launch {
            val (prepared, preview) = withContext(Dispatchers.Default) { ScreenCapture.prepare(shot) to ScreenCapture.preview(shot) }
            if (prepared == null) return@launch
            screen = prepared
            screenPreview = preview
        }
    }

    /** A new chat, unless the current one is still empty. */
    fun newQuestion() {
        val current = vm.engine.conversation(sessionId).value
        if (!(current.isNew && current.messages.isEmpty())) sessionId = vm.engine.startNew(ModelProfile.Assistant)
        if (visible) vm.quickOnScreen.value = sessionId
        text = ""
        attachScreen = false
        openings++
    }

    fun close() {
        visible = false
        vm.quickOnScreen.value = null
        vm.quickNotice.value = null
        preparing?.cancel()
        screen = null
        screenPreview = null
        attachScreen = false
    }

    fun send() {
        val question = text.trim()
        val shot = screen.takeIf { attachScreen }
        if (question.isEmpty() && shot == null) return
        vm.stopReading()
        val origin = TurnOrigin(TurnOrigin.Surface.Assistant, screenshot = shot != null, device = TurnOrigin.Device.Desktop)
        if (vm.engine.send(sessionId, question, listOfNotNull(shot), origin = origin)) {
            text = ""
            attachScreen = false
        }
    }

    fun stop() = vm.engine.stop(sessionId)

    /** What an OpenUI button or follow-up in the answer sends, in this chat. */
    fun sendFromReply(reply: String): Boolean =
        vm.engine.send(sessionId, reply, origin = TurnOrigin(TurnOrigin.Surface.Assistant, device = TurnOrigin.Device.Desktop))

    /** Hands the chat to the main window, as the overlay hands off to the full app. */
    fun openInApp(showMain: () -> Unit) {
        vm.openSession(sessionId)
        close()
        showMain()
    }
}

/**
 * What the quick question window shows: a card with its own small sky, since Windows can't frost the
 * apps behind it, holding [QuickAskPanel]. Sheets open over it in [overlay].
 */
@Composable
fun QuickAskCard(quick: QuickAsk, vm: MainViewModel, overlay: OverlayHost, onOpenInApp: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val prefs by vm.prefs.collectAsState()
    DudanTheme(settings.reduceTransparency, Accent.from(settings.accent), Sky.from(settings.sky), prefs.textScale) {
        CompositionLocalProvider(LocalOverlayHost provides overlay) {
            val shape = RoundedCornerShape(28.dp)
            Box(Modifier.fillMaxWidth().clip(shape).border(GlassDefaults.Border, shape)) {
                GlassBackdrop(Modifier.matchParentSize(), dim = true)
                QuickAskPanel(quick, vm, onOpenInApp)
            }
            overlay.Layers()
        }
    }
}

/** The quick question's content: the prompt bar, Ask about screen, and the answer above it. */
@Composable
fun QuickAskPanel(quick: QuickAsk, vm: MainViewModel, onOpenInApp: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val conversation by remember(quick.sessionId) { quick.conversation }.collectAsState()
    val speakingId by vm.speaker.speakingId.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(quick.openings) { runCatching { focus.requestFocus() } }
    val messages = conversation.messages
    val listState = rememberLazyListState()
    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.text?.length, last?.steps?.size) {
        // Next layout, not now: see MessageList.
        if (messages.isNotEmpty()) listState.requestScrollToItem(messages.lastIndex)
    }
    val shot = quick.screen
    val shotPreview = quick.screenPreview
    val openUiHost = remember(quick) { OpenUiHost(send = quick::sendFromReply, openUrl = ::openExternalUrl) }
    Column(Modifier.fillMaxWidth().animateContentSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 6.dp)) {
            DudanMark(size = 26.dp, working = conversation.isBusy, halo = true)
            Text(settings.assistantName, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            if (messages.isNotEmpty()) {
                QuickAction(Icons.Rounded.Add, stringResource(Res.string.desktop_quick_new), onClick = quick::newQuestion)
                QuickAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(Res.string.desktop_quick_open_in_app), onClick = onOpenInApp)
            }
        }
        vm.quickNotice.collectAsState().value?.let { notice -> QuickNotice(notice, onDone = { vm.quickNotice.value = null }) }
        if (messages.isNotEmpty()) CompositionLocalProvider(LocalOpenUiHost provides openUiHost) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    when (message.role) {
                        Role.User -> UserMessageItem(message)
                        Role.Background -> BackgroundResultItem(
                            message = message,
                            assistantName = settings.assistantName,
                            reviewing = conversation.reviewing,
                            reviewError = conversation.reviewError,
                            onReview = null,
                        )
                        Role.Assistant -> AssistantMessageItem(
                            message = message,
                            isLast = message.id == last?.id,
                            canRetry = message.id == conversation.retryId,
                            assistantName = settings.assistantName,
                            speaking = speakingId == message.id,
                            onSpeak = { vm.toggleSpeak(message.id, message.text, quick = true) },
                            onRetry = { vm.engine.retry(quick.sessionId) },
                            onApproval = { vm.engine.resolveApproval(quick.sessionId, it) },
                            showDisclaimer = false,
                        )
                    }
                }
            }
        }
        Composer(
            text = quick.text,
            onTextChange = { quick.text = it },
            placeholder = stringResource(Res.string.desktop_quick_hint, settings.assistantName),
            attachments = if (quick.attachScreen && shot != null) listOf(Attachment(0, image = shot, preview = shotPreview)) else emptyList(),
            onRemoveAttachment = { quick.attachScreen = false },
            listening = false,
            voiceLevel = 0f,
            busy = conversation.isBusy,
            // The screen is the one thing to add here, and its chip does that.
            onAddClick = null,
            onMicClick = null,
            onStopListening = {},
            onSend = quick::send,
            onStop = quick::stop,
            onLiveClick = null,
            focusRequester = focus,
            modelPicker = {
                if (shot != null) ScreenChip(attached = quick.attachScreen, onClick = { quick.attachScreen = !quick.attachScreen })
            },
        )
    }
}

/** A notice in the card, such as a voice to download, where the main window would show its snackbar. */
@Composable
private fun QuickNotice(notice: Notice, onDone: () -> Unit) {
    LaunchedEffect(notice) {
        delay(10_000)
        onDone()
    }
    Row(
        Modifier.fillMaxWidth().pane(RoundedCornerShape(16.dp), Palette.Surface, outline = Palette.Hairline).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(18.dp))
        Text(stringResource(notice.text, *notice.args.toTypedArray()), style = MaterialTheme.typography.bodySmall, color = Palette.TextPrimary, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        notice.action?.let { action ->
            TextButton(onClick = {
                onDone()
                action.run()
            }) { Text(stringResource(action.label)) }
        }
        PlainIconButton(Icons.Rounded.Close, stringResource(Res.string.cancel), onClick = onDone, size = 36.dp, iconSize = 18.dp)
    }
}

/** "Ask about screen" as a chip in the prompt bar; checked once the screen is attached. */
@Composable
private fun ScreenChip(attached: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .padding(start = 4.dp)
            .outlined(RoundedCornerShape(14.dp), fill = if (attached) accent.color.copy(alpha = 0.16f) else Color.Transparent, outline = if (attached) accent.color.copy(alpha = 0.5f) else Palette.Outline)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (attached) Icons.Rounded.Check else Icons.Outlined.Screenshot, contentDescription = null, tint = if (attached) accent.soft else Palette.TextSecondary, modifier = Modifier.size(16.dp))
        Text(
            stringResource(if (attached) Res.string.screen_attached else Res.string.ask_about_screen),
            style = MaterialTheme.typography.labelLarge,
            color = if (attached) Palette.TextPrimary else Palette.TextSecondary,
        )
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
    }
}
