package nl.bartvandermeeren.aight.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.appContainer
import nl.bartvandermeeren.aight.chat.ChatEngine
import nl.bartvandermeeren.aight.chat.Conversation
import nl.bartvandermeeren.aight.chat.userMessage
import nl.bartvandermeeren.aight.data.AppSettings
import nl.bartvandermeeren.aight.data.ModelCatalog
import nl.bartvandermeeren.aight.data.ModelChoice
import nl.bartvandermeeren.aight.data.ModelProfile
import nl.bartvandermeeren.aight.ui.components.Attachment
import nl.bartvandermeeren.aight.ui.components.ImageCodec

enum class Screen { Chat, Settings, Search, Live, Skills, Jobs }

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
                    attachments.clear()
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
        attachments.clear()
        screen = Screen.Chat
    }

    fun openSession(id: String) {
        _currentId.value = id
        engine.load(id)
        screen = Screen.Chat
    }

    fun reloadCurrent() = engine.load(_currentId.value, force = true)

    /** True while picked images are being encoded; the composer counts as busy so nothing overtakes that turn. */
    var preparingSend by mutableStateOf(false)
        private set

    /** Short messages for the UI to show as a toast. */
    private val _notices = Channel<Int>(Channel.BUFFERED)
    val notices = _notices.receiveAsFlow()

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
        preparingSend = true
        viewModelScope.launch {
            try {
                val images = picked.map { attachment ->
                    runCatching {
                        when {
                            attachment.bitmap != null -> ImageCodec.prepare(attachment.bitmap)
                            attachment.uri != null -> ImageCodec.prepare(getApplication(), attachment.uri)
                            else -> null
                        }
                    }.getOrNull()
                }
                if (images.any { it == null }) {
                    // Keep the draft and attachments so the user can fix it instead of losing the photo.
                    _notices.trySend(R.string.image_prepare_failed)
                    return@launch
                }
                if (engine.send(sessionId, text, images.filterNotNull())) {
                    if (_currentId.value == sessionId) {
                        composerText = ""
                        attachments.removeAll(picked)
                    }
                }
            } finally {
                preparingSend = false
            }
        }
    }

    fun stop() = engine.stop(_currentId.value)
    fun retry() = engine.retry(_currentId.value)
    fun resolveApproval(choice: String) = engine.resolveApproval(_currentId.value, choice)

    fun addImage(uri: Uri) {
        attachments += Attachment(++attachmentIds, uri = uri)
    }

    fun addImage(bitmap: Bitmap) {
        attachments += Attachment(++attachmentIds, bitmap = bitmap)
    }

    fun removeAttachment(attachment: Attachment) {
        attachments.remove(attachment)
    }

    fun acceptSharedText(text: String) {
        newChat()
        composerText = text
    }

    fun acceptSharedImage(uri: Uri) {
        newChat()
        addImage(uri)
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
                modelCatalog.value = api.modelOptions()
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
}
