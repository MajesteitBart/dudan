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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import nl.bartvandermeeren.dudan.data.HermesMessage
import nl.bartvandermeeren.dudan.data.ModelChoice
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.data.RunOutcome
import nl.bartvandermeeren.dudan.data.SessionSummary
import nl.bartvandermeeren.dudan.data.AppSettings

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
    /** Pauses between transcript checks while background work is out; the last one repeats. */
    private val watchPauses: List<Long> = listOf(3_000, 5_000, 10_000, 15_000, 20_000, 30_000),
    private val currentSettings: suspend () -> AppSettings,
) {
    /**
     * One agent turn. [text] is what the user typed; [input] is what Hermes gets, with a note per
     * attached file. Cleanup only touches shared state this turn still owns.
     */
    private class Turn(
        val sessionId: String,
        val userMessageId: String,
        val messageId: String,
        val text: String,
        val images: List<PreparedImage>,
        files: List<FileRef>,
        val origin: TurnOrigin,
        /** Set for "Review result and finish"; see [reviewBackground]. */
        var review: ReviewRequest? = null,
    ) {
        val input: String = AttachmentNotes.compose(text, files)
        var job: Job? = null
        var runId: String? = null
        var stopRequested = false
        /** Runs keep going on the server when the client disconnects; image turns use the session stream, which doesn't. */
        val usesRuns: Boolean get() = images.isEmpty()
    }

    /**
     * Background work the agent started in a chat that hasn't reported back. Hermes has no push for
     * this, so the transcript's tail is polled while the app process lives, for at most [WATCH_MS]
     * after the last sign of the work. Results that land while the process is gone show up the next
     * time the chat opens or the app returns.
     */
    private class Watch(val sessionId: String) {
        val pending = mutableSetOf<String>()
        /** Results that came in (by [BackgroundResult.key]) but the chat doesn't show yet. */
        val unshown = mutableSetOf<String>()
        var until = 0L
        var job: Job? = null
    }

    private val conversations = mutableMapOf<String, MutableStateFlow<Conversation>>()
    private val turns = mutableMapOf<String, Turn>()
    private val watches = mutableMapOf<String, Watch>()
    private val syncing = mutableSetOf<String>()
    /** Background results already announced, by server and delivery, so each is reported once. */
    private val announced = mutableSetOf<String>()
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

    /**
     * A background result that came in for work this process was watching. Results already in a chat
     * when it loads are never announced. [key] identifies the result on its server.
     */
    data class BackgroundArrival(val sessionId: String, val messageId: String, val result: BackgroundResult, val key: String)

    private val _backgroundResults = MutableSharedFlow<BackgroundArrival>(extraBufferCapacity = 32)
    val backgroundResults: SharedFlow<BackgroundArrival> = _backgroundResults.asSharedFlow()

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
        watches.values.forEach { it.job?.cancel() }
        watches.clear()
        announced.clear()
        conversations.clear()
        _sessions.value = SessionsState()
    }

    /**
     * Shows a chat's history. A chat that is already loaded is brought up to date instead, without a
     * spinner, so reopening it shows what arrived meanwhile. A running turn is never overwritten.
     */
    fun load(sessionId: String, force: Boolean = false) {
        val flow = flowFor(sessionId)
        val current = flow.value
        if (current.isNew || current.isBusy || current.loading) return
        if (current.loaded && !force) {
            sync(sessionId)
            return
        }
        flow.update { it.copy(loading = true, loadError = null) }
        scope.launch {
            try {
                val rows = api.sessionMessages(sessionId).messages
                val messages = HistoryMapper.map(rows)
                flow.update { if (it.isBusy) it.copy(loading = false) else it.copy(messages = messages, loading = false, loaded = true) }
                track(sessionId, earlierRows(sessionId, rows) + rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow.update { it.copy(loading = false, loadError = e.userMessage()) }
            }
        }
    }

    /**
     * Brings a loaded chat up to date with Hermes, for instance when the app comes back to the front.
     * What the chat shows only gives way once the transcript holds it; see [HistoryMapper.reconcile].
     */
    fun sync(sessionId: String) {
        val current = conversations[sessionId]?.value ?: return
        if (current.isNew || !current.loaded || current.loading || current.isBusy || !syncing.add(sessionId)) return
        scope.launch {
            try {
                show(sessionId, api.sessionMessages(sessionId).messages)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The chat keeps what it shows; the next reopen or resume tries again.
            } finally {
                syncing.remove(sessionId)
            }
        }
    }

    /** What [show] did: skipped because a turn is running, kept the chat's own turn, or took the transcript. */
    private enum class Shown { Busy, Merged, Replaced }

    /**
     * Merges [rows] into the chat unless a turn is running. [afterTurn] marks the refresh right after
     * a turn of this app; see [track].
     */
    private suspend fun show(sessionId: String, rows: List<HermesMessage>, afterTurn: Boolean = false): Shown {
        val transcript = HistoryMapper.map(rows)
        var shown = Shown.Merged
        conversations[sessionId]?.update { c ->
            if (c.isBusy) shown = Shown.Busy
            if (c.isBusy || !c.loaded) return@update c
            val merged = HistoryMapper.reconcile(c.messages, transcript)
            shown = if (merged.caughtUp) Shown.Replaced else Shown.Merged
            c.copy(messages = merged.messages)
        }
        track(sessionId, rows, afterTurn)
        return shown
    }

    /**
     * Continues after background results came in, when the user asks for it. Hermes saves such results
     * without starting a turn, because it can't know whether the user still wants the work to go on:
     * they may have stopped it, said something new, or still owe the agent a confirmation. Another
     * client may also have continued already, so this checks the transcript first and only starts the
     * normal turn, with its approvals, when the results are still the last thing in the chat.
     *
     * The run carries an idempotency key named after the result, so a second dudan on the same Hermes
     * profile, or a request the network repeats, joins the same run instead of starting another. A
     * retry passes the [previous] request and asks again exactly the same way: when Hermes had admitted
     * that run after all, the retry joins it, and only when Hermes reports it ended without finishing
     * does the retry get a fresh key (see [admitReview]). Other clients (Telegram, the CLI) can still continue between the
     * check and the run; only Hermes could close that gap.
     */
    fun reviewBackground(sessionId: String, prompt: String, previous: ReviewRequest? = null) {
        val flow = conversations[sessionId] ?: return
        val current = flow.value
        if (!current.awaitingReview || current.isBusy || current.reviewing) return
        val result = current.messages.last().background?.key ?: return
        val request = previous ?: ReviewRequest(reviewKey(current.messages.takeLastWhile { it.role == Role.Background }.last { it.background?.interim == false }))
        flow.update { it.copy(reviewing = true, reviewError = null) }
        scope.launch {
            try {
                val rows = api.sessionMessages(sessionId).messages
                val transcript = HistoryMapper.map(rows)
                if (transcript.takeLastWhile { it.role == Role.Background }.none { it.background?.key == result }) {
                    // Someone continued already or the chat moved on; show that instead. The chat ended
                    // with results from Hermes, so nothing unsent is lost.
                    flow.update { if (it.isBusy) it else it.copy(messages = transcript) }
                    track(sessionId, rows)
                    return@launch
                }
                show(sessionId, rows)
                // Asked the same way from every surface, so another dudan reviewing this result sends the
                // same request and joins the run instead of hitting the key's conflict check.
                if (flow.value.awaitingReview) startTurn(sessionId, prompt, origin = TurnOrigin(), review = request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow.update { it.copy(reviewError = e.userMessage()) }
            } finally {
                flow.update { it.copy(reviewing = false) }
            }
        }
    }

    /** Starts a turn. Returns false when the conversation is still busy with the previous one. */
    fun send(
        sessionId: String,
        text: String,
        images: List<PreparedImage> = emptyList(),
        files: List<FileRef> = emptyList(),
        origin: TurnOrigin = TurnOrigin(),
    ): Boolean = startTurn(sessionId, text, images, files, origin)

    private fun startTurn(
        sessionId: String,
        text: String,
        images: List<PreparedImage> = emptyList(),
        files: List<FileRef> = emptyList(),
        origin: TurnOrigin = TurnOrigin(),
        review: ReviewRequest? = null,
    ): Boolean {
        val flow = flowFor(sessionId)
        if (flow.value.isBusy || (text.isBlank() && images.isEmpty() && files.isEmpty())) return false
        val user = UiMessage(
            id = nextLocalId(), role = Role.User, text = text, images = images.map { ImageRef(it.dataUrl) }, files = files,
            review = review, origin = origin,
        )
        val assistant = UiMessage(
            id = nextLocalId(), role = Role.Assistant, state = MessageState.Streaming, startedAtMs = System.currentTimeMillis(),
        )
        val wasNew = flow.value.isNew
        flow.update { it.copy(messages = it.messages + user + assistant, loaded = true, loadError = null) }
        if (wasNew) addOptimisticSession(sessionId, text.ifBlank { files.joinToString(", ") { it.name } })
        val turn = Turn(sessionId, user.id, assistant.id, text, images, files, origin, review)
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
        // Only continue after background results if nobody else did meanwhile.
        if (lastUser.review != null && flow.value.awaitingReview) {
            reviewBackground(sessionId, lastUser.text, previous = lastUser.review)
            return
        }
        val images = lastUser.images.mapNotNull { ref ->
            ref.source.takeIf { it.startsWith("data:") }?.let { PreparedImage(ByteArray(0), it) }
        }
        // A question loaded from the transcript lost its origin; an assistant chat still says where it was asked.
        val origin = lastUser.origin
            ?: TurnOrigin(if (profileOf(sessionId) == ModelProfile.Assistant) TurnOrigin.Surface.Assistant else TurnOrigin.Surface.App)
        send(sessionId, lastUser.text, images, lastUser.files, origin)
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
                watches.remove(sessionId)?.job?.cancel()
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
                // A retried review asks the way it asked before.
                val model = turn.review?.model ?: settings.modelFor(profileOf(sessionId))
                val rich = turn.review?.richReplies ?: settings.richReplies
                val instructions = TurnInstructions.build(turn.origin, settings.phoneControl, rich)
                val events: Flow<AgentEvent> = if (turn.usesRuns) {
                    val runId = if (turn.review == null) api.startRun(sessionId, turn.input, model, instructions)
                    else admitReview(turn, model, rich, settings)
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
            val message = conversations[sessionId]?.value?.messages?.firstOrNull { it.id == turn.messageId }
            if (message != null && message.state == MessageState.Done && message.text.isNotBlank()) {
                val tools = message.steps.filter { it.kind == StepKind.Tool }.map { it.title }
                _completedTurns.value = CompletedTurn(sessionId, turn.messageId, message.text, idCounter.incrementAndGet(), tools)
            }
            refreshSessions()
            // A turn that sent work to the background, or ran while earlier work was out, may have
            // results coming; the transcript says which.
            val delegated = message?.steps.orEmpty().any { it.kind == StepKind.Subagent || (it.kind == StepKind.Tool && "delegate" in it.title) }
            if (!cancelled && (delegated || watches.containsKey(sessionId))) scope.launch { refreshAfterTurn(sessionId) }
        }
    }

    /** Hermes may still be saving the turn when its run ends; give it a moment before comparing. */
    private suspend fun refreshAfterTurn(sessionId: String) {
        for (pause in AFTER_TURN_PAUSES) {
            delay(pause)
            val rows = try {
                api.sessionMessages(sessionId).messages
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (show(sessionId, rows, afterTurn = true) == Shown.Replaced) return
        }
    }

    // ---- Background work ------------------------------------------------------------------------

    /**
     * Announces results for watched work in [rows], and starts watching work [rows] shows is still out.
     * Right after a turn of this app ([afterTurn]), the work that turn sent off counts as watched even
     * when its result is already in, so a result that beat this refresh is announced too. Results
     * already in a chat when it loads are not.
     */
    private suspend fun track(sessionId: String, rows: List<HermesMessage>, afterTurn: Boolean = false) {
        if (conversations[sessionId] == null) return
        val server = currentSettings().serverUrl
        if (afterTurn) {
            val question = rows.indexOfLast { it.role == "user" && it.displayKind != "hidden" && !BackgroundWork.isDelivery(it) }
            val started = rows.drop(question + 1).flatMap(BackgroundWork::dispatchedIds)
            if (started.isNotEmpty()) watches.getOrPut(sessionId) { Watch(sessionId) }.pending += started
        }
        announce(sessionId, rows, server)
        val since = System.currentTimeMillis() / 1000.0 - DISCOVERY_WINDOW_S
        val outstanding = BackgroundWork.outstanding(rows, since)
        val watch = if (outstanding.isEmpty()) watches[sessionId] else watches.getOrPut(sessionId) { Watch(sessionId) }
        watch ?: return
        watch.pending += outstanding
        forgetShown(watch)
        if (watch.pending.isEmpty() && watch.unshown.isEmpty()) {
            if (watch.job?.isActive != true) watches.remove(sessionId)
            return
        }
        watch.until = System.currentTimeMillis() + WATCH_MS
        if (watch.job?.isActive != true) watch.job = scope.launch { poll(watch) }
    }

    /**
     * Rows before [latest], Hermes' latest page, while they still fall in [DISCOVERY_WINDOW_S]: work a
     * long chat started further back may still be out after a restart. Empty when the chat fits in
     * one page or the server can't page back.
     */
    private suspend fun earlierRows(sessionId: String, latest: List<HermesMessage>): List<HermesMessage> {
        val since = System.currentTimeMillis() / 1000.0 - DISCOVERY_WINDOW_S
        val earlier = mutableListOf<HermesMessage>()
        var page = latest
        repeat(MAX_EARLIER_PAGES) {
            val oldest = page.firstOrNull()?.timestamp
            if (page.size < PAGE_ROWS || oldest == null || oldest < since) return earlier
            page = try {
                api.sessionRowsBefore(sessionId, offset = latest.size + earlier.size, limit = PAGE_ROWS) ?: return earlier
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return earlier
            }
            earlier.addAll(0, page)
        }
        return earlier
    }

    /** Drops results the chat now shows from [Watch.unshown]; a chat that isn't loaded shows them when it loads. */
    private fun forgetShown(watch: Watch) {
        val conversation = conversations[watch.sessionId]?.value
        if (conversation == null || !conversation.loaded) {
            watch.unshown.clear()
            return
        }
        watch.unshown -= conversation.messages.mapNotNullTo(mutableSetOf()) { it.background?.key }
    }

    /** Returns true when [rows] held a result for watched work that nobody announced yet. */
    private fun announce(sessionId: String, rows: List<HermesMessage>, server: String): Boolean {
        val watch = watches[sessionId] ?: return false
        var arrived = false
        rows.forEachIndexed { index, row ->
            if (!BackgroundWork.isDelivery(row)) return@forEachIndexed
            val result = BackgroundWork.parse(row)
            val id = result.delegationId ?: return@forEachIndexed
            if (id !in watch.pending) return@forEachIndexed
            if (!result.interim) watch.pending -= id
            // Delegation ids are unique per Hermes profile, and the server URL includes the profile.
            // The session id isn't stable: compression moves a chat to a new one.
            val key = "$server|${result.key}"
            if (announced.add(key)) {
                arrived = true
                watch.unshown += result.key
                _backgroundResults.tryEmit(BackgroundArrival(sessionId, row.id?.let { "h_$it" } ?: "h_$index", result, key))
            }
        }
        return arrived
    }

    private suspend fun poll(watch: Watch) {
        var round = 0
        try {
            var retries = 0
            while ((watch.pending.isNotEmpty() || watch.unshown.isNotEmpty()) && System.currentTimeMillis() < watch.until) {
                // A result that came in is shown right away; waiting applies to work still out, and to
                // a result that didn't show on the last try.
                if (watch.unshown.isEmpty()) delay(watchPauses[minOf(round++, watchPauses.lastIndex)])
                else if (retries > 0) delay(watchPauses[minOf(retries - 1, watchPauses.lastIndex)])
                val conversation = conversations[watch.sessionId]?.value ?: return
                // A running turn ends with a refresh of its own.
                if (conversation.isBusy) {
                    retries = 1
                    continue
                }
                try {
                    if (watch.unshown.isNotEmpty()) {
                        // The whole transcript, since a tail can't replace the chat.
                        show(watch.sessionId, api.sessionMessages(watch.sessionId).messages)
                        forgetShown(watch)
                        retries = if (watch.unshown.isEmpty()) 0 else retries + 1
                    } else {
                        announce(watch.sessionId, api.sessionTail(watch.sessionId, TAIL_ROWS).messages, currentSettings().serverUrl)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HermesApi.HermesException) {
                    if (e.status in setOf(401, 403, 404)) return
                    retries++
                } catch (_: Exception) {
                    retries++
                }
            }
        } finally {
            if (watches[watch.sessionId] === watch) watches.remove(watch.sessionId)
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
        val history = runCatching { HistoryMapper.map(api.sessionMessages(turn.sessionId).messages) }.getOrNull()
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

    /** The idempotency key for reviewing [result], the same on every device. Hermes takes 1 to 255 visible ASCII characters. */
    private fun reviewKey(result: UiMessage): String =
        ("dudan-review-" + result.background?.key.orEmpty().map { if (it.code in 33..126) it else '_' }.joinToString("")).take(240)

    /**
     * Starts a review run under the turn's key, with [model] and [rich] as that key was first sent. When
     * Hermes replays a run under that key which ended without finishing, the earlier attempt really
     * failed, so this one starts afresh under the next key, with the current [settings]. A run that is
     * still going or finished is joined instead. The request is recorded before it goes out, so a
     * retry after a lost answer asks the same way.
     */
    private suspend fun admitReview(turn: Turn, model: ModelChoice, rich: Boolean, settings: AppSettings): String {
        val key = turn.review!!.key
        remember(turn, ReviewRequest(key, model, rich))
        val admission = api.admitRun(turn.sessionId, turn.input, model, reviewInstructions(turn, rich), key)
        if (!admission.replayed || admission.status !in setOf("failed", "cancelled", "interrupted")) return admission.runId
        val next = ReviewRequest(
            key = key.substringBeforeLast('#') + "#" + ((key.substringAfterLast('#', "1").toIntOrNull() ?: 1) + 1),
            model = settings.modelFor(profileOf(turn.sessionId)),
            richReplies = settings.richReplies,
        )
        remember(turn, next)
        return api.admitRun(turn.sessionId, turn.input, next.model!!, reviewInstructions(turn, next.richReplies == true), next.key).runId
    }

    /**
     * A review's instructions leave out Phone control: that setting belongs to each phone, and another
     * dudan reviewing the same result has to send the same request to join the run.
     */
    private fun reviewInstructions(turn: Turn, rich: Boolean) = TurnInstructions.build(turn.origin, phoneControl = null, richReplies = rich)

    private fun remember(turn: Turn, request: ReviewRequest) {
        turn.review = request
        updateMessage(turn.sessionId, turn.userMessageId) { it.copy(review = request) }
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

        /** How long after its last sign background work is polled for. */
        private const val WATCH_MS = 60 * 60 * 1000L
        /** Background work started longer ago than this isn't watched when a chat opens. */
        private const val DISCOVERY_WINDOW_S = 6 * 60 * 60.0
        /** Rows per poll; results land at the end of the transcript. */
        private const val TAIL_ROWS = 40
        /** Hermes' page size; the default read returns the latest page. */
        private const val PAGE_ROWS = 500
        private const val MAX_EARLIER_PAGES = 10
        private val AFTER_TURN_PAUSES = listOf(500L, 2_000L, 5_000L)

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
    is HermesApi.HermesException -> when {
        status == 401 || status == 403 -> "Hermes rejected the API key ($status)."
        status == 404 -> message.ifBlank { "Not found on the Hermes server." }
        code == "idempotency_key_conflict" -> "These results were already reviewed from another device."
        status == 429 -> "Hermes is busy with too many runs. Try again in a moment."
        status == 0 -> message
        else -> "Hermes error $status: $message"
    }
    is java.net.UnknownHostException -> "Can't find the Hermes server. Is Tailscale connected?"
    is java.net.ConnectException -> "Can't reach the Hermes server. Is the API server running?"
    is java.net.SocketTimeoutException -> "The Hermes server stopped responding."
    else -> message ?: toString()
}
