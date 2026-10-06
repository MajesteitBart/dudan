package nl.bartvandermeeren.dudan.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.chat.SkillTags
import nl.bartvandermeeren.dudan.data.SkillInfo
import nl.bartvandermeeren.dudan.ui.theme.LocalAccent
import nl.bartvandermeeren.dudan.ui.theme.Palette

/**
 * Something waiting in the composer. A photo has [uri] or [bitmap] and goes to the model as a
 * picture; any other file has [uri] and [file] and is uploaded to the Hermes host as soon as it's picked.
 */
data class Attachment(val id: Long, val uri: Uri? = null, val bitmap: Bitmap? = null, val file: PickedFile? = null)

private enum class TrailingMode { Idle, Content, Listening, Busy }

/**
 * The prompt bar, after beautifului.dev's: the message on top, and below it the attach button, an
 * optional [modelPicker], the mic and one primary button (Live, Send or Stop) in the user's accent, on a
 * glass card of its own.
 */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    attachments: List<Attachment>,
    onRemoveAttachment: (Attachment) -> Unit,
    listening: Boolean,
    voiceLevel: Float,
    /** What the recognizer heard so far, shown instead of the text field while listening. */
    voiceText: String = "",
    busy: Boolean,
    onAddClick: () -> Unit,
    onMicClick: () -> Unit,
    onStopListening: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onLiveClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** The glass tint. */
    containerColor: Color = Palette.Composer,
    /** The wash under that tint; the overlay has no sky to wash, so it passes Transparent. */
    containerWash: Color = Palette.GlassWash,
    /** What that glass turns into with Reduce transparency. */
    solidColor: Color = Palette.ChromeSolid,
    focusRequester: FocusRequester? = null,
    modelPicker: (@Composable () -> Unit)? = null,
    addMenu: @Composable () -> Unit = {},
    onRetryAttachment: (Attachment) -> Unit = {},
    /** Hermes' skills: typing `$` lists them, and picking one writes its tag. */
    skills: List<SkillInfo> = emptyList(),
) {
    // The field keeps its own cursor. Text set from outside (dictation, Use in chat, clearing after a
    // send) arrives with the cursor at the end.
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val value = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))
    val query = remember(value.text, value.selection, skills) {
        if (skills.isEmpty() || !value.selection.collapsed) null else SkillTags.queryAt(value.text, value.selection.start)
    }
    val suggestions = remember(query, skills) { query?.let { SkillTags.suggest(it.text, skills) }.orEmpty() }
    val skillSlugs = remember(skills) { skills.mapTo(HashSet()) { SkillTags.slug(it.name) } }
    fun pickSkill(skill: SkillInfo) {
        val q = query ?: return
        val (completed, cursor) = SkillTags.complete(value.text, q, skill)
        field = TextFieldValue(completed, TextRange(cursor))
        onTextChange(completed)
    }

    val hasContent = text.isNotBlank() || attachments.isNotEmpty()
    val mode = when {
        busy -> TrailingMode.Busy
        listening -> TrailingMode.Listening
        hasContent -> TrailingMode.Content
        else -> TrailingMode.Idle
    }
    val accent = LocalAccent.current
    val container = Modifier.glass(RoundedCornerShape(28.dp), containerColor, solid = solidColor, wash = containerWash)
    Column(modifier.fillMaxWidth().then(container).padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
        if (suggestions.isNotEmpty() && !listening) {
            SkillSuggestions(suggestions, onPick = ::pickSkill)
        }
        if (attachments.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 10.dp, end = 8.dp, bottom = 4.dp, top = 8.dp),
            ) {
                items(attachments, key = { it.id }) { attachment ->
                    if (attachment.file != null) {
                        FileChip(attachment.file, onRemove = { onRemoveAttachment(attachment) }, onRetry = { onRetryAttachment(attachment) })
                    } else {
                        AttachmentThumb(attachment, onRemove = { onRemoveAttachment(attachment) })
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (listening && voiceText.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        voiceText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                        color = Palette.TextPrimary,
                        maxLines = 1,
                        // The newest words matter; let the start of a long sentence scroll away.
                        overflow = TextOverflow.StartEllipsis,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    )
                    VoiceWaveform(voiceLevel, Modifier.padding(start = 8.dp).width(64.dp), bars = 12)
                }
            } else if (listening) {
                VoiceWaveform(voiceLevel, Modifier.padding(horizontal = 4.dp))
            } else {
                BasicTextField(
                    value = value,
                    onValueChange = {
                        field = it
                        if (it.text != text) onTextChange(it.text)
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.TextPrimary, fontSize = 18.sp),
                    visualTransformation = remember(skillSlugs, accent) { SkillTagHighlight(skillSlugs, accent.soft) },
                    cursorBrush = SolidColor(accent.soft),
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    placeholder,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                                    color = Palette.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                PlainIconButton(Icons.Rounded.Add, stringResource(R.string.action_add), onClick = onAddClick, size = 44.dp, iconSize = 26.dp)
                addMenu()
            }
            Box(Modifier.weight(1f)) { modelPicker?.invoke() }
            AnimatedContent(
                targetState = mode,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.85f)) togetherWith fadeOut() },
                label = "composer-trailing",
            ) { current ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (current) {
                        TrailingMode.Busy ->
                            CircleIconButton(Icons.Rounded.Stop, stringResource(R.string.action_stop), accent.color, onStop, tint = accent.on)
                        TrailingMode.Listening -> {
                            CircleIconButton(Icons.Rounded.Stop, stringResource(R.string.action_stop_listening), Palette.Card, onStopListening)
                            CircleIconButton(Icons.Rounded.ArrowUpward, stringResource(R.string.action_send), accent.color, onSend, tint = accent.on)
                        }
                        TrailingMode.Content -> {
                            PlainIconButton(Icons.Rounded.MicNone, stringResource(R.string.action_voice), onClick = onMicClick, size = 44.dp)
                            CircleIconButton(Icons.Rounded.ArrowUpward, stringResource(R.string.action_send), accent.color, onSend, tint = accent.on)
                        }
                        TrailingMode.Idle -> {
                            PlainIconButton(Icons.Rounded.MicNone, stringResource(R.string.action_voice), onClick = onMicClick, size = 44.dp)
                            if (onLiveClick != null) {
                                CircleIconButton(DudanIcons.Live, stringResource(R.string.action_live), accent.color, onLiveClick, tint = accent.on)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Skills matching the `$tag` being typed, above the message; the list scrolls once it holds more than a few. */
@Composable
private fun SkillSuggestions(skills: List<SkillInfo>, onPick: (SkillInfo) -> Unit) {
    val accent = LocalAccent.current
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 236.dp).padding(top = 4.dp)) {
        items(skills, key = { it.name }) { skill ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPick(skill) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "\$" + SkillTags.slug(skill.name),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = accent.soft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                skill.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    HorizontalDivider(color = Palette.Hairline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
}

/** The active model as a quiet text button in the prompt bar, like beautifului.dev's "Vanilla 1 ⌄". */
@Composable
fun ModelPickerButton(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = Palette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (subtitle.isNotEmpty()) {
            Text(" · $subtitle", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, maxLines = 1)
        }
        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.padding(start = 2.dp).size(18.dp))
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    description: String,
    background: Color,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    tint: Color = Color.White,
) = CircleIconButton(icon, description, SolidColor(background), onClick, size, tint)

/** A round button with a [background] tint or gradient. It sits inside the composer's glass, so it stays flat. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    description: String,
    background: Brush,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    tint: Color = Color.White,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun PlainIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
    tint: Color = Palette.Icon,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun AttachmentThumb(attachment: Attachment, onRemove: () -> Unit) {
    Box(Modifier.size(72.dp)) {
        val shape = RoundedCornerShape(16.dp)
        val modifier = Modifier.fillMaxSize().pane(shape, Palette.Card)
        when {
            attachment.bitmap != null -> Image(
                attachment.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier,
            )
            attachment.uri != null -> {
                val image by rememberImageBitmap(attachment.uri.toString(), maxDimension = 256)
                Box(modifier) {
                    image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(22.dp)
                .pane(CircleShape, Palette.MenuSolid)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove), tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}
