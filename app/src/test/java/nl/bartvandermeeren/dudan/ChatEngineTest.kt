package nl.bartvandermeeren.dudan

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nl.bartvandermeeren.dudan.chat.ChatEngine
import nl.bartvandermeeren.dudan.chat.Conversation
import nl.bartvandermeeren.dudan.chat.MessageState
import nl.bartvandermeeren.dudan.chat.PreparedImage
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.chat.TurnInstructions
import nl.bartvandermeeren.dudan.chat.TurnOrigin
import nl.bartvandermeeren.dudan.chat.UiMessage
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.ModelChoice
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.data.ReasoningEffort
import nl.bartvandermeeren.dudan.device.PhoneControl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Scenario tests for stop, recovery and turn ownership against a scripted Hermes server. */
class ChatEngineTest {
    @org.junit.Test fun renamedAndExistingAssistantSessionsKeepTheirModelProfile() {
        assertEquals(ModelProfile.Assistant, ChatEngine.profileOf("dudan_assist_123"))
        assertEquals(ModelProfile.Assistant, ChatEngine.profileOf("aight_assist_123"))
        assertEquals(ModelProfile.Chats, ChatEngine.profileOf("dudan_123"))
        assertEquals(ModelProfile.Chats, ChatEngine.profileOf("aight_123"))
        assertEquals(ModelProfile.Chats, ChatEngine.profileOf("telegram_123"))
    }

