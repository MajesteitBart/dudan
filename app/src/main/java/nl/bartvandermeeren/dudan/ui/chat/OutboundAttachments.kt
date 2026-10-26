package nl.bartvandermeeren.dudan.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.OutboundAttachment
import nl.bartvandermeeren.dudan.ui.components.FileKind
import nl.bartvandermeeren.dudan.ui.components.formatBytes
import nl.bartvandermeeren.dudan.ui.components.pane
import nl.bartvandermeeren.dudan.ui.theme.Palette

private const val PREVIEW_MAX_DIMENSION = 1600

/** Decoded previews by SHA-256, so scrolling back to an image doesn't download it again. */
private val previewCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/**
 * Files the agent returned with an answer; see docs/outbound-attachments.md. Images show at their own
 * aspect ratio, and every file has Save file, which asks Android's document picker where it goes.
 * Nothing is opened or installed. A preview stops downloading when its row scrolls away; a save runs in
 * [saveScope], so it keeps going until the chat closes.
 */
@Composable
fun OutboundAttachments(api: HermesApi, sessionId: String, attachments: List<OutboundAttachment>, saveScope: CoroutineScope) {
    // Metadata names its chat; a file listed under another chat doesn't show in this one.
    attachments.filter { it.sessionId == sessionId }.forEach { file ->
        key(file.inputId, file.index, file.sha256) { OutboundAttachmentItem(api, file, saveScope) }
    }
}

@Composable
private fun OutboundAttachmentItem(api: HermesApi, file: OutboundAttachment, saveScope: CoroutineScope) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf(if (file.previewable) previewCache.get(file.sha256) else null) }
    var loading by remember { mutableStateOf(file.previewable && preview == null) }
    var previewFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var retry by remember { mutableIntStateOf(0) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mime)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        saveScope.launch {
            saving = true; saved = false; problem = null
            var written = false
            try {
                withVerifiedFile(context, api, file) { temp ->
                    val target = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open destination")
                    target.use { out -> temp.inputStream().use { it.copyTo(out) } }
                }
                written = true
                saved = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = problemFor(e, R.string.attachment_save_failed)
            } finally {
                // The picker already created the file; don't leave it empty or half written.
                if (!written) withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                saving = false
            }
        }
    }

    LaunchedEffect(api, file, retry) {
        if (!file.previewable || preview != null) return@LaunchedEffect
        loading = true; previewFailed = false; problem = null
        try {
            val bitmap = withVerifiedFile(context, api, file, ::decodePreview)
            previewCache.put(file.sha256, bitmap)
            preview = bitmap
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            previewFailed = true
            problem = problemFor(e, R.string.attachment_unavailable)
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        preview?.let { bitmap ->
            Image(
                bitmap,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                    .clip(RoundedCornerShape(18.dp)),
            )
        }
        Row(
            Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .pane(RoundedCornerShape(18.dp), Palette.Card)
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FileKind(file.mime, file.name)
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
            TextButton(onClick = { save.launch(file.name) }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.attachment_save))
            }
        }
        if (loading || saving) LinearProgressIndicator(Modifier.widthIn(max = 360.dp).fillMaxWidth())
        problem?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(message), style = MaterialTheme.typography.bodySmall, color = Palette.Danger, modifier = Modifier.weight(1f, fill = false))
                if (previewFailed && message != R.string.attachment_gone) {
                    TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
        if (saved) Text(stringResource(R.string.attachment_saved), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    }
}

/** 404 and 410: the server no longer has the bytes, or not for this chat, so trying again won't help. */
@StringRes
private fun problemFor(error: Exception, @StringRes otherwise: Int): Int =
    if (error is HermesApi.HermesException && error.status in setOf(404, 410)) R.string.attachment_gone else otherwise

/**
 * Downloads [file] into a private temporary file, runs [use] on the verified copy off the main thread
 * and deletes it again, also when anything fails or the caller leaves.
 */
private suspend fun <T> withVerifiedFile(context: Context, api: HermesApi, file: OutboundAttachment, use: (File) -> T): T {
    val temp = withContext(Dispatchers.IO) { AttachmentTemp.create(context) }
    try {
        api.downloadAttachment(file, temp)
        return withContext(Dispatchers.IO) { use(temp) }
    } finally {
        temp.delete()
    }
}

/** Temporary files live in their own cache folder. The first one a process makes clears what a killed process left there. */
private object AttachmentTemp {
    private var swept = false

    @Synchronized
    fun create(context: Context): File {
        val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
        if (!swept) {
            dir.listFiles()?.forEach { it.delete() }
            swept = true
        }
        return File.createTempFile("attachment-", ".tmp", dir)
    }
}

/** Decodes a verified image at most [PREVIEW_MAX_DIMENSION] on its longest side. Absurd dimensions are refused before decoding. */
private fun decodePreview(file: File): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth !in 1..16_384 || bounds.outHeight !in 1..16_384) throw IOException("Unsupported image size")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_MAX_DIMENSION) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap() ?: throw IOException("Invalid image")
}
