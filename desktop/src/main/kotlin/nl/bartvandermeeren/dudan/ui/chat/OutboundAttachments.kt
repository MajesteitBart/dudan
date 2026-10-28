package nl.bartvandermeeren.dudan.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.OutboundAttachment
import nl.bartvandermeeren.dudan.desktop.DesktopFiles
import nl.bartvandermeeren.dudan.desktop.res.*
import nl.bartvandermeeren.dudan.ui.components.FileKind
import nl.bartvandermeeren.dudan.ui.components.formatBytes
import nl.bartvandermeeren.dudan.ui.components.pane
import nl.bartvandermeeren.dudan.ui.theme.Palette
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Files the agent returned with an answer, as on the phone; see docs/outbound-attachments.md. Each has
 * Save file, which asks where it goes in Windows' own save dialog. Nothing is opened or run: Show in
 * folder points Explorer at the saved file. A save runs in [saves], so it keeps going while its row
 * scrolls away.
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
 * Saves of returned files. A file downloads beside the name the user picked and replaces it only once
 * its size and SHA-256 check out, so a failed save never leaves half a file, or costs the one it would
 * have overwritten.
 */
@Stable
class AttachmentSaves(private val scope: CoroutineScope) {
    sealed interface State {
        data object Saving : State
        data class Saved(val file: File) : State
        data class Failed(val message: StringResource) : State
    }

    private data class Key(val sessionId: String, val inputId: String, val index: Int, val sha256: String)

    private val states = mutableStateMapOf<Key, State>()

    private fun keyOf(file: OutboundAttachment) = Key(file.sessionId, file.inputId, file.index, file.sha256)

    operator fun get(file: OutboundAttachment): State? = states[keyOf(file)]

    /** Saves [file] as [target]. While a save of the same file runs, another doesn't start. */
    fun start(api: HermesApi, file: OutboundAttachment, target: File) {
        val key = keyOf(file)
        if (states[key] == State.Saving) return
        states[key] = State.Saving
        scope.launch {
            states[key] = try {
                save(api, file, target)
                State.Saved(target)
            } catch (e: CancellationException) {
                states.remove(key)
                throw e
            } catch (e: Exception) {
                // 404 and 410: the server no longer has the bytes, or not for this chat.
                State.Failed(if (e is HermesApi.HermesException && e.status in setOf(404, 410)) Res.string.attachment_gone else Res.string.attachment_save_failed)
            }
        }
    }

    private suspend fun save(api: HermesApi, file: OutboundAttachment, target: File) {
        val partial = withContext(Dispatchers.IO) { File.createTempFile(".dudan-", ".part", target.absoluteFile.parentFile) }
        try {
            // Deletes the partial file itself when the download or the check fails.
            api.downloadAttachment(file, partial)
            withContext(Dispatchers.IO) { Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            withContext(Dispatchers.IO) { partial.delete() }
        }
    }
}

@Composable
private fun OutboundAttachmentItem(api: HermesApi, file: OutboundAttachment, saves: AttachmentSaves) {
    val state = saves[file]
    val saving = state == AttachmentSaves.State.Saving
    val title = stringResource(Res.string.attachment_save)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .widthIn(max = 420.dp)
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
            if (state is AttachmentSaves.State.Saved) {
                TextButton(onClick = { DesktopFiles.showInFolder(state.file) }) { Text(stringResource(Res.string.desktop_show_in_folder)) }
            }
            TextButton(
                onClick = { DesktopFiles.saveAs(title, file.name)?.let { saves.start(api, file, it) } },
                enabled = !saving,
            ) {
                Text(title)
            }
        }
        if (saving) LinearProgressIndicator(Modifier.widthIn(max = 420.dp).fillMaxWidth())
        when (state) {
            is AttachmentSaves.State.Failed -> Text(stringResource(state.message), style = MaterialTheme.typography.bodySmall, color = Palette.Danger)
            is AttachmentSaves.State.Saved -> Text(stringResource(Res.string.attachment_saved), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            AttachmentSaves.State.Saving, null -> Unit
        }
    }
}
