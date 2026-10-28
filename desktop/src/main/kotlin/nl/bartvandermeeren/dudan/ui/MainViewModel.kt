package nl.bartvandermeeren.dudan.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.bartvandermeeren.dudan.chat.ChatEngine
import nl.bartvandermeeren.dudan.chat.Conversation
import nl.bartvandermeeren.dudan.chat.PreparedImage
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.chat.TurnOrigin
import nl.bartvandermeeren.dudan.chat.userMessage
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.ModelCatalog
import nl.bartvandermeeren.dudan.data.ModelChoice
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.data.SessionSummary
import nl.bartvandermeeren.dudan.data.SkillCatalog
import nl.bartvandermeeren.dudan.desktop.ChatGroup
import nl.bartvandermeeren.dudan.desktop.ChatGroups
import nl.bartvandermeeren.dudan.desktop.ChatMarks
import nl.bartvandermeeren.dudan.desktop.DesktopPrefs
import nl.bartvandermeeren.dudan.desktop.DesktopSettings
import nl.bartvandermeeren.dudan.desktop.KeyCombo
import nl.bartvandermeeren.dudan.desktop.ShortcutAction
import nl.bartvandermeeren.dudan.desktop.ShortcutKey
import nl.bartvandermeeren.dudan.desktop.ShortcutProblem
import nl.bartvandermeeren.dudan.desktop.Shortcuts
import nl.bartvandermeeren.dudan.desktop.SnoozeOption
import nl.bartvandermeeren.dudan.desktop.res.Res
import nl.bartvandermeeren.dudan.desktop.res.background_review_prompt
import nl.bartvandermeeren.dudan.desktop.res.desktop_action_download
import nl.bartvandermeeren.dudan.desktop.res.desktop_voice_failed
import nl.bartvandermeeren.dudan.desktop.res.desktop_voice_missing_english
import nl.bartvandermeeren.dudan.desktop.res.desktop_voice_missing_other
import nl.bartvandermeeren.dudan.desktop.res.desktop_voice_unsupported
import nl.bartvandermeeren.dudan.desktop.res.image_prepare_failed
import nl.bartvandermeeren.dudan.desktop.res.images_too_large
import nl.bartvandermeeren.dudan.desktop.res.openui_busy
import nl.bartvandermeeren.dudan.desktop.res.too_many_attachments
import nl.bartvandermeeren.dudan.ui.components.Attachment
import nl.bartvandermeeren.dudan.ui.components.ImageCodec
import nl.bartvandermeeren.dudan.voice.DesktopSpeaker
import nl.bartvandermeeren.dudan.voice.Kokoro
import nl.bartvandermeeren.dudan.voice.ModelStore
import nl.bartvandermeeren.dudan.voice.Supertonic
import okhttp3.OkHttpClient
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

enum class Screen { Chat, Settings }

/** A short message for the window's snackbar: a string resource, its format arguments and an optional button. */
data class Notice(val text: StringResource, val args: List<Any> = emptyList(), val action: NoticeAction? = null)

class NoticeAction(val label: StringResource, val run: () -> Unit)

/**
 * The Windows app's counterpart of the phone's MainViewModel, under the same name so the screens
 * ported from Android read the same. It lives as long as the app, in the tray too, and owns the one
 * chat engine the main window and the quick question share.
 */
