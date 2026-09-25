package nl.bartvandermeeren.aight.assist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.chat.Conversation
import nl.bartvandermeeren.aight.chat.Role
import nl.bartvandermeeren.aight.data.AppSettings
import nl.bartvandermeeren.aight.ui.chat.AssistantMessageItem
import nl.bartvandermeeren.aight.ui.chat.UserMessageItem
import nl.bartvandermeeren.aight.ui.components.Composer
import nl.bartvandermeeren.aight.ui.components.PlainIconButton
import nl.bartvandermeeren.aight.ui.components.AightMark
import nl.bartvandermeeren.aight.ui.theme.Palette
import nl.bartvandermeeren.aight.voice.SpeechInput

private val emptyConversation = MutableStateFlow(Conversation(""))

@Composable
fun AssistOverlay(state: AssistState) {
    val phase by state.speech.phase.collectAsState()
    val level by state.speech.level.collectAsState()
    val heard by state.speech.partial.collectAsState()
    val speakingId by state.speaker.speakingId.collectAsState()
    val conversationFlow = remember(state.sessionId) { state.sessionId?.let { state.engine.conversation(it) } ?: emptyConversation }
    val conversation by conversationFlow.collectAsState()
    val settings = state.settings ?: AppSettings()
    val listening = phase != SpeechInput.Phase.Idle
    val hasChat = conversation.messages.isNotEmpty()
    val keyboard = LocalSoftwareKeyboardController.current

    // Android forces the session window into adjust=pan. While the keyboard animates in, the view root
    // pans to keep the focused field visible, and because Compose moves the field without a View layout
    // pass that stale pan sticks on top of imePadding. A real layout pass makes the view root recompute.
    val view = LocalView.current
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) { view.requestLayout() }

    Box(Modifier.fillMaxSize()) {
        // Scrim: tap anywhere outside the panel to dismiss, like Gemini's overlay.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = if (hasChat) 0.45f else 0.15f),
                        1f to Color.Black.copy(alpha = 0.8f),
                    ),
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.dismiss() },
        )
        EdgeGlow(intensity = when {
            listening -> 0.55f + level * 0.45f
            conversation.isBusy -> 0.6f
            else -> 0.4f
        })

        AnimatedVisibility(
            visible = state.shown,
            enter = slideInVertically { it / 4 } + fadeIn(tween(180)),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!settings.isConfigured && state.settings != null) {
                    NotConfiguredCard(onOpen = state::openFullChat)
                }
                if (hasChat) {
                    // Weighted so the composer below always keeps its room when space runs out.
                    Box(Modifier.weight(1f, fill = false)) {
                        ResponsePanel(state, conversation, settings, speakingId)
                    }
                }
                val shot = state.screenshot
                if (shot != null && !state.attachScreenshot && !conversation.isBusy) {
                    Chip(
                        icon = { Icon(Icons.Outlined.ScreenshotMonitor, null, tint = Palette.Icon, modifier = Modifier.size(20.dp)) },
                        label = stringResource(R.string.ask_about_screen),
                        onClick = {
                            state.speech.cancel()
                            state.attachScreenshot = true
                        },
                    )
                }
                if (shot != null && state.attachScreenshot) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Card)) {
                            Image(shot.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Text(stringResource(R.string.screen_attached), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                        PlainIconButton(Icons.Rounded.Close, stringResource(R.string.action_remove), onClick = { state.attachScreenshot = false }, size = 36.dp, iconSize = 18.dp)
                    }
                }
                Composer(
                    text = state.text,
                    onTextChange = { state.text = it },
                    placeholder = stringResource(R.string.composer_hint, settings.assistantName),
                    attachments = emptyList(),
                    onRemoveAttachment = {},
                    listening = listening,
                    voiceLevel = level,
                    voiceText = heard,
                    busy = conversation.isBusy,
                    onAddClick = {
                        state.speech.cancel()
                        if (state.screenshot != null) state.attachScreenshot = true else state.openFullChat()
                    },
                    onMicClick = state::startListening,
                    onStopListening = state::stopListening,
                    onSend = {
                        if (listening) {
                            state.stopListening()
                        } else {
                            keyboard?.hide()
                            state.send()
                        }
                    },
                    onStop = state::stop,
                    onLiveClick = state::openLive,
                    containerColor = Color(0xFF151618),
                )
            }
        }
    }
}

