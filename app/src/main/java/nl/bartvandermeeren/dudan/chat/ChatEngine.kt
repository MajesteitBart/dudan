package nl.bartvandermeeren.dudan.chat

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.bartvandermeeren.dudan.data.AgentEvent
import nl.bartvandermeeren.dudan.data.ApprovalRequest
import nl.bartvandermeeren.dudan.data.AttachmentNotes
import nl.bartvandermeeren.dudan.data.FileRef
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.data.RunOutcome
import nl.bartvandermeeren.dudan.data.SessionSummary
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.openui.OpenUiPrompt

/** An image ready to send: JPEG bytes plus the data: URL Hermes expects. */
class PreparedImage(val bytes: ByteArray, val dataUrl: String)

/**
 * Owns every conversation and running turn for the whole process, so the app, the assistant
 * overlay and Live mode all see the same state and a run survives navigating away.
 * All mutation happens on the main dispatcher of [scope].
 */
class ChatEngine(
    private val api: HermesApi,
    private val scope: CoroutineScope,
    private val currentSettings: suspend () -> AppSettings,
) {
    /**
     * One agent turn. [text] is what the user typed; [input] is what Hermes gets, with a note per
     * attached file. Cleanup only touches shared state this turn still owns.
     */
    private class Turn(val sessionId: String, val messageId: String, val text: String, val images: List<PreparedImage>, files: List<FileRef>) {
        val input: String = AttachmentNotes.compose(text, files)
        var job: Job? = null
        var runId: String? = null
        var stopRequested = false
        /** Runs keep going on the server when the client disconnects; image turns use the session stream, which doesn't. */
        val usesRuns: Boolean get() = images.isEmpty()
    }

    private val conversations = mutableMapOf<String, MutableStateFlow<Conversation>>()
    private val turns = mutableMapOf<String, Turn>()
    private val idCounter = AtomicLong()

    private val _sessions = MutableStateFlow(SessionsState())
    val sessions: StateFlow<SessionsState> = _sessions.asStateFlow()

    /** Emits every successfully finished assistant turn; read-aloud and notifications key off it. */
    private val _completedTurns = MutableStateFlow<CompletedTurn?>(null)
    val completedTurns: StateFlow<CompletedTurn?> = _completedTurns.asStateFlow()

    /** [tools] names the tools the turn called, as Hermes reports them (MCP tools as mcp__server__tool). */
    data class CompletedTurn(
        val sessionId: String,
        val messageId: String,
        val text: String,
        val seq: Long,
        val tools: List<String> = emptyList(),
    )

    fun conversation(sessionId: String): StateFlow<Conversation> = flowFor(sessionId).asStateFlow()

    /** Starts a local chat. Assistant chats carry their origin in the id, so it survives app restarts. */
    fun startNew(profile: ModelProfile = ModelProfile.Chats): String {
        val prefix = if (profile == ModelProfile.Assistant) ASSISTANT_PREFIX else CHAT_PREFIX
        val id = prefix + UUID.randomUUID().toString().replace("-", "").take(20)
        flowFor(id).value = Conversation(id, isNew = true, loaded = true)
        return id
    }

    /** Forgets everything tied to the previous server; call when the connection changes. */
    fun reset() {
        turns.values.forEach { it.job?.cancel() }
        turns.clear()
        conversations.clear()
        _sessions.value = SessionsState()
    }

    fun load(sessionId: String, force: Boolean = false) {
        val flow = flowFor(sessionId)
        val current = flow.value
        if (current.isNew || current.isBusy || current.loading) return
        if (current.loaded && !force) return
        flow.update { it.copy(loading = true, loadError = null) }
        scope.launch {
            try {
                val messages = HistoryMapper.map(api.sessionMessages(sessionId))
                flow.update { if (it.isBusy) it.copy(loading = false) else it.copy(messages = messages, loading = false, loaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow.update { it.copy(loading = false, loadError = e.userMessage()) }
            }
        }
    }

    /** Starts a turn. Returns false when the conversation is still busy with the previous one. */
    fun send(sessionId: String, text: String, images: List<PreparedImage> = emptyList(), files: List<FileRef> = emptyList()): Boolean {
        val flow = flowFor(sessionId)
        if (flow.value.isBusy || (text.isBlank() && images.isEmpty() && files.isEmpty())) return false
        val user = UiMessage(id = nextLocalId(), role = Role.User, text = text, images = images.map { ImageRef(it.dataUrl) }, files = files)
        val assistant = UiMessage(
            id = nextLocalId(), role = Role.Assistant, state = MessageState.Streaming, startedAtMs = System.currentTimeMillis(),
        )
        val wasNew = flow.value.isNew
        flow.update { it.copy(messages = it.messages + user + assistant, loaded = true, loadError = null) }
        if (wasNew) addOptimisticSession(sessionId, text.ifBlank { files.joinToString(", ") { it.name } })
        val turn = Turn(sessionId, assistant.id, text, images, files)
        turns[sessionId] = turn
        turn.job = scope.launch { runTurn(turn) }
        return true
    }

    /** Re-sends the last user message after a failed or stopped turn. */
    fun retry(sessionId: String) {
        val flow = flowFor(sessionId)
        val messages = flow.value.messages
        if (flow.value.isBusy) return
        val lastUserIndex = messages.indexOfLast { it.role == Role.User }
        if (lastUserIndex < 0) return
        val lastUser = messages[lastUserIndex]
        flow.update { it.copy(messages = messages.subList(0, lastUserIndex)) }
        val images = lastUser.images.mapNotNull { ref ->
            ref.source.takeIf { it.startsWith("data:") }?.let { PreparedImage(ByteArray(0), it) }
        }
        send(sessionId, lastUser.text, images, lastUser.files)
    }

    /**
     * Stops the running turn. A Runs turn is only settled once Hermes confirms it, so a failed stop
     * request never leaves the agent working while the app shows it as stopped.
     */
    fun stop(sessionId: String) {
        val turn = turns[sessionId] ?: return
        if (turn.stopRequested) return
        turn.stopRequested = true
        updateMessage(sessionId, turn.messageId) { it.copy(stopping = true) }
        if (!turn.usesRuns) {
            // Hermes interrupts a session-stream turn when its client disconnects.
            turn.job?.cancel()
            return
        }
        // Without a run id yet, runTurn sends the stop as soon as Hermes returns one.
        if (turn.runId != null) sendStop(turn)
    }

    private fun sendStop(turn: Turn) {
        val runId = turn.runId ?: return
        scope.launch {
            repeat(3) { attempt ->
                try {
                    api.stopRun(runId)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HermesApi.HermesException) {
                    // 404/409: the run already finished; its terminal event or status settles the turn.
                    if (e.status in 400..499 && e.status != 429) return@launch
                } catch (_: Exception) {
                    // Network trouble: try again.
                }
                delay(1_500L * (attempt + 1))
            }
            updateMessage(turn.sessionId, turn.messageId) { it.copy(stopping = false, error = "Couldn't reach Hermes to stop this run.") }
        }
    }

    fun resolveApproval(sessionId: String, choice: String) {
        val message = conversations[sessionId]?.value?.messages?.lastOrNull { it.role == Role.Assistant } ?: return
        val request = message.approval ?: return
        if (request.pendingChoice != null) return
        updateMessage(sessionId, message.id) { it.copy(approval = request.copy(pendingChoice = choice)) }
        scope.launch {
            try {
                val runId = request.runId.ifBlank { turns[sessionId]?.runId.orEmpty() }
                api.resolveApproval(runId, choice, request.requestId)
                // A newer approval may already have arrived over the stream; only clear this one.
                updateMessage(sessionId, message.id) { m -> if (m.approval?.sameRequest(request) == true) m.copy(approval = null) else m }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateMessage(sessionId, message.id) { m ->
                    if (m.approval?.sameRequest(request) == true) m.copy(approval = request, error = e.userMessage()) else m
                }
            }
        }
    }

    // ---- Session list ---------------------------------------------------------------------------

    fun refreshSessions() {
        scope.launch {
            _sessions.update { it.copy(loading = true) }
            try {
                val s = currentSettings()
                if (!s.isConfigured) {
                    _sessions.value = SessionsState(loadedOnce = true)
                    return@launch
                }
                val items = api.listSessions(source = if (s.showAllChannels) null else "api_server")
                val sorted = items.sortedWith(
                    compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.lastActive ?: 0.0 },
                )
                _sessions.value = SessionsState(sorted, loading = false, error = null, loadedOnce = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sessions.update { it.copy(loading = false, error = e.userMessage(), loadedOnce = true) }
            }
        }
    }

    fun deleteSession(sessionId: String, onDone: () -> Unit = {}) {
        scope.launch {
            try {
                // Don't leave the agent working in a chat that no longer exists.
                turns[sessionId]?.let { turn ->
                    stop(sessionId)
                    turn.job?.cancel()
                }
                api.deleteSession(sessionId)
                turns.remove(sessionId)
                conversations.remove(sessionId)
                _sessions.update { state -> state.copy(items = state.items.filterNot { it.id == sessionId }) }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sessions.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    fun renameSession(sessionId: String, title: String) {
        _sessions.update { state -> state.copy(items = state.items.map { if (it.id == sessionId) it.copy(title = title) else it }) }
        scope.launch {
            runCatching { api.updateSession(sessionId, title = title) }.onFailure { e ->
                if (e is CancellationException) throw e
                _sessions.update { it.copy(error = e.userMessage()) }
                refreshSessions()
            }
        }
    }

    fun setPinned(sessionId: String, pinned: Boolean) {
        _sessions.update { state ->
            state.copy(
                items = state.items.map { if (it.id == sessionId) it.copy(pinned = pinned) else it }
                    .sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.lastActive ?: 0.0 }),
            )
        }
        scope.launch {
            runCatching { api.updateSession(sessionId, pinned = pinned) }.onFailure { e ->
                if (e is CancellationException) throw e
                refreshSessions()
            }
        }
    }

    // ---- Turn execution -------------------------------------------------------------------------

    private suspend fun runTurn(turn: Turn) {
        val sessionId = turn.sessionId
        try {
            try {
                if (conversations[sessionId]?.value?.isNew == true) {
                    api.createSession(sessionId)
                    conversations[sessionId]?.update { it.copy(isNew = false) }
                }
                val settings = currentSettings()
                val model = settings.modelFor(profileOf(sessionId))
                val instructions = OpenUiPrompt.instructions.takeIf { settings.richReplies }
                val events: Flow<AgentEvent> = if (turn.usesRuns) {
                    val runId = api.startRun(sessionId, turn.input, model, instructions)
                    turn.runId = runId
                    if (turn.stopRequested) sendStop(turn)
                    api.runEvents(runId)
                } else {
                    api.sessionChatStream(sessionId, multimodalMessage(turn.input, turn.images), model, instructions)
                }
                var sawTerminal = false
                events.collect { event ->
                    if (event is AgentEvent.RunStarted && event.runId != null) turn.runId = event.runId
                    if (event is AgentEvent.Finished || event is AgentEvent.Failed) sawTerminal = true
                    updateMessage(sessionId, turn.messageId) { TurnReducer.apply(it, event, System.currentTimeMillis()) }
                }
                if (!sawTerminal) recoverRun(turn, IOException("The connection to Hermes closed early."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recoverRun(turn, e)
            }
        } finally {
            // Anything still streaming here ended abnormally; never report it as a success.
            val cancelled = !currentCoroutineContext().isActive
            updateMessage(sessionId, turn.messageId) { message ->
                when {
                    !message.isStreaming -> message
                    cancelled -> TurnReducer.finish(message, RunOutcome.Cancelled, null, null, System.currentTimeMillis())
                    else -> TurnReducer.finish(message, RunOutcome.Failed, null, "The run ended without a result.", System.currentTimeMillis())
                }
            }
            if (turns[sessionId] === turn) turns.remove(sessionId)
            conversations[sessionId]?.value?.messages?.firstOrNull { it.id == turn.messageId }?.let { message ->
                if (message.state == MessageState.Done && message.text.isNotBlank()) {
                    val tools = message.steps.filter { it.kind == StepKind.Tool }.map { it.title }
                    _completedTurns.value = CompletedTurn(sessionId, turn.messageId, message.text, idCounter.incrementAndGet(), tools)
                }
            }
            refreshSessions()
        }
    }

    /** The stream broke. A Runs turn keeps going on the server, so poll it until it ends. */
    private suspend fun recoverRun(turn: Turn, cause: Exception) {
        val runId = turn.runId
        val authFailure = cause is HermesApi.HermesException && cause.status in setOf(401, 403)
        if (!turn.usesRuns || runId == null || authFailure) {
            fail(turn, cause.userMessage())
            return
        }
        updateMessage(turn.sessionId, turn.messageId) { it.copy(reconnecting = true) }
        var failures = 0
        val deadline = System.currentTimeMillis() + 60 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(if (failures == 0) 1_500 else 4_000)
            val status = try {
                api.runStatus(runId).also { failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                if (e is HermesApi.HermesException && e.status == 404) {
                    // The status record expired; the transcript is the source of truth now.
                    reloadAfterRecovery(turn)
                    return
                }
                continue
            }
            if (status.approval != null) updateMessage(turn.sessionId, turn.messageId) { it.copy(approval = status.approval) }
            if (status.isTerminal) {
                updateMessage(turn.sessionId, turn.messageId) {
                    TurnReducer.finish(it, status.outcome, status.output, status.error, System.currentTimeMillis())
                }
                return
            }
        }
        fail(turn, cause.userMessage())
    }

    /** Takes this turn's answer from the transcript: the first reply after this turn's own question. */
    private suspend fun reloadAfterRecovery(turn: Turn) {
        val history = runCatching { HistoryMapper.map(api.sessionMessages(turn.sessionId)) }.getOrNull()
        val question = history?.indexOfLast { it.role == Role.User && it.text.trim() == turn.text.trim() } ?: -1
        val answer = if (question >= 0) {
            history!!.drop(question + 1).firstOrNull { it.role == Role.Assistant && it.text.isNotBlank() }
        } else null
        if (answer != null) {
            updateMessage(turn.sessionId, turn.messageId) {
                TurnReducer.finish(it.copy(steps = answer.steps.ifEmpty { it.steps }), RunOutcome.Completed, answer.text, null, System.currentTimeMillis())
            }
        } else {
            fail(turn, "Lost track of this run. Reopen the chat to see whether Hermes finished it.")
        }
    }

    private fun fail(turn: Turn, message: String) {
        updateMessage(turn.sessionId, turn.messageId) { TurnReducer.apply(it, AgentEvent.Failed(message), System.currentTimeMillis()) }
    }

    private fun multimodalMessage(text: String, images: List<PreparedImage>): JsonElement {
        if (images.isEmpty()) return JsonPrimitive(text)
        return buildJsonArray {
            if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
            images.forEach { image ->
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", image.dataUrl) })
                })
            }
        }
    }

    private fun addOptimisticSession(sessionId: String, text: String) {
        val now = System.currentTimeMillis() / 1000.0
        _sessions.update { state ->
            if (state.items.any { it.id == sessionId }) state
            else state.copy(
                items = listOf(SessionSummary(sessionId, null, text.ifBlank { "…" }, "api_server", now, now, false, 1)) + state.items,
            )
        }
    }

    /** Updates a message without recreating a conversation that was deleted or reset meanwhile. */
    private fun updateMessage(sessionId: String, messageId: String, transform: (UiMessage) -> UiMessage) {
        conversations[sessionId]?.update { conversation ->
            conversation.copy(messages = conversation.messages.map { if (it.id == messageId) transform(it) else it })
        }
    }

    private fun flowFor(sessionId: String) = conversations.getOrPut(sessionId) { MutableStateFlow(Conversation(sessionId)) }

    private fun nextLocalId() = "l_${idCounter.incrementAndGet()}"

    companion object {
        private const val CHAT_PREFIX = "dudan_"
        private const val ASSISTANT_PREFIX = "dudan_assist_"

        /** Which default model a chat uses. Chats from other Hermes clients count as regular chats. */
        fun profileOf(sessionId: String): ModelProfile =
            // Existing Hermes sessions keep their IDs after the rename.
            if (sessionId.startsWith(ASSISTANT_PREFIX) || sessionId.startsWith("aight_assist_"))
                ModelProfile.Assistant else ModelProfile.Chats
    }
}

/** Two approval requests are the same when Hermes' request id matches, or, without ids, the run and command do. */
fun ApprovalRequest.sameRequest(other: ApprovalRequest): Boolean =
    if (requestId != null || other.requestId != null) requestId == other.requestId
    else runId == other.runId && command == other.command

fun Throwable.userMessage(): String = when (this) {
    is HermesApi.HermesException -> when (status) {
        401, 403 -> "Hermes rejected the API key ($status)."
        404 -> message.ifBlank { "Not found on the Hermes server." }
        429 -> "Hermes is busy with too many runs. Try again in a moment."
        0 -> message
        else -> "Hermes error $status: $message"
    }
    is java.net.UnknownHostException -> "Can't find the Hermes server. Is Tailscale connected?"
    is java.net.ConnectException -> "Can't reach the Hermes server. Is the API server running?"
    is java.net.SocketTimeoutException -> "The Hermes server stopped responding."
    else -> message ?: toString()
}
