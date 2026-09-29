package nl.bartvandermeeren.dudan

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.bartvandermeeren.dudan.chat.ChatEngine
import nl.bartvandermeeren.dudan.chat.Conversation
import nl.bartvandermeeren.dudan.chat.MessageState
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
        engine = ChatEngine(HermesApi(OkHttpClient()) { settings.server }, scope) { settings }
    }

    @After
    fun tearDown() {
        scope.cancel()
        dispatcher.close()
        server.shutdown()
    }

    private fun script(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                return when {
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
        whileRunning: (String) -> Unit = {},
    ): UiMessage = runBlocking {
        val id = withContext(dispatcher) { engine.startNew(profile).also { engine.send(it, text) } }
        whileRunning(id)
        withTimeout(20_000) {
            val flow = withContext(dispatcher) { engine.conversation(id) }
            flow.first { c: Conversation -> c.messages.lastOrNull()?.let { !it.isStreaming } == true }.messages.last()
        }
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
    }
}
