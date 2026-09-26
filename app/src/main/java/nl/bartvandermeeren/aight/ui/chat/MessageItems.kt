package nl.bartvandermeeren.aight.ui.chat

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.chat.ImageRef
import nl.bartvandermeeren.aight.chat.MessageState
import nl.bartvandermeeren.aight.chat.Step
import nl.bartvandermeeren.aight.chat.StepKind
import nl.bartvandermeeren.aight.chat.UiMessage
import nl.bartvandermeeren.aight.data.ApprovalRequest
import nl.bartvandermeeren.aight.ui.components.Markdown
import nl.bartvandermeeren.aight.ui.components.PlainIconButton
import nl.bartvandermeeren.aight.ui.components.AightMark
import nl.bartvandermeeren.aight.ui.components.copyToClipboard
import nl.bartvandermeeren.aight.ui.components.outlined
import nl.bartvandermeeren.aight.ui.components.pane
import nl.bartvandermeeren.aight.ui.components.rememberImageBitmap
import nl.bartvandermeeren.aight.ui.theme.GoogleSansCode
import nl.bartvandermeeren.aight.ui.theme.Palette

/** A light bubble like Superhuman's "yes!", its tail corner toward the edge the user writes from. */
private val UserBubbleShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp)

@Composable
fun UserMessageItem(message: UiMessage, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (message.images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                message.images.take(4).forEach { MessageImage(it) }
            }
        }
        if (message.text.isNotBlank()) {
            var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
            val long = message.text.length > 420 || message.text.count { it == '\n' } > 6
            Box(Modifier.pane(UserBubbleShape, Palette.UserBubble)) {
                Box(Modifier.animateContentSize()) {
                    SelectionContainer {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Palette.TextPrimary,
                            maxLines = if (long && !expanded) 6 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                start = 22.dp, end = if (long) 52.dp else 22.dp, top = 14.dp, bottom = 14.dp,
                            ),
                        )
                    }
                    if (long) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(36.dp)
                                .pane(CircleShape, Palette.Card)
                                .clickable { expanded = !expanded },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                                tint = Palette.Icon,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageImage(ref: ImageRef) {
    val image by rememberImageBitmap(ref.source, maxDimension = 720)
    Box(
        Modifier
            .sizeIn(maxWidth = 200.dp, maxHeight = 200.dp)
            .size(168.dp)
            .pane(RoundedCornerShape(24.dp), Palette.Card),
    ) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(168.dp)) }
    }
}

@Composable
fun AssistantMessageItem(
    message: UiMessage,
    isLast: Boolean,
    assistantName: String,
    speaking: Boolean,
    onSpeak: () -> Unit,
    onRetry: () -> Unit,
    onApproval: (String) -> Unit,
    modifier: Modifier = Modifier,
    showDisclaimer: Boolean = true,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val hasWork = message.steps.isNotEmpty() || message.reasoning.isNotBlank()
        if (message.isStreaming && message.text.isEmpty() && !hasWork) {
            WorkingRow(
                label = stringResource(
                    when {
                        message.stopping -> R.string.status_stopping
                        message.reconnecting -> R.string.status_reconnecting
                        else -> R.string.status_thinking
                    },
                ),
            )
        }
        if (hasWork) WorkPanel(message)
        if (message.isStreaming && message.reconnecting && hasWork) {
            Text(stringResource(R.string.status_reconnecting), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        }
        message.approval?.let { ApprovalCard(it, onApproval) }
        if (message.text.isNotBlank()) {
            SelectionContainer { Markdown(message.text) }
        }
        when (message.state) {
            MessageState.Failed -> ErrorRow(message.error ?: stringResource(R.string.error_generic), onRetry = if (isLast) onRetry else null)
            MessageState.Cancelled -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Stop, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.status_stopped), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                if (isLast) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.action_retry),
                        style = MaterialTheme.typography.labelLarge,
                        color = Palette.Link,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry).padding(4.dp),
                    )
                }
            }
            else -> Unit
        }
        if (message.state == MessageState.Done && message.text.isNotBlank()) {
            ActionRow(message.text, speaking, onSpeak)
            if (isLast && showDisclaimer) {
                Text(
                    stringResource(R.string.disclaimer, assistantName),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
fun WorkingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AightMark(size = 22.dp, working = true)
        ShimmerText(label)
    }
}

@Composable
private fun ShimmerText(text: String) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    val dots = ".".repeat((tick % 3 + 1).toInt())
    Text("$text$dots", style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary)
}