    private lateinit var server: MockWebServer
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var engine: ChatEngine
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settings = AppSettings(serverUrl = server.url("/").toString(), apiKey = "secret-key-1234567")
        engine = ChatEngine(HermesApi(OkHttpClient()) { settings.server }, scope, watchPauses = listOf(100L)) { settings }
    }

    @After
    fun tearDown() {
        scope.cancel()
        dispatcher.close()
        server.shutdown()
    }

    /** Scripts Hermes. The handler sees transcript requests first; other session calls get empty defaults. */
    private fun script(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                return when {
                    request.path!!.contains("/messages") -> handler(request)
                    request.path!!.startsWith("/api/sessions") && request.method == "GET" ->
                        MockResponse().setBody("""{"object":"list","data":[]}""")
                    request.path == "/api/sessions" && request.method == "POST" -> MockResponse().setResponseCode(201).setBody("{}")
                    else -> handler(request)
                }
            }
        }
    }

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    /** Sends a message on the engine thread and waits until the assistant reply settles. */
    private fun sendAndSettle(
        text: String,
        profile: ModelProfile = ModelProfile.Chats,
        origin: TurnOrigin = TurnOrigin(),
        images: List<PreparedImage> = emptyList(),
        whileRunning: (String) -> Unit = {},
    ): UiMessage = runBlocking {
        val id = withContext(dispatcher) { engine.startNew(profile).also { engine.send(it, text, images, origin = origin) } }
        whileRunning(id)
        settle(id)
    }

    private suspend fun settle(id: String): UiMessage = withTimeout(20_000) {
        val flow = withContext(dispatcher) { engine.conversation(id) }
        flow.first { c: Conversation -> c.messages.lastOrNull()?.let { !it.isStreaming } == true }.messages.last()
    }

    private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject

    @Test
    fun everyTurnTellsHermesWhereItWasAsked() {
        settings = settings.copy(phoneControl = true)
        val runs = CopyOnWriteArrayList<String>()
        script { request ->
            when {
                request.path == "/v1/runs" -> {
                    runs += request.body.readUtf8()
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"run_${runs.size}"}""")
                }
                request.path!!.endsWith("/events") -> sse("""{"event":"run.completed","output":"Ok"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val spoken = TurnOrigin(TurnOrigin.Surface.Assistant, spoken = true)
        sendAndSettle("Wat is de hoofdstad van Frankrijk?")
        sendAndSettle("Zet een timer van tien minuten", ModelProfile.Assistant, spoken)
        val typed = runs[0].json()["instructions"]?.jsonPrimitive?.content
        assertEquals(TurnInstructions.build(TurnOrigin(), phoneControl = true, richReplies = true), typed)
        assertTrue(typed!!.contains("openui-lang"))
        val heard = runs[1].json()["instructions"]?.jsonPrimitive?.content
        assertEquals(TurnInstructions.build(spoken, phoneControl = true, richReplies = true), heard)
        assertFalse(heard!!.contains("openui-lang"))
    }

    @Test
    fun imageTurnsSendTheInstructionsAsTheSessionSystemMessage() {
        val bodies = CopyOnWriteArrayList<String>()
        script { request ->
            if (request.path!!.endsWith("/chat/stream")) {
                bodies += request.body.readUtf8()
                sse("""{"event":"run.completed","output":"Een instellingenscherm."}""")
            } else {
                MockResponse().setResponseCode(404)
            }
        }
        val origin = TurnOrigin(TurnOrigin.Surface.Assistant, screenshot = true)
        val reply = sendAndSettle(
            "Wat zie ik hier?", ModelProfile.Assistant, origin, listOf(PreparedImage(ByteArray(0), "data:image/jpeg;base64,AAAA")),
        )
        assertEquals(MessageState.Done, reply.state)
        val body = bodies.single().json()
        assertEquals(TurnInstructions.build(origin, phoneControl = false, richReplies = true), body["system_message"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("instructions"))
    }

    @Test
    fun retrySendsTheQuestionTheWayItWasFirstAsked() {
        val runs = CopyOnWriteArrayList<String>()
        script { request ->
            when {
                request.path == "/v1/runs" -> {
                    runs += request.body.readUtf8()
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"run_${runs.size}"}""")
                }
                request.path == "/v1/runs/run_1/events" -> sse("""{"event":"run.failed","error":"Provider overloaded"}""")
                request.path!!.endsWith("/events") -> sse("""{"event":"run.completed","output":"Het is kwart over acht."}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val live = TurnOrigin(TurnOrigin.Surface.Live, spoken = true)
        var id = ""
        val failed = sendAndSettle("Hoe laat is het?", origin = live) { id = it }
        assertEquals(MessageState.Failed, failed.state)

        val retried = runBlocking {
            withContext(dispatcher) { engine.retry(id) }
            settle(id)
        }
        assertEquals(MessageState.Done, retried.state)
        assertEquals(2, runs.size)
        assertEquals(TurnInstructions.build(live, phoneControl = false, richReplies = true), runs[1].json()["instructions"]?.jsonPrimitive?.content)
    }

    @Test
    fun failedHistoryLoadKeepsTheErrorVisibleUntilRetrySucceeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"History unavailable"}}"""))
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        val flow = withContext(dispatcher) {
            engine.load("existing_session")
            engine.conversation("existing_session").also { assertFalse(it.value.showGreeting) }
        }
        val failed = withTimeout(5_000) { flow.first { it.loadError != null } }
        assertFalse(failed.loading)
        assertTrue(failed.messages.isEmpty())
        assertFalse(failed.showGreeting)

        withContext(dispatcher) {
            engine.load("existing_session", force = true)
            assertFalse(flow.value.showGreeting)
        }
        val recovered = withTimeout(5_000) { flow.first { it.loaded } }
        assertEquals(null, recovered.loadError)
        assertTrue(recovered.showGreeting)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun assistantChatsRunOnTheFastModelAndOtherChatsOnTheNormalOne() {
        settings = settings.copy(
            model = ModelChoice("anthropic", "big-model"),
            assistantModel = ModelChoice("openrouter", "small-model", effort = ReasoningEffort.Low),
        )
        val runs = CopyOnWriteArrayList<String>()
        script { request ->
            when {
                request.path == "/v1/runs" -> {
                    runs += request.body.readUtf8()
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"run_${runs.size}"}""")
                }
                request.path!!.endsWith("/events") -> sse("""{"event":"run.completed","output":"Ok"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        sendAndSettle("Wat staat er vandaag in mijn agenda?", ModelProfile.Assistant)
        sendAndSettle("Help me met een langer plan")
        assertTrue(runs[0], runs[0].contains(""""model":"small-model"""") && runs[0].contains(""""reasoning_effort":"low"""") && !runs[0].contains("fast"))
        assertTrue(runs[1], runs[1].contains(""""model":"big-model"""") && !runs[1].contains("model_options"))
        assertEquals(ModelProfile.Assistant, ChatEngine.profileOf(runs[0].substringAfter(""""session_id":"""").substringBefore('"')))
    }

    @Test
    fun completedTurnsNameTheToolsTheyCalled() {
        script { request ->
            when {
                request.path == "/v1/runs" -> MockResponse().setResponseCode(202).setBody("""{"run_id":"run_tools"}""")
                request.path!!.endsWith("/events") -> sse(
                    """{"event":"tool.started","tool":"mcp__phone__open_app","preview":"Spotify"}""",
                    """{"event":"tool.completed","tool":"mcp__phone__open_app","preview":"Opened Spotify."}""",
                    """{"event":"run.completed","output":"Spotify staat aan."}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        sendAndSettle("Open Spotify")
        val turn = runBlocking {
            withTimeout(5_000) { withContext(dispatcher) { engine.completedTurns }.first { it != null }!! }
        }
        // The reply notifier keys off this to stay quiet about a turn that opened something.
        assertEquals(listOf("mcp__phone__open_app"), turn.tools)
        assertTrue(PhoneControl.openedSomething(turn.tools))
    }

    @Test
    fun stopBeforeTheRunIdArrivesIsSentOnceHermesReturnsIt() {
        val stopSent = CountDownLatch(1)
        script { request ->
            when (request.path) {
                "/v1/runs" -> MockResponse().setBodyDelay(400, TimeUnit.MILLISECONDS).setResponseCode(202).setBody("""{"run_id":"run_1"}""")
                "/v1/runs/run_1/stop" -> MockResponse().setBody("""{"status":"stopping"}""").also { stopSent.countDown() }
                "/v1/runs/run_1/events" -> {
                    // Hermes only reports cancellation after it received the stop.
                    stopSent.await(10, TimeUnit.SECONDS)
                    sse("""{"event":"run.cancelled"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val reply = sendAndSettle("Doe iets langs") { id -> runBlocking { withContext(dispatcher) { engine.stop(id) } } }
        assertEquals(MessageState.Cancelled, reply.state)
        assertTrue(requests.contains("POST /v1/runs/run_1/stop"))
    }

    @Test
    fun droppedStreamPollsTheRunUntilItFinishes() {
        var polls = 0
        script { request ->
            when (request.path) {
                "/v1/runs" -> MockResponse().setResponseCode(202).setBody("""{"run_id":"run_2"}""")
                // The stream dies after one delta, without a terminal event.
                "/v1/runs/run_2/events" -> sse("""{"event":"message.delta","delta":"Half"}""")
                "/v1/runs/run_2" -> {
                    polls++
                    if (polls < 2) MockResponse().setBody("""{"status":"running"}""")
                    else MockResponse().setBody("""{"status":"completed","output":"Volledig antwoord."}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val reply = sendAndSettle("Vraag")
        assertEquals(MessageState.Done, reply.state)
        assertEquals("Volledig antwoord.", reply.text)
    }

    @Test
    fun recoveryNeverReusesTheAnswerToAnEarlierQuestion() {
        script { request ->
            when {
                request.path == "/v1/runs" -> MockResponse().setResponseCode(202).setBody("""{"run_id":"run_3"}""")
                request.path == "/v1/runs/run_3/events" -> sse("""{"event":"message.delta","delta":"x"}""")
                request.path == "/v1/runs/run_3" -> MockResponse().setResponseCode(404).setBody("""{"error":{"message":"Run not found"}}""")
                request.path!!.contains("/messages") -> MockResponse().setBody(
                    """{"data":[{"role":"user","content":"Oude vraag"},{"role":"assistant","content":"Oud antwoord"}]}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val reply = sendAndSettle("Nieuwe vraag")
        assertEquals(MessageState.Failed, reply.state)
        assertTrue(reply.text != "Oud antwoord")
        assertTrue(requests.any { it.endsWith("/messages") })
    }

    // ---- Background work that reports back after the agent's turn -------------------------------

    /** The session transcript Hermes keeps; tests add rows while the engine runs. */
    private val transcript = CopyOnWriteArrayList<JsonObject>()
    private val rowIds = AtomicLong()

    private fun row(role: String, content: String, displayKind: String? = null, tool: String? = null) = buildJsonObject {
        put("id", rowIds.incrementAndGet())
        put("role", role)
        put("content", content)
        put("timestamp", System.currentTimeMillis() / 1000.0)
        if (displayKind != null) put("display_kind", displayKind)
        if (tool != null) {
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", "call_$tool")
                    put("function", buildJsonObject { put("name", tool); put("arguments", "{}") })
                })
            })
        }
    }

    private fun delivery(content: String) = row("user", content, displayKind = "async_delegation_complete")

    /** Answers /messages like Hermes: the latest 500 rows by default, or the last `limit` rows for `order=latest`. */
    private fun transcriptPage(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val limit = url.queryParameter("limit")?.toInt() ?: 500
        val offset = url.queryParameter("offset")?.toInt() ?: 0
        val rows = transcript.toList()
        val end = (rows.size - offset).coerceAtLeast(0)
        val body = buildJsonObject {
            put("object", "list")
            put("data", JsonArray(rows.subList((end - limit).coerceAtLeast(0), end)))
            put("pagination", buildJsonObject { put("order", "latest"); put("limit", limit); put("offset", offset) })
        }
        return MockResponse().setBody(body.toString())
    }

    /**
     * Hermes' Idempotency-Key handling: a known key with the same request gets its run back with the
     * run's status; with a different request it is refused.
     */
    private class Admissions {
        val keys = CopyOnWriteArrayList<String>()
        val runs = LinkedHashMap<String, String>()
        val bodies = mutableMapOf<String, String>()
        val status = java.util.concurrent.ConcurrentHashMap<String, String>()

        @Synchronized
        fun admit(request: RecordedRequest, onNew: (String) -> MockResponse = { MockResponse() }): MockResponse {
            val key = request.getHeader("Idempotency-Key").orEmpty()
            val body = request.body.readUtf8()
            keys += key
            runs[key]?.let { run ->
                if (bodies[key] != body) {
                    return MockResponse().setResponseCode(409)
                        .setBody("""{"error":{"message":"Idempotency-Key was already used with a different request payload","code":"idempotency_key_conflict"}}""")
                }
                return MockResponse().setResponseCode(202).setBody("""{"run_id":"$run","status":"${status[run] ?: "running"}","replayed":true}""")
            }
            val run = "run_${runs.size + 1}"
            runs[key] = run
            bodies[key] = body
            return onNew(run).setResponseCode(202).setBody("""{"run_id":"$run","status":"started"}""")
        }
    }

    private val fullReads get() = requests.count { it.endsWith("/messages") }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out; requests so far: $requests" }
            Thread.sleep(20)
        }
    }

    private fun awaitRequest(match: (String) -> Boolean) = waitUntil { requests.any(match) }

    private fun collectArrivals(): MutableList<ChatEngine.BackgroundArrival> {
        val arrivals = CopyOnWriteArrayList<ChatEngine.BackgroundArrival>()
        runBlocking { withContext(dispatcher) { scope.launch { engine.backgroundResults.collect { arrivals += it } } } }
        return arrivals
    }

    private fun open(sessionId: String): StateFlow<Conversation> = runBlocking {
        val flow = withContext(dispatcher) { engine.load(sessionId); engine.conversation(sessionId) }
        withTimeout(5_000) { flow.first { it.loaded } }
        flow
    }

    private fun <T> StateFlow<T>.await(condition: (T) -> Boolean): T = runBlocking { withTimeout(10_000) { first(condition) } }

    private val tailPolls get() = requests.count { it.contains("order=latest") }

    @Test
    fun aResultThatLandsAfterTheRunEndedShowsUpWithoutAnotherTurn() {
        val runs = AtomicInteger()
        script { request ->
            when {
                request.path!!.contains("/messages") -> transcriptPage(request)
                request.path == "/v1/runs" -> {
                    runs.incrementAndGet()
                    transcript += row("user", "Zoek vluchten")
                    transcript += row("assistant", "", tool = "delegate_task")
                    transcript += row("tool", Deliveries.dispatched("deleg_1"))
                    transcript += row("assistant", "Ik heb een subagent gestart; het resultaat volgt.")
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"run_bg"}""")
                }
                request.path == "/v1/runs/run_bg/events" -> sse(
                    """{"event":"tool.started","tool":"delegate_task","preview":"Zoek vluchten"}""",
                    """{"event":"tool.completed","tool":"delegate_task","preview":"dispatched"}""",
                    """{"event":"run.completed","output":"Ik heb een subagent gestart; het resultaat volgt."}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val arrivals = collectArrivals()
        var id = ""
        sendAndSettle("Zoek vluchten") { id = it }
        // The parent run is over and the worker still busy: the app keeps an eye on the transcript.
        awaitRequest { it.contains("order=latest") }
        transcript += delivery(Deliveries.single("deleg_1"))

        val flow = runBlocking { withContext(dispatcher) { engine.conversation(id) } }
        val shown = flow.await { c -> c.messages.lastOrNull()?.role == Role.Background }
        assertEquals("Zoek vluchten", shown.messages.single { it.role == Role.User }.text)
        assertTrue(shown.awaitingReview)
        // Arrival alone never continues the task.
        assertEquals(1, runs.get())

        // Later polls and reloads don't announce it again, and the watch ends with the result.
        Thread.sleep(500)
        assertEquals(listOf("deleg_1"), arrivals.map { it.result.delegationId })
        val polls = tailPolls
        runBlocking { withContext(dispatcher) { engine.load(id) } }
        Thread.sleep(400)
        assertEquals(polls, tailPolls)
        assertEquals(1, arrivals.size)
        assertEquals(1, runs.get())
    }

    @Test
    fun aResultThatBeatsTheRefreshAfterTheTurnIsStillAnnounced() {
        script { request ->
            when {
                request.path!!.contains("/messages") -> transcriptPage(request)
                request.path == "/v1/runs" -> {
                    transcript += row("user", "Snel klusje")
                    transcript += row("assistant", "", tool = "delegate_task")
                    transcript += row("tool", Deliveries.dispatched("deleg_fast"))
                    transcript += row("assistant", "Gestart.")
                    // The worker finishes before the app reads the transcript after the turn.
                    transcript += delivery(Deliveries.single("deleg_fast"))
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"run_fast"}""")
                }
                request.path == "/v1/runs/run_fast/events" -> sse(
                    """{"event":"tool.started","tool":"delegate_task"}""",
                    """{"event":"tool.completed","tool":"delegate_task"}""",
                    """{"event":"run.completed","output":"Gestart."}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val arrivals = collectArrivals()
        var id = ""
        sendAndSettle("Snel klusje") { id = it }
        val flow = runBlocking { withContext(dispatcher) { engine.conversation(id) } }
        flow.await { c -> c.messages.lastOrNull()?.role == Role.Background }
        Thread.sleep(300)
        assertEquals(listOf("deleg_fast"), arrivals.map { it.result.delegationId })
    }

    @Test
    fun aResultStillShowsWhenTheFirstRefreshAfterFindingItFails() {
        transcript += row("user", "Zoek vluchten")
        transcript += row("assistant", "", tool = "delegate_task")
        transcript += row("tool", Deliveries.dispatched("deleg_retry"))
        transcript += row("assistant", "Gestart.")
        val failFullReads = AtomicInteger(0)
        script { request ->
            when {
                request.path!!.endsWith("/messages") && failFullReads.getAndDecrement() > 0 ->
                    MockResponse().setResponseCode(502).setBody("""{"error":{"message":"Bad gateway"}}""")
                request.path!!.contains("/messages") -> transcriptPage(request)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val flow = open("s_flaky")
        awaitRequest { it.contains("order=latest") }
        // The tail poll will find the result; the next two full reads fail.
        failFullReads.set(2)
        val reads = fullReads
        transcript += delivery(Deliveries.single("deleg_retry"))
        val shown = flow.await { c -> c.messages.lastOrNull()?.role == Role.Background }
        assertTrue(fullReads >= reads + 3)
        assertTrue(shown.awaitingReview)
    }

    @Test
    fun reopeningALoadedChatShowsWhatArrivedMeanwhile() {
        transcript += row("user", "Vraag")
        transcript += row("assistant", "Antwoord")
        script { request -> if (request.path!!.contains("/messages")) transcriptPage(request) else MockResponse().setResponseCode(404) }
        val flow = open("s_reopen")
        assertEquals(2, flow.value.messages.size)

        transcript += delivery(Deliveries.single("deleg_2"))
        // Opening the chat again, and the app coming back to the front, use the cached path.
        runBlocking { withContext(dispatcher) { engine.load("s_reopen") } }
        val reopened = flow.await { it.messages.size == 3 }
        assertEquals(Role.Background, reopened.messages.last().role)
        assertTrue(reopened.awaitingReview)
    }

    @Test
    fun refreshingNeverOverwritesARunningTurn() {
        transcript += row("user", "Eerder")
        transcript += row("assistant", "Eerder antwoord")
        val release = CountDownLatch(1)
        script { request ->
            when {
                request.path!!.contains("/messages") -> transcriptPage(request)
                request.path == "/v1/runs" -> MockResponse().setResponseCode(202).setBody("""{"run_id":"run_busy"}""")
                request.path == "/v1/runs/run_busy/events" -> {
                    release.await(10, TimeUnit.SECONDS)
                    sse("""{"event":"message.delta","delta":"Klaar"}""", """{"event":"run.completed","output":"Klaar"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val flow = open("s_busy")
        runBlocking { withContext(dispatcher) { engine.send("s_busy", "Nieuw") } }
        // Another client adds to the transcript while this turn streams.
        transcript += row("user", "Iets anders")
        transcript += row("assistant", "Van een ander apparaat")
        runBlocking {
            withContext(dispatcher) {
                engine.load("s_busy", force = true)
                engine.load("s_busy")
                engine.sync("s_busy")
            }
        }
        Thread.sleep(300)
        val during = flow.value
        assertTrue(during.isBusy)
        assertEquals("Nieuw", during.messages[during.messages.lastIndex - 1].text)
        assertTrue(during.messages.none { it.text == "Van een ander apparaat" })

        release.countDown()
        val done = flow.await { !it.isBusy }
        assertEquals("Klaar", done.messages.last().text)
    }

    @Test
    fun reviewStartsOneNormalTurnWhileTheResultIsStillTheLastThing() {
        transcript += row("user", "Zoek vluchten")
        transcript += row("assistant", "Gestart.")
        transcript += delivery(Deliveries.single("deleg_4"))
        val inputs = CopyOnWriteArrayList<String>()
        val keys = CopyOnWriteArrayList<String>()
        script { request ->
            when {
                request.path!!.contains("/messages") -> transcriptPage(request)
                request.path == "/v1/runs" -> {
                    inputs += request.body.readUtf8()
                    keys += request.getHeader("Idempotency-Key").orEmpty()
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"run_review"}""")
                }
                request.path == "/v1/runs/run_review/events" -> sse("""{"event":"run.completed","output":"KLM om 12:00 is geboekt."}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val flow = open("s_review")
        assertTrue(flow.value.awaitingReview)
        // A double tap.
        runBlocking {
            withContext(dispatcher) {
                engine.reviewBackground("s_review", "Rond af")
                engine.reviewBackground("s_review", "Rond af")
            }
        }
        val done = flow.await { c -> c.messages.lastOrNull()?.let { it.role == Role.Assistant && !it.isStreaming } == true }
        assertEquals(MessageState.Done, done.messages.last().state)
        assertEquals(1, inputs.size)
        assertTrue(inputs[0], inputs[0].contains(""""input":"Rond af""""))
        // Another dudan reviewing the same result, or a repeated request, joins this run. The overlay and
        // the app ask the same way, so the surface it was tapped on doesn't change the request.
        assertEquals(listOf("dudan-review-deleg_4"), keys)
        assertEquals(
            TurnInstructions.build(TurnOrigin(), phoneControl = false, richReplies = true),
            inputs[0].json()["instructions"]?.jsonPrimitive?.content,
        )
        assertTrue(done.messages.last { it.role == Role.User }.reviewsBackground)
        assertFalse(done.awaitingReview)
    }

    @Test
    fun retryingAFailedReviewChecksAgainAndStartsAFreshRun() {
        transcript += row("user", "Zoek vluchten")
        transcript += row("assistant", "Gestart.")
        transcript += delivery(Deliveries.single("deleg_7"))
        val hermes = Admissions()
        script { request ->
            when {
                request.path!!.contains("/messages") -> transcriptPage(request)
                request.path == "/v1/runs" -> hermes.admit(request)
                request.path == "/v1/runs/run_1/events" -> {
                    hermes.status["run_1"] = "failed"
                    sse("""{"event":"run.failed","error":"provider error"}""")
                }
                request.path == "/v1/runs/run_2/events" -> sse("""{"event":"run.completed","output":"Klaar."}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val flow = open("s_retry")
        runBlocking { withContext(dispatcher) { engine.reviewBackground("s_retry", "Rond af") } }
        flow.await { it.messages.lastOrNull()?.state == MessageState.Failed }

        runBlocking { withContext(dispatcher) { engine.retry("s_retry") } }
        val done = flow.await { c -> c.messages.lastOrNull()?.let { it.role == Role.Assistant && it.state == MessageState.Done } == true }
        assertEquals("Klaar.", done.messages.last().text)
        // Hermes replayed the failed run under the first key, so the retry moved on to the next one.
        assertEquals(listOf("dudan-review-deleg_7", "dudan-review-deleg_7", "dudan-review-deleg_7#2"), hermes.keys)
        assertEquals(1, done.messages.count { it.role == Role.User && it.reviewsBackground })
        assertEquals("dudan-review-deleg_7#2", done.messages.last { it.role == Role.User }.review?.key)
    }

    @Test
    fun aRetryAfterALostAdmissionJoinsTheRunHermesAlreadyStarted() {
        // Without OkHttp's own retry, so the lost answer reaches the engine.
        engine = ChatEngine(HermesApi(OkHttpClient.Builder().retryOnConnectionFailure(false).build()) { settings.server }, scope, listOf(100L)) { settings }
        transcript += row("user", "Zoek vluchten")
        transcript += row("assistant", "Gestart.")
        transcript += delivery(Deliveries.single("deleg_8"))
        val hermes = Admissions()
        script { request ->
            when {
                request.path!!.contains("/messages") -> transcriptPage(request)
                // Hermes admits the run, but the answer never reaches the phone.
                request.path == "/v1/runs" -> hermes.admit(request) { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                request.path == "/v1/runs/run_1/events" -> sse("""{"event":"run.completed","output":"Klaar via de eerste run."}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val flow = open("s_lost")
        runBlocking { withContext(dispatcher) { engine.reviewBackground("s_lost", "Rond af") } }
        flow.await { it.messages.lastOrNull()?.state == MessageState.Failed }

        hermes.status["run_1"] = "completed"
        // A different model, no rich replies and Phone control on now; the retry must still ask the way it first did.
        settings = settings.copy(model = ModelChoice("anthropic", "other-model"), richReplies = false, phoneControl = true)
        runBlocking { withContext(dispatcher) { engine.retry("s_lost") } }
        val done = flow.await { c -> c.messages.lastOrNull()?.let { it.role == Role.Assistant && it.state == MessageState.Done } == true }
        assertEquals("Klaar via de eerste run.", done.messages.last().text)
        assertEquals(setOf("dudan-review-deleg_8"), hermes.keys.toSet())
        assertEquals(1, hermes.runs.size)
    }

    @Test
    fun aResultFoundWhileATurnStartsIsShownOnceTheTurnEnds() {
        transcript += row("user", "Zoek vluchten")
        transcript += row("assistant", "", tool = "delegate_task")
        transcript += row("tool", Deliveries.dispatched("deleg_busy"))
        transcript += row("assistant", "Gestart.")
        val holdFullRead = java.util.concurrent.atomic.AtomicBoolean(false)
        val fullReadHeld = CountDownLatch(1)
        val releaseFullRead = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        script { request ->
            when {
                request.path!!.endsWith("/messages") && holdFullRead.compareAndSet(true, false) -> {
                    fullReadHeld.countDown()
                    releaseFullRead.await(10, TimeUnit.SECONDS)
                    transcriptPage(request)
                }
                request.path!!.contains("/messages") -> transcriptPage(request)
                request.path == "/v1/runs" -> {
                    transcript += row("user", "Nog iets")
                    transcript += row("assistant", "Klaar.")
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"run_next"}""")
                }
                request.path == "/v1/runs/run_next/events" -> {
                    releaseRun.await(10, TimeUnit.SECONDS)
                    sse("""{"event":"run.completed","output":"Klaar."}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val flow = open("s_race")
        awaitRequest { it.contains("order=latest") }
        holdFullRead.set(true)
        transcript += delivery(Deliveries.single("deleg_busy"))
        // The poll found the result; while it reads the whole transcript, the user starts a turn.
        check(fullReadHeld.await(10, TimeUnit.SECONDS))
        runBlocking { withContext(dispatcher) { engine.send("s_race", "Nog iets") } }
        releaseFullRead.countDown()
        Thread.sleep(400)
        releaseRun.countDown()
        val shown = flow.await { c -> !c.isBusy && c.messages.any { it.background?.key == "deleg_busy" } }
        assertEquals("Klaar.", shown.messages.last().text)
    }

    @Test
    fun aResultFoundJustBeforeATurnTheUserStopsStillShows() {
        transcript += row("user", "Zoek vluchten")
        transcript += row("assistant", "", tool = "delegate_task")
        transcript += row("tool", Deliveries.dispatched("deleg_stop"))
        transcript += row("assistant", "Gestart.")
        val holdFullRead = java.util.concurrent.atomic.AtomicBoolean(false)
        val fullReadHeld = CountDownLatch(1)
        val releaseFullRead = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        script { request ->
            when {
                request.path!!.endsWith("/messages") && holdFullRead.compareAndSet(true, false) -> {
                    fullReadHeld.countDown()
                    releaseFullRead.await(10, TimeUnit.SECONDS)
                    transcriptPage(request)
                }
                request.path!!.contains("/messages") -> transcriptPage(request)
                // Hermes saves the question; the user stops the turn before a reply.
                request.path == "/v1/runs" -> {
                    transcript += row("user", "Nog iets")
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"run_stop"}""")
                }
                request.path == "/v1/runs/run_stop/events" -> {
                    releaseRun.await(10, TimeUnit.SECONDS)
                    sse("""{"event":"run.cancelled"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val flow = open("s_stop")
        awaitRequest { it.contains("order=latest") }
        holdFullRead.set(true)
        transcript += delivery(Deliveries.single("deleg_stop"))
        check(fullReadHeld.await(10, TimeUnit.SECONDS))
        runBlocking { withContext(dispatcher) { engine.send("s_stop", "Nog iets") } }
        releaseFullRead.countDown()
        Thread.sleep(400)
        releaseRun.countDown()
        val shown = flow.await { c -> !c.isBusy && c.messages.any { it.background?.key == "deleg_stop" } }
        // The stopped turn stays as it was, after the result that came in before it.
        assertEquals(MessageState.Cancelled, shown.messages.last().state)
        assertEquals(listOf(Role.Background, Role.User, Role.Assistant), shown.messages.takeLast(3).map { it.role })
    }

    @Test
    fun workStartedMoreThanAPageBackIsWatchedAfterARestart() {
        transcript += row("user", "Lang klusje")
        transcript += row("assistant", "", tool = "delegate_task")
        transcript += row("tool", Deliveries.dispatched("deleg_far"))
        transcript += row("assistant", "Gestart.")
        // The chat went on for more than Hermes' 500-row page while the worker ran.
        repeat(300) {
            transcript += row("user", "Vraag $it")
            transcript += row("assistant", "Antwoord $it")
        }
        script { request -> if (request.path!!.contains("/messages")) transcriptPage(request) else MockResponse().setResponseCode(404) }
        val arrivals = collectArrivals()
        val flow = open("s_far")
        awaitRequest { it.contains("offset=500") }
        awaitRequest { it.contains("order=latest&limit=40") }
        transcript += delivery(Deliveries.single("deleg_far"))
        flow.await { c -> c.messages.lastOrNull()?.background?.key == "deleg_far" }
        Thread.sleep(300)
        assertEquals(listOf("deleg_far"), arrivals.map { it.result.delegationId })
    }

    @Test
    fun reviewStartsNothingWhenAnotherClientAlreadyContinued() {
        transcript += row("user", "Zoek vluchten")
        transcript += row("assistant", "Gestart.")
        transcript += delivery(Deliveries.single("deleg_5"))
        val runs = AtomicInteger()
        script { request ->
            when {
                request.path!!.contains("/messages") -> transcriptPage(request)
                request.path == "/v1/runs" -> MockResponse().setResponseCode(202).setBody("""{"run_id":"run_${runs.incrementAndGet()}"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val flow = open("s_other")
        assertTrue(flow.value.awaitingReview)
        transcript += row("user", "Ga door")
        transcript += row("assistant", "Afgerond via Telegram.")

        runBlocking { withContext(dispatcher) { engine.reviewBackground("s_other", "Rond af") } }
        val after = flow.await { !it.reviewing && it.messages.last().text == "Afgerond via Telegram." }
        assertFalse(after.awaitingReview)
        assertEquals(0, runs.get())
    }

    @Test
    fun afterARestartOldResultsShowQuietlyAndOpenWorkIsWatchedAgain() {
        // A chat longer than Hermes' 500-row page.
        repeat(300) {
            transcript += row("user", "Vraag $it")
            transcript += row("assistant", "Antwoord $it")
        }
        // What an earlier app process left: one result that came in, one worker still busy.
        transcript += row("user", "Doe twee dingen")
        transcript += row("assistant", "", tool = "delegate_task")
        transcript += row("tool", Deliveries.dispatched("deleg_old"))
        transcript += row("tool", Deliveries.dispatched("deleg_open"))
        transcript += row("assistant", "Twee subagents gestart.")
        transcript += delivery(Deliveries.single("deleg_old"))
        script { request -> if (request.path!!.contains("/messages")) transcriptPage(request) else MockResponse().setResponseCode(404) }
        val arrivals = collectArrivals()

        val flow = open("s_restart")
        assertEquals(Role.Background, flow.value.messages.last().role)
        assertTrue(flow.value.awaitingReview)
        awaitRequest { it.contains("order=latest") }
        Thread.sleep(300)
        assertTrue(arrivals.isEmpty())

        // An early warning shows up once, however often the transcript is read; the batch goes on.
        transcript += delivery(Deliveries.taskFailed("deleg_open"))
        flow.await { c -> c.messages.count { it.role == Role.Background } == 2 }
        val polls = tailPolls
        waitUntil { tailPolls >= polls + 3 }
        assertEquals(listOf("deleg_open#task2"), arrivals.map { it.result.key })

        transcript += delivery(Deliveries.batch("deleg_open"))
        val shown = flow.await { c -> c.messages.count { it.role == Role.Background } == 3 }
        assertEquals(nl.bartvandermeeren.dudan.chat.BackgroundStatus.PartlyFailed, shown.messages.last().background?.status)
        Thread.sleep(300)
        assertEquals(listOf("deleg_open#task2", "deleg_open"), arrivals.map { it.result.key })
    }

    @Test
    fun reviewAfterAStoppedTurnStillDefersToAnotherClient() {
        transcript += row("user", "Zoek vluchten")
        transcript += row("assistant", "Gestart.")
        val runs = AtomicInteger()
        script { request ->
            when {
                request.path!!.contains("/messages") -> transcriptPage(request)
                request.path == "/v1/runs" -> {
                    transcript += row("user", "Stop maar")
                    MockResponse().setResponseCode(202).setBody("""{"run_id":"run_${runs.incrementAndGet()}"}""")
                }
                request.path!!.endsWith("/events") -> sse("""{"event":"run.cancelled"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val flow = open("s_stopped")
        runBlocking { withContext(dispatcher) { engine.send("s_stopped", "Stop maar") } }
        flow.await { it.messages.lastOrNull()?.state == MessageState.Cancelled }

        // The worker reports back after the stop. The chat keeps "Stopped" and shows the result below it.
        transcript += delivery(Deliveries.single("deleg_6"))
        runBlocking { withContext(dispatcher) { engine.load("s_stopped") } }
        val waiting = flow.await { it.awaitingReview }
        assertEquals(MessageState.Cancelled, waiting.messages[waiting.messages.lastIndex - 1].state)

        // Meanwhile another client continued.
        transcript += row("user", "Ga door")
        transcript += row("assistant", "Afgerond via Telegram.")
        runBlocking { withContext(dispatcher) { engine.reviewBackground("s_stopped", "Rond af") } }
        val after = flow.await { !it.reviewing && it.messages.last().text == "Afgerond via Telegram." }
        assertFalse(after.awaitingReview)
        assertEquals(1, runs.get())
    }
}
