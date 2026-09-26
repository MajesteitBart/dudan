package nl.bartvandermeeren.aight.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.ui.theme.Palette

/** A picked image waiting in the composer. Exactly one of [uri] or [bitmap] is set. */
data class Attachment(val id: Long, val uri: Uri? = null, val bitmap: Bitmap? = null)

private enum class TrailingMode { Idle, Content, Listening, Busy }

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
    /** The glass tint. It frosts the screen's backdrop where there is one (see [LocalHazeState]). */
    containerColor: Color = Palette.Composer,
    focusRequester: FocusRequester? = null,
    addMenu: @Composable () -> Unit = {},
) {
    val hasContent = text.isNotBlank() || attachments.isNotEmpty()
    val mode = when {
        busy -> TrailingMode.Busy
        listening -> TrailingMode.Listening
        hasContent -> TrailingMode.Content
        else -> TrailingMode.Idle
    }
    Box(modifier.fillMaxWidth().glass(RoundedCornerShape(34.dp), containerColor, LocalHazeState.current)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            if (attachments.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp, top = 4.dp),
                ) {
                    items(attachments, key = { it.id }) { attachment ->
                        AttachmentThumb(attachment, onRemove = { onRemoveAttachment(attachment) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    PlainIconButton(Icons.Rounded.Add, stringResource(R.string.action_add), onClick = onAddClick, iconSize = 28.dp)
                    addMenu()
                }
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 10.dp),
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
                            value = text,
                            onValueChange = onTextChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.TextPrimary, fontSize = 18.sp),
                            cursorBrush = SolidColor(Palette.Link),
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
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.85f)) togetherWith fadeOut() },
                    label = "composer-trailing",
                ) { current ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (current) {
                            TrailingMode.Busy ->
                                CircleIconButton(Icons.Rounded.CropSquare, stringResource(R.string.action_stop), Palette.Card, onStop)
                            TrailingMode.Listening -> {
                                CircleIconButton(Icons.Rounded.CropSquare, stringResource(R.string.action_stop_listening), Palette.Surface, onStopListening)
                                CircleIconButton(Icons.Rounded.ArrowUpward, stringResource(R.string.action_send), GlassDefaults.Orb, onSend)
                            }
                            TrailingMode.Content -> {
                                PlainIconButton(Icons.Rounded.MicNone, stringResource(R.string.action_voice), onClick = onMicClick)
                                CircleIconButton(Icons.Rounded.ArrowUpward, stringResource(R.string.action_send), GlassDefaults.Orb, onSend)
                            }
                            TrailingMode.Idle -> {
                                PlainIconButton(Icons.Rounded.MicNone, stringResource(R.string.action_voice), onClick = onMicClick)
                                if (onLiveClick != null) {
                                    CircleIconButton(AightIcons.Live, stringResource(R.string.action_live), Palette.Live, onLiveClick)
                                }
                            }
                        }
                    }
                }
            }
        }
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

/** A round glass button with a [background] tint or gradient. */
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
            .background(GlassDefaults.Sheen)
            .border(GlassDefaults.Border, CircleShape)
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
        val modifier = Modifier.fillMaxSize().glass(shape, Palette.Card)
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
                .glass(CircleShape, Palette.Menu)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove), tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}