class MainViewModel(val store: DesktopSettings, private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Hermes sends an SSE keepalive every 10 s, so a long read timeout only trips on a dead link.
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val api = HermesApi(http) { store.current().server }

    /** A client bound to unsaved credentials, for the connection test in setup. */
    fun apiFor(config: HermesApi.ServerConfig) = HermesApi(http) { config }

    val skillCatalog = SkillCatalog(api, scope)

    val engine = ChatEngine(api, scope, skills = skillCatalog::skills, device = TurnOrigin.Device.Desktop) { store.current() }

    val settings: StateFlow<AppSettings> = store.flow

    val sessions = engine.sessions

    private val _currentId = MutableStateFlow(engine.startNew())
    val currentId: StateFlow<String> = _currentId

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversation: StateFlow<Conversation> = _currentId
        .flatMapLatest { engine.conversation(it) }
        .stateIn(scope, SharingStarted.Eagerly, Conversation(_currentId.value, isNew = true))

    var screen by mutableStateOf(Screen.Chat)
    var composerText by mutableStateOf("")
    val attachments = mutableStateListOf<Attachment>()
    private var attachmentIds = 0L
    /** Bumped whenever the draft is thrown away, so a picture still being encoded doesn't land in the next one. */
    private var draftGeneration = 0L
    private var encoding by mutableIntStateOf(0)

    /** Set to move the keyboard focus to the prompt bar, for instance after New chat. */
    var focusComposer by mutableStateOf(false)

    /** True while pictures are being encoded; the composer counts as busy so a send doesn't leave them behind. */
    val preparingSend: Boolean get() = encoding > 0

    val prefs: StateFlow<DesktopPrefs> = store.prefs

    /** The chat list stays open beside the chat unless the user closes it. */
    var sidebarOpen by mutableStateOf(true)
    /** Set to move the keyboard focus to the chat list's search field. */
    var focusSearch by mutableStateOf(false)
    val searchQuery = MutableStateFlow("")
    /** Groups folded shut in the chat list; Settled starts that way, as in T3 Code. */
    var collapsedGroups by mutableStateOf(setOf(ChatGroup.Settled))

    val marks = ChatMarks(store.dir, scope)

    /** The clock the chat list's times and snoozes go by, a tick a minute. */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    /** Every chat in its group. What a chat is (settled, snoozed, unread) is read here, whatever the search. */
    val allChats: StateFlow<ChatGroups> = combine(engine.sessions, marks.current, engine.activity, prefs.map { it.threadSort }, _now) { sessions, marks, activity, sort, now ->
        ChatGroups.arrange(sessions.items, marks, activity, now, sort)
    }.stateIn(scope, SharingStarted.Eagerly, ChatGroups())

    /** The chats the list shows: all of them, or those the search finds. */
    val chatGroups: StateFlow<ChatGroups> = combine(allChats, searchQuery) { groups, query -> groups.matching(query) }
        .stateIn(scope, SharingStarted.Eagerly, ChatGroups())

    /** Whether the main window has the focus; a chat only counts as read while someone can see it. */
    private val windowFocused = MutableStateFlow(false)
    /** The quick question's chat while its window is up. */
    val quickOnScreen = MutableStateFlow<String?>(null)

    val speaker = DesktopSpeaker(
        kokoro = ModelStore(DesktopSettings.voicesDir(), http, scope, Kokoro.MODEL),
        supertonic = ModelStore(DesktopSettings.voicesDir(), http, scope, Supertonic.MODEL),
        englishVoice = { prefs.value.englishVoice },
        otherVoice = { prefs.value.otherVoice },
    )
    /** A reply waiting for [engine]'s download, read as soon as it's in, unless reading stops or moves on first. */
    private data class PendingSpeech(val id: String, val text: String, val engine: DesktopSpeaker.Engine, val quick: Boolean)

    private var pendingSpeech: PendingSpeech? = null
    /** Whether the reading now playing was asked for in the quick question's window, where its errors show. */
    private var speakingQuick = false
    private var toldMissingVoice = false

    /** A notice for the quick question's window, which has no snackbar of the main window's. */
    val quickNotice = MutableStateFlow<Notice?>(null)

    /** The shortcut Settings is waiting for, and why the last keys pressed for it didn't take. */
    var recording by mutableStateOf<ShortcutAction?>(null)
        private set
    var recordingProblem by mutableStateOf<Pair<ShortcutProblem, KeyCombo>?>(null)
        private set

    val modelCatalog = MutableStateFlow<ModelCatalog?>(null)
    val modelCatalogError = MutableStateFlow<String?>(null)

    private val _notices = Channel<Notice>(Channel.BUFFERED)
    val notices = _notices.receiveAsFlow()

    init {
        scope.launch {
            // Chats, runs and models belong to one server; switching servers starts clean.
            var previousServer: String? = null
            settings.collect { s ->
                if (!s.isConfigured) return@collect
                if (previousServer != null && previousServer != s.serverUrl) {
                    engine.reset()
                    skillCatalog.reset()
                    modelCatalog.value = null
                    _currentId.value = engine.startNew()
                    clearDraft()
                }
                if (previousServer != s.serverUrl || !engine.sessions.value.loadedOnce) {
                    engine.refreshSessions()
                    skillCatalog.ensureLoaded()
                    loadModels()
                }
                if (previousServer != s.serverUrl) marks.use(s.serverUrl)
                previousServer = s.serverUrl
            }
        }
        scope.launch {
            while (true) {
                _now.value = System.currentTimeMillis()
                marks.wake(_now.value)
                delay(60_000 - _now.value % 60_000)
            }
        }
        scope.launch {
            // Chats before the first list stay read; only what comes in after counts as new. The line is drawn at
            // Hermes' latest time, never this PC's clock, so a clock that runs ahead hides nothing.
            engine.sessions.collect { state ->
                if (state.loadedOnce && state.error == null && !state.loading) {
                    state.items.filterNot { it.local }.mapNotNull { it.lastActive }.maxOrNull()?.let(marks::startUnreadFrom)
                }
            }
        }
        scope.launch {
            // A chat on screen is read up to its latest activity, also when that arrives while it's open, and when
            // the chat comes back from Settings.
            combine(engine.sessions, _currentId, windowFocused, quickOnScreen, snapshotFlow { screen }) { sessions, current, focused, quick, shown ->
                buildList {
                    if (focused && shown == Screen.Chat) add(current)
                    quick?.let(::add)
                }.mapNotNull { id -> sessions.items.firstOrNull { it.id == id } }
            }.collect { shown -> shown.forEach(::markSeen) }
        }
        scope.launch {
            // A result moves its chat; the list learns how far only from Hermes, so a settled chat comes back new.
            engine.backgroundResults.collect { engine.refreshSessions() }
        }
        scope.launch {
            engine.completedTurns.filterNotNull().collect { turn ->
                if (prefs.value.readAloud) speak(turn.messageId, turn.text, quiet = true, quick = quickOnScreen.value == turn.sessionId)
            }
        }
        scope.launch {
            speaker.failed.collect { if (it > 0) tell(Notice(Res.string.desktop_voice_failed), speakingQuick) }
        }
        DesktopSpeaker.Engine.entries.forEach { engine ->
            scope.launch {
                // A reply that waited for this voice's download is read once it's in; a stopped or failed download
                // drops it. The other voice's download has no say over it.
                speaker.store(engine).state.collect { state ->
                    val pending = pendingSpeech?.takeIf { it.engine == engine } ?: return@collect
                    when (state) {
                        ModelStore.State.Ready -> {
                            pendingSpeech = null
                            speak(pending.id, pending.text, quick = pending.quick)
                        }
                        is ModelStore.State.Downloading -> Unit
                        else -> pendingSpeech = null
                    }
                }
            }
        }
    }

    /** Starts a chat; [profile] Assistant marks quick questions, so they use the quick model. */
    fun newChat(profile: ModelProfile = ModelProfile.Chats) {
        val current = conversation.value
        val reusable = current.isNew && current.messages.isEmpty() && ChatEngine.profileOf(current.sessionId) == profile
        if (!reusable) _currentId.value = engine.startNew(profile)
        clearDraft()
        screen = Screen.Chat
        focusComposer = true
    }

    fun openSession(id: String) {
        clearDraft()
        _currentId.value = id
        engine.load(id)
        screen = Screen.Chat
        // Opening it reads it, also when the window has no focus yet (from a notification).
        sessions.value.items.firstOrNull { it.id == id }?.let(::markSeen)
    }

    /** Read up to the chat's latest activity; a chat only this app knows yet has no Hermes time to go by. */
    private fun markSeen(session: SessionSummary) = marks.seen(session.id, session.lastActive.takeUnless { session.local })

    private fun clearDraft() {
        composerText = ""
        attachments.clear()
        draftGeneration++
    }

    fun reloadCurrent() = engine.load(_currentId.value, force = true)

    /** The window is back in front: show what arrived while it was away, such as background results. */
    fun onWindowFocused() {
        windowFocused.value = true
        engine.load(_currentId.value)
        if (engine.sessions.value.loadedOnce) engine.refreshSessions()
    }

    fun onWindowBlurred() {
        windowFocused.value = false
    }

    fun reviewBackground() {
        stopReading()
        val sessionId = _currentId.value
        scope.launch { engine.reviewBackground(sessionId, getString(Res.string.background_review_prompt)) }
    }

    fun send() {
        val text = composerText.trim()
        val picked = attachments.toList()
        if (text.isEmpty() && picked.isEmpty()) return
        if (conversation.value.isBusy || preparingSend) return
        val images = picked.mapNotNull { it.image }
        stopReading()
        if (engine.send(_currentId.value, text, images)) {
            composerText = ""
            attachments.removeAll { it in picked }
        }
    }

    /** Sends what an OpenUI button or follow-up in a reply asks for. The draft in the composer stays. */
    fun sendFromReply(text: String): Boolean {
        if (preparingSend) return false
        return engine.send(_currentId.value, text)
    }

    /** A button in a reply was pressed while the reply is still coming in. */
    fun replyBusy() {
        _notices.trySend(Notice(Res.string.openui_busy))
    }

    fun stop() = engine.stop(_currentId.value)
    fun retry() = engine.retry(_currentId.value)
    fun resolveApproval(choice: String) = engine.resolveApproval(_currentId.value, choice)

    /** Adds picked or dropped image files; files that aren't images Skia can read are refused with a notice. */
    fun addImageFiles(files: List<File>) {
        val room = MAX_DRAFT_ATTACHMENTS - attachments.size
        if (room <= 0 || files.size > room) _notices.trySend(Notice(Res.string.too_many_attachments, listOf(MAX_DRAFT_ATTACHMENTS)))
        files.take(room.coerceAtLeast(0)).forEach { file -> attachLater { ImageCodec.prepare(file) } }
    }

    /**
     * Adds a picture that takes a moment to encode: a file, one from the clipboard, a screenshot. It goes into the
     * draft that was open when the user asked for it, and is dropped when that draft is gone by the time it's ready.
     */
    fun attachLater(encode: suspend () -> PreparedImage?) {
        val draft = draftGeneration
        encoding++
        scope.launch {
            try {
                val image = try {
                    encode()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (draft != draftGeneration) return@launch
                if (image == null) _notices.trySend(Notice(Res.string.image_prepare_failed)) else addPrepared(image)
            } finally {
                encoding--
            }
        }
    }

    private fun addPrepared(image: PreparedImage) {
        if (attachments.size >= MAX_DRAFT_ATTACHMENTS) {
            _notices.trySend(Notice(Res.string.too_many_attachments, listOf(MAX_DRAFT_ATTACHMENTS)))
            return
        }
        if (attachments.sumOf { it.image?.bytes?.size?.toLong() ?: 0L } + image.bytes.size > MAX_IMAGE_BYTES_PER_TURN) {
            _notices.trySend(Notice(Res.string.images_too_large))
            return
        }
        attachments += Attachment(++attachmentIds, image = image)
    }

    fun removeAttachment(attachment: Attachment) {
        attachments.remove(attachment)
    }

    fun loadModels() {
        scope.launch {
            modelCatalogError.value = null
            try {
                val catalog = api.modelOptions()
                modelCatalog.value = catalog
                // Drop a thinking level or fast mode the model doesn't take, so what the app shows is what it sends.
                val current = settings.value
                ModelProfile.entries.forEach { profile ->
                    val choice = current.modelFor(profile)
                    val supported = catalog.supported(choice)
                    if (supported != choice) store.setModel(profile, supported)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                modelCatalogError.value = e.userMessage()
            }
        }
    }

    fun setModel(profile: ModelProfile, choice: ModelChoice) {
        scope.launch { store.setModel(profile, choice) }
    }

    fun deleteSession(id: String) {
        engine.deleteSession(id) {
            marks.forget(id)
            if (_currentId.value == id) newChat()
        }
    }

    // ---- Chat list ------------------------------------------------------------------------------

    private fun summary(id: String): SessionSummary? = sessions.value.items.firstOrNull { it.id == id }

    fun settle(id: String) = marks.settle(id, summary(id)?.lastActive)
    fun unsettle(id: String) = marks.unsettle(id)
    fun snooze(id: String, option: SnoozeOption) = marks.snooze(id, option.until(System.currentTimeMillis()))
    fun unsnooze(id: String) = marks.unsnooze(id)
    fun markUnread(id: String) = marks.markUnread(id)
    fun markRead(id: String) {
        summary(id)?.let(::markSeen)
    }

    fun toggleGroup(group: ChatGroup) {
        collapsedGroups = if (group in collapsedGroups) collapsedGroups - group else collapsedGroups + group
    }

    /** Settles the open chat, or brings it back when it's settled already. */
    fun toggleSettledCurrent() {
        val id = _currentId.value
        when {
            allChats.value.row(id)?.group == ChatGroup.Settled -> unsettle(id)
            sessions.value.items.any { it.id == id } -> settle(id)
        }
    }

    /** Opens the chat [step] rows up or down the list as it shows; from a new chat, the first or the last one. */
    fun openNearby(step: Int) {
        val rows = chatGroups.value.visible(collapsedGroups)
        if (rows.isEmpty()) return
        val index = rows.indexOfFirst { it.session.id == _currentId.value }
        val next = if (index < 0) (if (step > 0) 0 else rows.lastIndex) else (index + step).coerceIn(0, rows.lastIndex)
        if (next != index) openSession(rows[next].session.id)
    }

    // ---- Reading aloud --------------------------------------------------------------------------

    /** Reads a reply aloud, or stops when it's the one being read. [quick] is a reply in the quick question's window. */
    fun toggleSpeak(messageId: String, text: String, quick: Boolean = false) {
        if (speaker.speakingId.value == messageId) stopReading() else speak(messageId, text, quick = quick)
    }

    /** Stops reading, or reads [sessionId]'s latest reply aloud. */
    fun toggleSpeakLatest(sessionId: String = _currentId.value, quick: Boolean = false) {
        if (speaker.speakingId.value != null || pendingSpeech != null) {
            stopReading()
            return
        }
        val reply = engine.conversation(sessionId).value.messages.lastOrNull { it.role == Role.Assistant && !it.isStreaming && it.text.isNotBlank() } ?: return
        speak(reply.id, reply.text, quick = quick)
    }

    /** Stops what's being read, and a reply still waiting for its voice's download. */
    fun stopReading() {
        pendingSpeech = null
        speaker.stop()
    }

    /** True when something was being read or waiting to be; Esc stops it once nothing else is open. */
    fun stopSpeaking(): Boolean = (speaker.speakingId.value != null || pendingSpeech != null).also { stopReading() }

    /** Settings' sample of a voice, which takes over from any reply being read. */
    fun previewVoice(engine: DesktopSpeaker.Engine, voice: String) {
        pendingSpeech = null
        speaker.preview(engine, voice, settings.value.userName)
    }

    /**
     * [quiet]: a new reply read by itself; then a missing voice is mentioned once per run, not at every reply.
     * [quick]: asked from the quick question's window, which shows the notice itself.
     */
    private fun speak(id: String, text: String, quiet: Boolean = false, quick: Boolean = false) {
        when (val result = speaker.speak(id, text)) {
            // A newer reading replaces the one waiting for a download.
            DesktopSpeaker.Result.Started -> {
                pendingSpeech = null
                speakingQuick = quick
            }
            DesktopSpeaker.Result.Empty -> Unit
            is DesktopSpeaker.Result.Missing -> {
                val model = speaker.store(result.engine)
                speaker.stop()
                val waiting = PendingSpeech(id, text, result.engine, quick)
                if (model.state.value is ModelStore.State.Downloading) {
                    pendingSpeech = waiting
                    return
                }
                pendingSpeech = null
                if (quiet && toldMissingVoice) return
                toldMissingVoice = true
                val message = if (result.engine == DesktopSpeaker.Engine.Kokoro) Res.string.desktop_voice_missing_english else Res.string.desktop_voice_missing_other
                tell(
                    Notice(message, listOf(sizeInMb(model.spec.sizeBytes)), NoticeAction(Res.string.desktop_action_download) {
                        pendingSpeech = waiting
                        model.download()
                    }),
                    quick,
                )
            }
            is DesktopSpeaker.Result.Unsupported -> if (!quiet) tell(Notice(Res.string.desktop_voice_unsupported), quick)
        }
    }

    private fun tell(notice: Notice, quick: Boolean) {
        if (quick) quickNotice.value = notice else _notices.trySend(notice)
    }

    // ---- Text size and shortcuts ----------------------------------------------------------------

    fun stepTextSize(step: Int) {
        val steps = DesktopSettings.TEXT_SCALES
        val index = steps.indexOfFirst { it >= prefs.value.textScale }.coerceAtLeast(0)
        update { setTextScale(steps[(index + step).coerceIn(0, steps.lastIndex)]) }
    }

    fun resetTextSize() = update { setTextScale(1f) }

    /** Runs a window shortcut; the quick question's is Windows' to deliver, see GlobalShortcut. */
    fun perform(action: ShortcutAction) {
        when (action) {
            ShortcutAction.QuickAsk -> Unit
            ShortcutAction.NewChat -> newChat()
            ShortcutAction.SearchChats -> {
                screen = Screen.Chat
                sidebarOpen = true
                focusSearch = true
            }
            ShortcutAction.ToggleSidebar -> sidebarOpen = !sidebarOpen
            ShortcutAction.PreviousChat -> openNearby(-1)
            ShortcutAction.NextChat -> openNearby(1)
            ShortcutAction.SettleChat -> toggleSettledCurrent()
            ShortcutAction.ReadAloud -> toggleSpeakLatest()
            ShortcutAction.TextBigger -> stepTextSize(1)
            ShortcutAction.TextSmaller -> stepTextSize(-1)
            ShortcutAction.TextReset -> resetTextSize()
            ShortcutAction.OpenSettings -> screen = if (screen == Screen.Settings) Screen.Chat else Screen.Settings
        }
    }

    /** The window shortcut these keys make, if any. The quick question's is Windows' to deliver. */
    fun shortcutFor(awtCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): ShortcutAction? =
        prefs.value.shortcuts.entries.firstOrNull { (action, combo) -> !action.global && combo?.matches(awtCode, ctrl, alt, shift) == true }?.key

    fun startRecording(action: ShortcutAction) {
        recording = action
        recordingProblem = null
    }

    fun stopRecording() {
        recording = null
        recordingProblem = null
    }

    /**
     * A key pressed while Settings waits for [recording]'s keys. Esc cancels, Backspace or Delete on its own
     * leaves the action without a shortcut, and a modifier alone waits for the rest.
     */
    fun record(awtCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean) {
        val action = recording ?: return
        if (awtCode == java.awt.event.KeyEvent.VK_ESCAPE) return stopRecording()
        val key = ShortcutKey.fromAwt(awtCode) ?: return
        if ((key == ShortcutKey.Backspace || key == ShortcutKey.Delete) && !ctrl && !alt && !shift) {
            update { setShortcut(action, null) }
            return stopRecording()
        }
        val combo = KeyCombo(key, ctrl, alt, shift)
        val problem = Shortcuts.problem(action, combo, prefs.value.shortcuts)
        if (problem != null) {
            recordingProblem = problem to combo
            return
        }
        update { setShortcut(action, combo) }
        stopRecording()
    }

    fun renameSession(id: String, title: String) = engine.renameSession(id, title)
    fun setPinned(id: String, pinned: Boolean) = engine.setPinned(id, pinned)
    fun refreshSessions() = engine.refreshSessions()

    fun update(change: suspend DesktopSettings.() -> Unit) {
        scope.launch { store.change() }
    }

    private companion object {
        fun sizeInMb(bytes: Long): Long = (bytes + 500_000) / 1_000_000

        const val MAX_DRAFT_ATTACHMENTS = 8
        const val MAX_IMAGE_BYTES_PER_TURN = 5L * 1024 * 1024
    }
}
