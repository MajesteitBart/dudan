package nl.bartvandermeeren.aight.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.data.FileRef
import nl.bartvandermeeren.aight.ui.theme.LocalAccent
import nl.bartvandermeeren.aight.ui.theme.Palette

/** A picked file that isn't a photo: it goes to the upload service on the Hermes host. */
data class PickedFile(val name: String, val mime: String, val size: Long, val upload: UploadState = UploadState.Uploading(0))

sealed interface UploadState {
    data class Uploading(val sent: Long) : UploadState
    data class Done(val ref: FileRef) : UploadState
    data class Failed(val message: String) : UploadState
}

fun fileIcon(mime: String, name: String): ImageVector {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("video/") -> Icons.Outlined.Movie
        mime.startsWith("audio/") -> Icons.Outlined.AudioFile
        mime.startsWith("image/") -> Icons.Outlined.Image
        mime == "application/pdf" || extension == "pdf" -> Icons.Outlined.PictureAsPdf
        extension in setOf("xls", "xlsx", "csv", "ods", "tsv") -> Icons.Outlined.TableChart
        extension in setOf("ppt", "pptx", "key", "odp") -> Icons.Outlined.Slideshow
        extension in setOf("zip", "rar", "7z", "tar", "gz", "tgz") -> Icons.Outlined.Archive
        mime.startsWith("text/") || extension in setOf("doc", "docx", "odt", "rtf", "md", "txt") -> Icons.Outlined.Description
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}

/** 820 KB, 12.4 MB, 1.2 GB. */
fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1024 -> String.format(Locale.getDefault(), "%.1f GB", mb / 1024)
        mb >= 10 -> String.format(Locale.getDefault(), "%.0f MB", mb)
        mb >= 1 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        else -> String.format(Locale.getDefault(), "%.0f KB", kb.coerceAtLeast(1.0))
    }
}

/** A file in a sent message: its kind, name and size. */
@Composable
fun FileBadge(file: FileRef, modifier: Modifier = Modifier) {
    Row(
        modifier
            .widthIn(max = 280.dp)
            .pane(RoundedCornerShape(18.dp), Palette.Card)
            .padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FileKind(file.mime, file.name)
        Column {
            Text(file.name, style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            file.size?.let { Text(formatBytes(it), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
        }
    }
}

@Composable
private fun FileKind(mime: String, name: String) {
    Box(Modifier.size(40.dp).pane(RoundedCornerShape(12.dp), Palette.Surface, outline = Palette.Hairline), contentAlignment = Alignment.Center) {
        Icon(fileIcon(mime, name), contentDescription = null, tint = Palette.Icon, modifier = Modifier.size(22.dp))
    }
}

/** A file in the prompt bar: its name, the upload's progress, and a retry when it failed. */
@Composable
fun FileChip(file: PickedFile, onRemove: () -> Unit, onRetry: () -> Unit) {
    val failed = file.upload as? UploadState.Failed
    Box(Modifier.size(width = 232.dp, height = 72.dp)) {
        Row(
            Modifier
                .fillMaxSize()
                .pane(RoundedCornerShape(16.dp), Palette.Card, outline = if (failed != null) Palette.Danger.copy(alpha = 0.5f) else null)
                .then(if (failed != null) Modifier.clickable(onClick = onRetry) else Modifier)
                .padding(start = 10.dp, end = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FileKind(file.mime, file.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(file.name, style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                when (val upload = file.upload) {
                    is UploadState.Uploading -> {
                        val fraction = if (file.size > 0) (upload.sent.toFloat() / file.size).coerceIn(0f, 1f) else 0f
                        Text(
                            stringResource(R.string.upload_progress, (fraction * 100).toInt(), formatBytes(file.size)),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextSecondary,
                            maxLines = 1,
                        )
                        LinearProgressIndicator(
                            progress = { fraction },
                            color = LocalAccent.current.color,
                            trackColor = Palette.Outline,
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                    }
                    is UploadState.Done -> Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    is UploadState.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Palette.Danger, modifier = Modifier.size(14.dp))
                        Text(stringResource(R.string.upload_failed_retry), style = MaterialTheme.typography.bodySmall, color = Palette.Danger, maxLines = 1)
                    }
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
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_remove), tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}