/** beautifului.dev's "Thought for 4 seconds ⌄": a quiet line that expands into the agent's steps as tool chips. */
@Composable
private fun WorkPanel(message: UiMessage) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (message.isStreaming) {
        LaunchedEffect(message.id) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    val running = message.steps.lastOrNull { it.running }
    val title = when {
        message.isStreaming && message.stopping -> stringResource(R.string.status_stopping)
        message.isStreaming && running != null -> stringResource(R.string.work_running_tool, prettyToolName(running.title))
        message.isStreaming -> stringResource(R.string.work_running)
        else -> {
            val ms = message.workedMs
            if (ms != null && ms > 0) stringResource(R.string.work_done_duration, formatDuration(ms)) else stringResource(R.string.work_done)
        }
    }
    val elapsed = message.startedAtMs?.let { formatDuration(now - it) }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(start = 2.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (message.isStreaming) {
                AightMark(size = 20.dp, working = true)
            } else {
                AightMark(size = 18.dp)
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (message.isStreaming && elapsed != null) {
                Text(elapsed, style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
            }
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                tint = Palette.TextSecondary,
                modifier = Modifier.size(22.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (message.reasoning.isNotBlank()) {
                    StepRow(
                        icon = { Icon(Icons.Outlined.Psychology, null, tint = Palette.TextSecondary, modifier = Modifier.size(18.dp)) },
                        title = stringResource(R.string.work_thoughts),
                        detail = message.reasoning.trim(),
                        monospace = false,
                        detailLines = 12,
                    )
                }
                message.steps.forEach { StepItem(it) }
            }
        }
    }
}

@Composable
private fun StepItem(step: Step) {
    val icon: @Composable () -> Unit = {
        when {
            step.running -> CircularProgressIndicator(strokeWidth = 2.dp, color = Palette.Link, modifier = Modifier.size(16.dp))
            step.failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = Palette.Danger, modifier = Modifier.size(18.dp))
            step.kind == StepKind.Commentary -> Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Palette.TextSecondary, modifier = Modifier.size(17.dp))
            step.kind == StepKind.Subagent -> Icon(Icons.Outlined.AccountTree, null, tint = Palette.TextSecondary, modifier = Modifier.size(17.dp))
            else -> Icon(Icons.Rounded.Build, null, tint = Palette.TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
    when (step.kind) {
        StepKind.Commentary -> StepRow(icon, step.title, null, monospace = false, detailLines = 0, titleLines = 8)
        StepKind.Subagent -> StepRow(icon, stringResource(R.string.work_subagent), step.result ?: step.title, monospace = false, detailLines = 4)
        StepKind.Tool -> {
            val duration = step.durationSec?.takeIf { it >= 0.1 }?.let { " · " + formatStepDuration(it) }.orEmpty()
            StepRow(icon, prettyToolName(step.title) + duration, step.detail ?: step.result, monospace = true, detailLines = 3)
        }
    }
}

/**
 * One step as a beautifului.dev tool chip: icon, a bold label and, for tools, the call itself in a
 * monospace pill on the same line. Prose details (thoughts, a subagent's result) go underneath.
 */
@Composable
private fun StepRow(
    icon: @Composable () -> Unit,
    title: String,
    detail: String?,
    monospace: Boolean,
    detailLines: Int,
    titleLines: Int = 2,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 2.dp).size(20.dp), contentAlignment = Alignment.Center) { icon() }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = Palette.TextPrimary,
                    maxLines = titleLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (monospace && !detail.isNullOrBlank()) {
                    Text(
                        detail.lineSequence().first(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = GoogleSansCode, fontSize = 12.5.sp),
                        color = Palette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .pane(RoundedCornerShape(8.dp), Palette.InlineCode, outline = Palette.Hairline)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            if (!monospace && !detail.isNullOrBlank() && detailLines > 0) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                    maxLines = detailLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalCard(request: ApprovalRequest, onChoice: (String) -> Unit) {
    // beautifului.dev's approval card: a darker card inside the chat card, one white primary choice.
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxWidth().pane(shape, Palette.Code, outline = Palette.SparkAmber.copy(alpha = 0.4f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = Palette.SparkAmber, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.approval_title), style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary)
            }
            request.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
            }
            request.command?.takeIf { it.isNotBlank() }?.let {
                Box(Modifier.fillMaxWidth().pane(RoundedCornerShape(12.dp), Palette.InlineCode, outline = Palette.Hairline)) {
                    Text(
                        it,
                        fontFamily = GoogleSansCode,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = Palette.TextPrimary,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
            val primary = request.choices.firstOrNull { it != "deny" }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                request.choices.forEach { choice ->
                    val pending = request.pendingChoice == choice
                    val isPrimary = choice == primary
                    Box(
                        Modifier
                            .then(if (isPrimary) Modifier.pane(CircleShape, Palette.Primary) else Modifier.outlined(CircleShape))
                            .clickable(enabled = request.pendingChoice == null) { onChoice(choice) },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val ink = if (isPrimary) Palette.OnPrimary else Palette.TextPrimary
                            if (pending) CircularProgressIndicator(strokeWidth = 2.dp, color = ink, modifier = Modifier.size(14.dp))
                            Text(approvalLabel(choice), style = MaterialTheme.typography.labelLarge, color = ink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun approvalLabel(choice: String) = when (choice) {
    "once" -> stringResource(R.string.approval_once)
    "session" -> stringResource(R.string.approval_session)
    "always" -> stringResource(R.string.approval_always)
    "deny" -> stringResource(R.string.approval_deny)
    else -> choice
}

@Composable
private fun ErrorRow(message: String, onRetry: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().pane(RoundedCornerShape(18.dp), Palette.DangerPane, outline = Palette.Danger.copy(alpha = 0.3f))) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Palette.Danger, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            if (onRetry != null) {
                PlainIconButton(Icons.Rounded.Refresh, stringResource(R.string.action_retry), onClick = onRetry, size = 40.dp, iconSize = 22.dp)
            }
        }
    }
}

@Composable
private fun ActionRow(text: String, speaking: Boolean, onSpeak: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(44.dp)) {
        PlainIconButton(
            if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
            stringResource(R.string.action_copy),
            onClick = { copyToClipboard(context, text); copied = true },
            size = 44.dp, iconSize = 22.dp,
        )
        PlainIconButton(
            Icons.Outlined.Share,
            stringResource(R.string.action_share),
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, null))
            },
            size = 44.dp, iconSize = 22.dp,
        )
        Spacer(Modifier.weight(1f))
        PlainIconButton(
            if (speaking) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
            stringResource(if (speaking) R.string.action_stop_reading else R.string.action_read_aloud),
            onClick = onSpeak,
            size = 44.dp, iconSize = 22.dp,
            tint = if (speaking) Palette.Link else Palette.Icon,
        )
    }
}

fun prettyToolName(raw: String): String {
    val cleaned = raw.substringAfterLast("__").replace('_', ' ').replace('-', ' ').trim()
    return cleaned.replaceFirstChar { it.uppercase() }
}

/** Tool steps are often sub-second, so show one decimal below ten seconds. */
fun formatStepDuration(seconds: Double): String =
    if (seconds < 10) String.format(java.util.Locale.ROOT, "%.1fs", seconds) else formatDuration((seconds * 1000).toLong())

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
