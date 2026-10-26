package nl.bartvandermeeren.dudan.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.OutboundAttachment
import nl.bartvandermeeren.dudan.ui.components.FileKind
import nl.bartvandermeeren.dudan.ui.components.ImageLimits
import nl.bartvandermeeren.dudan.ui.components.formatBytes
import nl.bartvandermeeren.dudan.ui.components.pane
import nl.bartvandermeeren.dudan.ui.theme.Palette

private const val PREVIEW_MAX_DIMENSION = 1600

/** Decoded previews by SHA-256, so scrolling back to an image doesn't download it again. */
private val previewCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Images that turned out too large or not decodable, by SHA-256, so they aren't downloaded again just to find out. */
private val noPreview = mutableSetOf<String>()

/** Previews decode one at a time, so several large images in one answer don't add up. Downloads still overlap. */
private val previewDecodes = Semaphore(1)

/**
 * Files the agent returned with an answer; see docs/outbound-attachments.md. Images show at their own
 * aspect ratio, and every file has Save file, which asks Android's document picker where it goes.
 * Nothing is opened or installed. A preview stops downloading when its row scrolls away; a save runs in
 * [saves], so it keeps going until the chat closes.
 */
@Composable
fun OutboundAttachments(api: HermesApi, sessionId: String, attachments: List<OutboundAttachment>, saves: AttachmentSaves) {
    // Metadata names its chat; a file listed under another chat doesn't show in this one.
    attachments.filter { it.sessionId == sessionId }.forEach { file ->
        key(file.inputId, file.index, file.sha256) { OutboundAttachmentItem(api, file, saves) }
    }
}

/** The saves of one message list. They run until the list leaves the screen, which is when the chat closes. */
@Composable
fun rememberAttachmentSaves(): AttachmentSaves {
    val scope = rememberCoroutineScope()
    return remember(scope) { AttachmentSaves(scope) }
}

/**
 * Saves of returned files. A save runs in [scope], and its state lives here rather than in the file's
 * row, so a row that scrolls away and back, or is drawn again under another message id, still shows the
 * save that is going on or how it ended.
 */
@Stable
class AttachmentSaves(private val scope: CoroutineScope) {
    sealed interface State {
        data object Saving : State
        data object Saved : State
        data class Failed(@get:StringRes val message: Int) : State
    }

    private data class Key(val sessionId: String, val inputId: String, val index: Int, val sha256: String)

    private val states = mutableStateMapOf<Key, State>()

    private fun keyOf(file: OutboundAttachment) = Key(file.sessionId, file.inputId, file.index, file.sha256)

    operator fun get(file: OutboundAttachment): State? = states[keyOf(file)]

    /** Saves [file] to [uri], which the document picker returned. While a save of the same file runs, another doesn't start. */
    fun start(context: Context, api: HermesApi, file: OutboundAttachment, uri: Uri) {
        val key = keyOf(file)
        if (states[key] == State.Saving) return
        states[key] = State.Saving
        val app = context.applicationContext
        scope.launch {
            states[key] = try {
                saveFile(app, api, file, uri)
                State.Saved
            } catch (e: CancellationException) {
                states.remove(key)
                throw e
            } catch (e: Exception) {
                State.Failed(problemFor(e, R.string.attachment_save_failed))
            }
        }
    }
}