@Composable
private fun ResponsePanel(state: AssistState, conversation: Conversation, settings: AppSettings, speakingId: String?) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.58f).dp
    val listState = rememberLazyListState()
    val last = conversation.messages.lastOrNull()
    LaunchedEffect(conversation.messages.size, last?.text?.length, last?.steps?.size) {
        listState.scrollToItem(conversation.messages.size)
    }
    Surface(color = Color(0xFF1B1C1E), shape = RoundedCornerShape(30.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AightMark(size = 18.dp, working = conversation.isBusy)
                Spacer(Modifier.size(10.dp))
                Text(settings.assistantName, style = MaterialTheme.typography.titleSmall, color = Palette.TextSecondary)
                Spacer(Modifier.weight(1f))
                PlainIconButton(Icons.Outlined.OpenInFull, stringResource(R.string.open_full_chat), onClick = state::openFullChat, size = 44.dp, iconSize = 20.dp)
                PlainIconButton(Icons.Rounded.Close, stringResource(R.string.action_close), onClick = state.dismiss, size = 44.dp, iconSize = 22.dp)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = maxHeight),
                contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(conversation.messages, key = { _, m -> m.id }) { index, message ->
                    if (message.role == Role.User) {
                        UserMessageItem(message)
                    } else {
                        AssistantMessageItem(
                            message = message,
                            isLast = index == conversation.messages.lastIndex,
                            assistantName = settings.assistantName,
                            speaking = speakingId == message.id,
                            onSpeak = {
                                if (speakingId == message.id) state.speaker.stop()
                                else state.speaker.speak(message.id, message.text, settings.speechLanguage)
                            },
                            onRetry = { state.sessionId?.let(state.engine::retry) },
                            onApproval = { choice -> state.sessionId?.let { state.engine.resolveApproval(it, choice) } },
                            showDisclaimer = false,
                        )
                    }
                }
                item(key = "bottom") { Spacer(Modifier.size(1.dp)) }
            }
        }
    }
}

@Composable
private fun NotConfiguredCard(onOpen: () -> Unit) {
    Surface(color = Color(0xFF1B1C1E), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Text(
            stringResource(R.string.assist_not_configured),
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.TextPrimary,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun Chip(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xF0151618))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary)
    }
}

/** Soft colored light along the bottom and side edges, breathing with the voice level. */
@Composable
private fun EdgeGlow(intensity: Float) {
    val transition = rememberInfiniteTransition(label = "edge")
    val drift by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(3600), RepeatMode.Reverse), label = "drift")
    val strength by animateFloatAsState(intensity, tween(200), label = "strength")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.radialGradient(
                listOf(Palette.SparkViolet.copy(alpha = 0.55f * strength), Palette.Live.copy(alpha = 0.3f * strength), Color.Transparent),
                center = Offset(w * (0.1f + 0.15f * drift), h * 1.02f),
                radius = w * 0.75f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.SparkBlue.copy(alpha = 0.45f * strength), Color.Transparent),
                center = Offset(w * (0.55f - 0.1f * drift), h * 1.06f),
                radius = w * 0.6f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.SparkAmber.copy(alpha = 0.5f * strength), Palette.SparkRose.copy(alpha = 0.18f * strength), Color.Transparent),
                center = Offset(w * 1.04f, h * (0.35f + 0.1f * drift)),
                radius = h * 0.35f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.SparkBlue.copy(alpha = 0.35f * strength), Color.Transparent),
                center = Offset(-w * 0.04f, h * (0.6f - 0.1f * drift)),
                radius = h * 0.3f,
            ),
        )
    }
}
