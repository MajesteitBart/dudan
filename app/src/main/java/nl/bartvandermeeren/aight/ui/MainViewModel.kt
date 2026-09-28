package nl.bartvandermeeren.aight.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.appContainer
import nl.bartvandermeeren.aight.chat.ChatEngine
import nl.bartvandermeeren.aight.chat.Conversation
import nl.bartvandermeeren.aight.chat.userMessage
import nl.bartvandermeeren.aight.data.AppSettings
import nl.bartvandermeeren.aight.data.FileRef
import nl.bartvandermeeren.aight.data.MAX_ATTACHMENT_BYTES
import nl.bartvandermeeren.aight.data.ModelCatalog
import nl.bartvandermeeren.aight.data.ModelChoice
import nl.bartvandermeeren.aight.data.ModelProfile
import nl.bartvandermeeren.aight.data.UploadClient
import nl.bartvandermeeren.aight.ui.components.Attachment
import nl.bartvandermeeren.aight.ui.components.ImageCodec
import nl.bartvandermeeren.aight.ui.components.PickedFile
import nl.bartvandermeeren.aight.ui.components.UploadState

enum class Screen { Chat, Settings, Search, Live, Skills, Jobs }

/** A short message for a toast: a string resource and its format arguments. */
data class Notice(@param:StringRes val text: Int, val args: List<Any> = emptyList())

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer
    val engine = container.engine
    val speaker = container.speaker
    val api = container.api

    val settings: StateFlow<AppSettings?> = container.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sessions = engine.sessions

    private val _currentId = MutableStateFlow(engine.startNew())
    val currentId: StateFlow<String> = _currentId

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversation: StateFlow<Conversation> = _currentId
        .flatMapLatest { engine.conversation(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Conversation(_currentId.value, isNew = true))

    var screen by mutableStateOf(Screen.Chat)
    var composerText by mutableStateOf("")
    val attachments = mutableStateListOf<Attachment>()
    private var attachmentIds = 0L
    private var draftGeneration = 0L
    private val uploadSlots = Semaphore(2)

    /** One-shot requests from intents (assist gesture, "open this chat") that the UI must act on. */
    private val _voiceRequests = Channel<Boolean>(Channel.CONFLATED)
    val voiceRequests = _voiceRequests.receiveAsFlow()

    val modelCatalog = MutableStateFlow<ModelCatalog?>(null)
    val modelCatalogError = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            settings.collect { s -> if (s?.isConfigured == true && !engine.sessions.value.loadedOnce) engine.refreshSessions() }
        }
        viewModelScope.launch {
            // Chats, runs and models belong to one server; switching servers starts clean.
            var previousServer: String? = null
            settings.collect { s ->
                val server = s?.serverUrl ?: return@collect
                if (previousServer != null && previousServer != server) {
                    engine.reset()
                    modelCatalog.value = null
                    _currentId.value = engine.startNew()
                    composerText = ""
                    clearAttachments()
                    engine.refreshSessions()
                    loadModels()
                }
                previousServer = server
            }
        }
    }

    /** Starts a chat; [profile] Assistant marks chats started by the assistant gesture so they use the fast model. */
    fun newChat(profile: ModelProfile = ModelProfile.Chats) {
        val current = conversation.value
        val reusable = current.isNew && current.messages.isEmpty() && ChatEngine.profileOf(current.sessionId) == profile
        if (!reusable) _currentId.value = engine.startNew(profile)
        composerText = ""
        clearAttachments()
        screen = Screen.Chat
    }

    fun openSession(id: String) {
        clearAttachments()
        composerText = ""
        _currentId.value = id
        engine.load(id)
        screen = Screen.Chat
    }

    fun reloadCurrent() = engine.load(_currentId.value, force = true)

    /** True while picked images are being encoded; the composer counts as busy so nothing overtakes that turn. */
    var preparingSend by mutableStateOf(false)
        private set

    /** Short messages for the UI to show as a toast. */
    private val _notices = Channel<Notice>(Channel.BUFFERED)
    val notices = _notices.receiveAsFlow()

    /** The send that is waiting for uploads or encoding photos; Stop cancels it. */
    private var pendingSend: Job? = null

    fun send(textOverride: String? = null) {
        val text = (textOverride ?: composerText).trim()
        val picked = attachments.toList()
        if (text.isEmpty() && picked.isEmpty()) return
        if (conversation.value.isBusy || preparingSend) return
        val sessionId = _currentId.value
        speaker.stop()
        if (picked.isEmpty()) {
            if (engine.send(sessionId, text)) composerText = ""
            return
        }
        if (picked.any { it.file?.upload is UploadState.Failed }) {
            _notices.trySend(Notice(R.string.upload_fix_first))
            return
        }
        preparingSend = true
        pendingSend = viewModelScope.launch {
            try {
                // Files upload as soon as they're picked; wait for the ones still on their way.
                val files = awaitUploads(picked.filter { it.file != null }.map { it.id }) ?: return@launch
                val images = picked.filter { it.file == null }.map { attachment ->
                    try {
                        when {
                            attachment.bitmap != null -> ImageCodec.prepare(attachment.bitmap)
                            attachment.uri != null -> ImageCodec.prepare(getApplication(), attachment.uri)
                            else -> null
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                if (images.any { it == null }) {
                    // Keep the draft and attachments so the user can fix it instead of losing the photo.
                    _notices.trySend(Notice(R.string.image_prepare_failed))
                    return@launch
                }
                // Stop may have come in while a photo was being encoded.
                ensureActive()
                if (engine.send(sessionId, text, images.filterNotNull(), files)) {
                    val sent = picked.map { it.id }.toSet()
                    sent.forEach { uploadIds.remove(it) }
                    if (_currentId.value == sessionId) {
                        composerText = ""
                        attachments.removeAll { it.id in sent }
                    }
                }
            } finally {
                preparingSend = false
                pendingSend = null
            }
        }
    }

    /** The uploaded files once all of [ids] are in, or null when one failed or was removed. */
    private suspend fun awaitUploads(ids: List<Long>): List<FileRef>? {
        if (ids.isEmpty()) return emptyList()
        val states = snapshotFlow { ids.map { id -> attachments.firstOrNull { it.id == id }?.file?.upload } }
            .first { list -> list.all { it is UploadState.Done } || list.any { it == null || it is UploadState.Failed } }
        return states.takeIf { list -> list.all { it is UploadState.Done } }?.map { (it as UploadState.Done).ref }
    }

    /** Sends what an OpenUI button or follow-up in a reply asks for. The draft in the composer stays. */
    fun sendFromReply(text: String): Boolean {
        if (preparingSend) return false
        speaker.stop()
        return engine.send(_currentId.value, text)
    }

    fun stop() {
        val waiting = pendingSend
        if (waiting != null && waiting.isActive) {
            waiting.cancel()
            return
        }
        engine.stop(_currentId.value)
    }
    fun retry() = engine.retry(_currentId.value)
    fun resolveApproval(choice: String) = engine.resolveApproval(_currentId.value, choice)

    fun addImage(uri: Uri) {
        attachments += Attachment(++attachmentIds, uri = uri)
    }

    fun addImage(bitmap: Bitmap) {
        attachments += Attachment(++attachmentIds, bitmap = bitmap)
    }

    /**
     * Adds what the user picked or shared. Photos the phone can decode go to the model as pictures,
     * as before; anything else (video, PDF, documents, archives) is uploaded to the Hermes host.
     */
    fun addPicked(uris: List<Uri>, mimeHint: String? = null) {
        if (uris.isEmpty()) return
        val generation = draftGeneration
        viewModelScope.launch {
            // Asking a document provider about a file is IPC, and a cloud provider can take seconds.
            val picked = withContext(Dispatchers.IO) {
                uris.map { uri -> uri to runCatching { FileInfo.of(getApplication(), uri) } }
            }
            if (generation != draftGeneration) return@launch
            if (picked.any { it.second.isFailure }) _notices.trySend(Notice(R.string.file_read_failed))
            picked.forEach { (uri, result) ->
                val info = result.getOrNull() ?: return@forEach
                if (isModelPhoto(info.mime, mimeHint)) {
                    addImage(uri)
                } else {
                    addFile(uri, info)
                }
            }
        }
    }

    private fun addFile(uri: Uri, info: FileInfo) {
        if (info.size > MAX_ATTACHMENT_BYTES) {
            _notices.trySend(Notice(R.string.file_too_large, listOf(info.name, MAX_ATTACHMENT_BYTES / (1024 * 1024))))
            return
        }
        val attachment = Attachment(++attachmentIds, uri = uri, file = PickedFile(info.name, info.mime, info.size))
        attachments += attachment
        startUpload(attachment.id)
    }

    private val uploadJobs = mutableMapOf<Long, Job>()

    /**
     * The upload service's id per attachment, so a removed file can be deleted from the Hermes host.
     * The upload client sets it from its own thread.
     */
    private val uploadIds = ConcurrentHashMap<Long, String>()

    private fun updateFile(id: Long, transform: (PickedFile) -> PickedFile) {
        val index = attachments.indexOfFirst { it.id == id }
        if (index < 0) return
        val attachment = attachments[index]
        val file = attachment.file ?: return
        attachments[index] = attachment.copy(file = transform(file))
    }

    private fun startUpload(id: Long) {
        val attachment = attachments.firstOrNull { it.id == id } ?: return
        val uri = attachment.uri ?: return
        val picked = attachment.file ?: return
        updateFile(id) { it.copy(upload = UploadState.Uploading(0)) }
        uploadJobs.remove(id)?.cancel()
        // A retry starts a new upload; the failed one's partial file isn't needed any more.
        uploadIds.remove(id)?.let(::deleteFromHost)
        uploadJobs[id] = viewModelScope.launch {
            try {
                // Some providers don't report a size; count the bytes once instead.
                val size = if (picked.size > 0) picked.size else withContext(Dispatchers.IO) { FileInfo.countBytes(getApplication(), uri) }
                if (size != picked.size) updateFile(id) { it.copy(size = size) }
                if (size > MAX_ATTACHMENT_BYTES) {
                    throw UploadClient.UploadException(
                        getApplication<Application>().getString(R.string.file_too_large, picked.name, MAX_ATTACHMENT_BYTES / (1024 * 1024)),
                    )
                }
                val source = UriSource(getApplication(), uri, picked.name, picked.mime, size)
                val ref = uploadSlots.withPermit {
                    container.uploads.upload(
                        source,
                        onCreated = { uploadId -> uploadIds[id] = uploadId },
                        onProgress = { sent ->
                            viewModelScope.launch {
                                updateFile(id) { file -> if (file.upload is UploadState.Uploading) file.copy(upload = UploadState.Uploading(sent)) else file }
                            }
                        },
                    )
                }
                updateFile(id) { it.copy(upload = UploadState.Done(ref)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = uploadFailure(e)
                updateFile(id) { it.copy(upload = UploadState.Failed(reason)) }
                _notices.trySend(Notice(R.string.upload_failed, listOf(picked.name, reason)))
            } finally {
                if (uploadJobs[id] == coroutineContext[Job]) uploadJobs.remove(id)
                // Removed while the upload was being created: forgetUpload had no id to delete then.
                if (attachments.none { it.id == id }) uploadIds.remove(id)?.let(::deleteFromHost)
            }
        }
    }

    private fun uploadFailure(e: Exception): String {
        val app = getApplication<Application>()
        return when {
            e is UploadClient.UploadException && e.status in setOf(401, 403) -> app.getString(R.string.upload_rejected_key)
            e is java.net.ConnectException || e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ->
                app.getString(R.string.upload_unreachable)
            else -> e.message ?: e.toString()
        }
    }

    fun retryUpload(attachment: Attachment) = startUpload(attachment.id)

    fun removeAttachment(attachment: Attachment) {
        pendingSend?.cancel()
        attachments.removeAll { it.id == attachment.id }
        forgetUpload(attachment.id)
    }

    /** Stops an upload and deletes what already reached the Hermes host; the file was never sent. */
    private fun forgetUpload(id: Long) {
        uploadJobs.remove(id)?.cancel()
        uploadIds.remove(id)?.let(::deleteFromHost)
    }

    private fun deleteFromHost(uploadId: String) {
        container.appScope.launch {
            // The service holds a cancelled chunk's lock for a few seconds; try once more after that.
            repeat(2) {
                if (runCatching { container.uploads.delete(uploadId) }.isSuccess) return@launch
                delay(6_000)
            }
        }
    }

    private fun clearAttachments() {
        draftGeneration++
        pendingSend?.cancel()
        attachments.forEach { forgetUpload(it.id) }
        attachments.clear()
    }

    fun acceptSharedText(text: String) {
        newChat()
        composerText = text
    }

    /** Something shared to aight from another app: files, and the text that came with them. */
    fun acceptShared(uris: List<Uri>, text: String?, mimeHint: String? = null) {
        newChat()
        if (!text.isNullOrBlank()) composerText = text
        addPicked(uris, mimeHint)
    }

    /** Start dictation, or Live mode when [live] is true. */
    fun requestVoice(live: Boolean = false) {
        _voiceRequests.trySend(live)
    }

    fun toggleSpeak(messageId: String, text: String) {
        if (speaker.speakingId.value == messageId) speaker.stop()
        else speaker.speak(messageId, text, settings.value?.speechLanguage)
    }

    fun loadModels() {
        viewModelScope.launch {
            modelCatalogError.value = null
            try {
                val catalog = api.modelOptions()
                modelCatalog.value = catalog
                // Drop a thinking level or fast mode the model doesn't take, for instance after Hermes'
                // default model changed, so what the app shows is what it sends.
                val current = settings.value ?: return@launch
                ModelProfile.entries.forEach { profile ->
                    val choice = current.modelFor(profile)
                    val supported = catalog.supported(choice)
                    if (supported != choice) container.settings.setModel(profile, supported)
                }
            } catch (e: Exception) {
                modelCatalogError.value = e.userMessage()
            }
        }
    }

    fun setModel(profile: ModelProfile, choice: ModelChoice) {
        viewModelScope.launch { container.settings.setModel(profile, choice) }
    }

    val kokoroModel get() = container.kokoroModel
    val orukeetModel get() = container.orukeetModel
    val supertonicModel get() = container.supertonicModel

    fun deleteSession(id: String) {
        engine.deleteSession(id) { if (_currentId.value == id) newChat() }
    }

    fun renameSession(id: String, title: String) = engine.renameSession(id, title)
    fun setPinned(id: String, pinned: Boolean) = engine.setPinned(id, pinned)
    fun refreshSessions() = engine.refreshSessions()

    val settingsRepository get() = container.settings

    /** Checks that the upload service answers with this API key; null when it does, else what went wrong. */
    suspend fun checkUploads(): String? = try {
        container.uploads.health()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        uploadFailure(e)
    }
}

/** Photo formats Android decodes; these go to the model as pictures instead of being uploaded as files. */
private val PHOTO_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/gif", "image/bmp", "image/avif")

internal fun isModelPhoto(mime: String, shareMimeHint: String?): Boolean =
    mime in PHOTO_TYPES || (mime == "application/octet-stream" && shareMimeHint?.startsWith("image/") == true)

/** What a content URI says about itself. [size] is -1 when the provider doesn't know. */
private class FileInfo(val name: String, val mime: String, val size: Long) {
    companion object {
        fun of(context: Context, uri: Uri): FileInfo {
            var name: String? = null
            var size = -1L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0) name = cursor.getString(nameColumn)
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                    }
                }
            }
            val fileName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val mime = context.contentResolver.getType(uri)?.takeIf { it != "application/octet-stream" }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
            return FileInfo(fileName, mime, size)
        }

        fun countBytes(context: Context, uri: Uri): Long {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the file.")
            return input.use { stream ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (total <= MAX_ATTACHMENT_BYTES) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        }
    }
}

/** Reads a picked file for the upload client, from any offset so an upload can resume. */
private class UriSource(
    private val context: Context,
    private val uri: Uri,
    override val name: String,
    override val mime: String,
    override val size: Long,
) : UploadClient.Source {
    override fun open(offset: Long): InputStream {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open $name.")
        var skipped = 0L
        while (skipped < offset) {
            val n = input.skip(offset - skipped)
            if (n > 0) {
                skipped += n
            } else if (input.read() >= 0) {
                skipped++
            } else {
                input.close()
                throw IOException("$name got shorter while it was uploading.")
            }
        }
        return input
    }
}