@Composable
private fun OutboundAttachmentItem(api: HermesApi, file: OutboundAttachment, saves: AttachmentSaves) {
    val context = LocalContext.current
    val wantsPreview = file.previewable && file.sha256 !in noPreview
    var preview by remember { mutableStateOf(if (wantsPreview) previewCache.get(file.sha256) else null) }
    var loading by remember { mutableStateOf(wantsPreview && preview == null) }
    var previewProblem by remember { mutableStateOf<Int?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    // One picker at a time, so a quick second tap doesn't leave a second, empty file behind.
    var picking by remember { mutableStateOf(false) }
    val saveState = saves[file]
    val saving = saveState == AttachmentSaves.State.Saving

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mime)) { uri ->
        picking = false
        if (uri != null) saves.start(context, api, file, uri)
    }

    LaunchedEffect(api, file, retry) {
        if (!wantsPreview || preview != null) return@LaunchedEffect
        loading = true; previewProblem = null
        try {
            val bitmap = withVerifiedFile(context, api, file) { temp -> previewDecodes.withPermit { decodePreview(temp) } }
            previewCache.put(file.sha256, bitmap)
            preview = bitmap
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoPreview) {
            // The download was fine; the file shows as a card and can still be saved.
            noPreview += file.sha256
        } catch (e: Exception) {
            previewProblem = problemFor(e, R.string.attachment_unavailable)
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
            TextButton(
                onClick = {
                    if (!picking) {
                        picking = true
                        save.launch(file.name)
                    }
                },
                enabled = !saving && !picking,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.attachment_save))
            }
        }
        if (loading || saving) LinearProgressIndicator(Modifier.widthIn(max = 360.dp).fillMaxWidth())
        previewProblem?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(message), style = MaterialTheme.typography.bodySmall, color = Palette.Danger, modifier = Modifier.weight(1f, fill = false))
                if (message != R.string.attachment_gone) {
                    TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
        when (saveState) {
            // The preview may already say the file is gone; once is enough.
            is AttachmentSaves.State.Failed -> if (saveState.message != previewProblem) {
                Text(stringResource(saveState.message), style = MaterialTheme.typography.bodySmall, color = Palette.Danger)
            }
            AttachmentSaves.State.Saved -> Text(stringResource(R.string.attachment_saved), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            AttachmentSaves.State.Saving, null -> Unit
        }
    }
}

/** 404 and 410: the server no longer has the bytes, or not for this chat, so trying again won't help. */
@StringRes
private fun problemFor(error: Exception, @StringRes otherwise: Int): Int =
    if (error is HermesApi.HermesException && error.status in setOf(404, 410)) R.string.attachment_gone else otherwise

/**
 * Downloads and verifies [file], then writes it to [uri]. The picker returns a new, empty document, or
 * an existing one the user chose to overwrite; when the save fails, [removeAfterFailedSave] decides
 * whether the document goes.
 */
private suspend fun saveFile(context: Context, api: HermesApi, file: OutboundAttachment, uri: Uri) {
    val resolver = context.contentResolver
    val pickedSize = withContext(Dispatchers.IO) { documentSize(resolver, uri) }
    val opened = AtomicBoolean(false)
    var written = false
    try {
        withVerifiedFile(context, api, file) { temp ->
            val target = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open destination")
            opened.set(true)
            target.use { out -> temp.inputStream().use { it.copyTo(out) } }
        }
        written = true
    } finally {
        if (!written && removeAfterFailedSave(pickedSize, opened.get())) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
        }
    }
}

/**
 * Whether a failed save removes the picked document: when it was empty, so the picker just created it,
 * or once writing had started, which truncated it. A file the user chose to overwrite stays as it was
 * when the download or the check fails first, and so does one whose size the provider doesn't report.
 */
internal fun removeAfterFailedSave(pickedSize: Long?, opened: Boolean): Boolean = opened || pickedSize == 0L

/** The size the document provider reports for [uri], or null when it reports none. */
private fun documentSize(resolver: ContentResolver, uri: Uri): Long? = runCatching {
    resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
}.getOrNull()

/**
 * Downloads [file] into a private temporary file, runs [use] on the verified copy off the main thread
 * and deletes it again, also when anything fails or the caller leaves.
 */
private suspend fun <T> withVerifiedFile(context: Context, api: HermesApi, file: OutboundAttachment, use: suspend (File) -> T): T {
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

/** A verified file that gets no preview: too large for [ImageLimits], or not an image Android can decode. */
private class NoPreview(message: String) : IOException(message)

/**
 * Decodes a verified image at most [PREVIEW_MAX_DIMENSION] on its longest side. An image past
 * [ImageLimits] for its format is refused before decoding.
 */
private fun decodePreview(file: File): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val maxPixels = file.inputStream().buffered().use(ImageLimits::maxPixels)
    if (!ImageLimits.allows(bounds.outWidth, bounds.outHeight, maxPixels)) throw NoPreview("Unsupported image size")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_MAX_DIMENSION) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap() ?: throw NoPreview("Invalid image")
}
