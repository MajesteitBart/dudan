package nl.bartvandermeeren.dudan

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import nl.bartvandermeeren.dudan.chat.HistoryMapper
import nl.bartvandermeeren.dudan.chat.MessageState
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.chat.StepKind
import nl.bartvandermeeren.dudan.chat.TurnReducer
import nl.bartvandermeeren.dudan.chat.UiMessage
import nl.bartvandermeeren.dudan.data.AgentEvent
import nl.bartvandermeeren.dudan.data.AgentEventParser
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.HermesJson
import nl.bartvandermeeren.dudan.data.HermesMessage
import nl.bartvandermeeren.dudan.data.ModelChoice
import nl.bartvandermeeren.dudan.data.ReasoningEffort
import nl.bartvandermeeren.dudan.data.RunOutcome
import nl.bartvandermeeren.dudan.data.SseReader
import nl.bartvandermeeren.dudan.ui.chat.prettyModelName
import nl.bartvandermeeren.dudan.ui.settings.isInsecureRemote
import nl.bartvandermeeren.dudan.voice.SpeechText
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseReaderTest {
    @Test
    fun parsesNamedAndUnnamedFramesAndSkipsKeepalives() {
        val raw = ": keepalive\n\n" +
            "event: assistant.delta\ndata: {\"delta\":\"Hoi\"}\n\n" +
            "data: {\"event\":\"message.delta\",\n" +
            "data: \"delta\":\"x\"}\n\n" +
            ": stream closed\n\n"
        val reader = SseReader(Buffer().writeUtf8(raw))
        val first = reader.next()!!
        assertEquals("assistant.delta", first.event)
        assertEquals("{\"delta\":\"Hoi\"}", first.data)
        val second = reader.next()!!
        assertNull(second.event)
        assertEquals("{\"event\":\"message.delta\",\n\"delta\":\"x\"}", second.data)
        assertNull(reader.next())
    }
}

class AgentEventParserTest {
    @Test
    fun mapsRunsStreamEventsFromPayloadName() {
        assertEquals(AgentEvent.TextDelta("Hi"), AgentEventParser.parse(null, """{"event":"message.delta","run_id":"r","delta":"Hi"}"""))
        assertEquals(
            AgentEvent.ToolStarted("terminal", "ls -la"),
            AgentEventParser.parse(null, """{"event":"tool.started","tool":"terminal","preview":"ls -la"}"""),
        )
        assertEquals(
            AgentEvent.ToolFinished("terminal", "exit 1", failed = true, durationSec = 0.4),
            AgentEventParser.parse(null, """{"event":"tool.completed","tool":"terminal","duration":0.4,"error":true,"preview":"exit 1"}"""),
        )
        assertEquals(
            AgentEvent.Finished(RunOutcome.Completed, "Done.", null),
            AgentEventParser.parse(null, """{"event":"run.completed","output":"Done.","usage":{}}"""),
        )
    }

    @Test
    fun mapsSessionStreamEventsFromSseName() {
        assertEquals(AgentEvent.TextDelta("a"), AgentEventParser.parse("assistant.delta", """{"delta":"a","run_id":"r"}"""))
        assertEquals(AgentEvent.Reasoning("hmm"), AgentEventParser.parse("tool.progress", """{"tool_name":"_thinking","delta":"hmm"}"""))
        assertEquals(AgentEvent.RunStarted("run_1"), AgentEventParser.parse("run.started", """{"run_id":"run_1"}"""))
        assertEquals(AgentEvent.FinalText("Answer"), AgentEventParser.parse("assistant.completed", """{"content":"Answer"}"""))
        assertNull(AgentEventParser.parse("tool.progress", """{"tool_name":"web_search","delta":"x"}"""))
    }

    @Test
    fun parsesApprovalRequests() {
        val event = AgentEventParser.parse(
            null,
            """{"event":"approval.request","run_id":"run_9","command":"rm -rf x","description":"danger","choices":["once","deny"]}""",
        ) as AgentEvent.ApprovalRequested
        assertEquals("run_9", event.request.runId)
        assertEquals(listOf("once", "deny"), event.request.choices)
    }
}

class TurnReducerTest {
    private val start = UiMessage("a", Role.Assistant, state = MessageState.Streaming, startedAtMs = 0)

    private fun reduce(vararg events: AgentEvent) = events.fold(start) { m, e -> TurnReducer.apply(m, e, 1000) }

    @Test
    fun buildsTextAndSteps() {
        val m = reduce(
            AgentEvent.ToolStarted("web_search", "q"),
            AgentEvent.ToolFinished("web_search", "3 results", false, 1.2),
            AgentEvent.TextDelta("Hel"),
            AgentEvent.TextDelta("lo"),
            AgentEvent.Finished(RunOutcome.Completed, "Hello", null),
        )
        assertEquals("Hello", m.text)
        assertEquals(MessageState.Done, m.state)
        assertEquals(1, m.steps.size)
        assertFalse(m.steps[0].running)
        assertEquals(1.2, m.steps[0].durationSec!!, 0.001)
        assertEquals(1000L, m.workedMs)
    }

    @Test
    fun movesStreamedCommentaryOutOfTheAnswer() {
        val m = reduce(
            AgentEvent.TextDelta("Let me check. "),
            AgentEvent.Commentary("Let me check.", alreadyStreamed = true),
            AgentEvent.TextDelta("It is 12°C."),
        )
        assertEquals("It is 12°C.", m.text)
        assertEquals(StepKind.Commentary, m.steps.single().kind)
    }

    @Test
    fun interruptedRunWithoutTextFails() {
        val m = reduce(AgentEvent.Finished(RunOutcome.Interrupted, null, "Gateway shutdown interrupted the run."))
        assertEquals(MessageState.Failed, m.state)
        assertEquals("Gateway shutdown interrupted the run.", m.error)
    }

    @Test
    fun failedRunWithPartialTextStaysFailed() {
        val m = reduce(AgentEvent.TextDelta("Eerste zin."), AgentEvent.Finished(RunOutcome.Failed, null, "provider error"))
        assertEquals(MessageState.Failed, m.state)
        assertEquals("Eerste zin.", m.text)
        assertEquals("provider error", m.error)
    }

    @Test
    fun approvalResolutionOnlyClearsTheMatchingRequest() {
        val first = nl.bartvandermeeren.dudan.data.ApprovalRequest("run", "rm a", null, listOf("once"), requestId = "a")
        val second = first.copy(command = "rm b", requestId = "b")
        val m = reduce(AgentEvent.ApprovalRequested(second), AgentEvent.ApprovalResolved("a"))
        assertEquals(second, m.approval)
        assertNull(reduce(AgentEvent.ApprovalRequested(second), AgentEvent.ApprovalResolved("b")).approval)
    }

    @Test
    fun cancelledKeepsPartialText() {
        val m = reduce(AgentEvent.TextDelta("Half"), AgentEvent.Finished(RunOutcome.Cancelled, null, null))
        assertEquals(MessageState.Cancelled, m.state)
        assertEquals("Half", m.text)
    }
}

class HistoryMapperTest {
    private fun msg(json: String) = HermesMessage.from(HermesJson.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)

    @Test
    fun foldsToolCallsIntoTheNextAnswer() {
        val messages = listOf(
            msg("""{"id":1,"role":"user","content":"Weer?","timestamp":100.0}"""),
            msg("""{"id":2,"role":"assistant","content":"Even kijken.","tool_calls":[{"id":"c1","function":{"name":"web_search","arguments":"{\"query\":\"weer utrecht\"}"}}],"timestamp":101.0}"""),
            msg("""{"id":3,"role":"tool","tool_call_id":"c1","content":"12 graden","timestamp":103.0}"""),
            msg("""{"id":4,"role":"assistant","content":"Het is 12 graden.","timestamp":110.5}"""),
            msg("""{"id":5,"role":"system","content":"hidden","display_kind":"hidden"}"""),
        )
        val ui = HistoryMapper.map(messages)
        assertEquals(2, ui.size)
        assertEquals(Role.User, ui[0].role)
        val answer = ui[1]
        assertEquals("Het is 12 graden.", answer.text)
        assertEquals(listOf(StepKind.Commentary, StepKind.Tool), answer.steps.map { it.kind })
        assertEquals("weer utrecht", answer.steps[1].detail)
        assertEquals("12 graden", answer.steps[1].result)
        assertEquals(10_500L, answer.workedMs)
    }

    @Test
    fun readsMultimodalUserContent() {
        val ui = HistoryMapper.map(
            listOf(msg("""{"role":"user","content":[{"type":"text","text":"Wat is dit?"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAA"}}]}""")),
        )
        assertEquals("Wat is dit?", ui.single().text)
        assertEquals("data:image/jpeg;base64,AAA", ui.single().images.single().source)
    }

    @Test
    fun keepsUnfinishedWorkVisible() {
        val ui = HistoryMapper.map(
            listOf(
                msg("""{"role":"user","content":"Doe iets"}"""),
                msg("""{"role":"assistant","content":"","tool_calls":[{"id":"c","function":{"name":"terminal","arguments":"{\"command\":\"make\"}"}}]}"""),
            ),
        )
        assertEquals(2, ui.size)
        assertEquals("make", ui[1].steps.single().detail)
    }
}

class HermesApiTest {
    @Test
    fun startsRunAndStreamsEvents() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run_1","status":"started"}"""))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                ": keepalive\n\n" +
                    "data: {\"event\":\"tool.started\",\"tool\":\"web_search\",\"preview\":\"q\"}\n\n" +
                    "data: {\"event\":\"message.delta\",\"delta\":\"Hoi\"}\n\n" +
                    "data: {\"event\":\"run.completed\",\"output\":\"Hoi\"}\n\n",
            ),
        )
        server.start()
        val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "secret-key") }
        val runId = api.startRun("dudan_1", "Hallo", ModelChoice(provider = "openrouter", model = "x/y", effort = ReasoningEffort.High, fast = true))
        assertEquals("run_1", runId)
        val start = server.takeRequest()
        assertEquals("/v1/runs", start.path)
        assertEquals("Bearer secret-key", start.getHeader("Authorization"))
        val body = HermesJson.parseToJsonElement(start.body.readUtf8()) as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("dudan_1"), body["session_id"])
        assertEquals(JsonPrimitive("openrouter"), body["provider"])
        assertEquals("""{"reasoning_effort":"high","fast":true}""", body["model_options"].toString())

        val events = api.runEvents(runId).toList()
        assertEquals("/v1/runs/run_1/events", server.takeRequest().path)
        assertEquals(
            listOf(
                AgentEvent.ToolStarted("web_search", "q"),
                AgentEvent.TextDelta("Hoi"),
                AgentEvent.Finished(RunOutcome.Completed, "Hoi", null),
            ),
            events,
        )
        server.shutdown()
    }

    @Test
    fun surfacesHermesErrors() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key","code":"invalid_api_key"}}"""))
        server.start()
        val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/v1").toString(), "bad") }
        val error = runCatching { api.listSessions("api_server") }.exceptionOrNull() as HermesApi.HermesException
        assertEquals(401, error.status)
        assertEquals("Invalid API key", error.message)
        // A base URL ending in /v1 must not produce /v1/api/sessions.
        assertEquals("/api/sessions?limit=100&source=api_server", server.takeRequest().path)
        server.shutdown()
    }
}

class SmallHelpersTest {
    @Test
    fun prettyModelNames() {
        assertEquals("Opus 4.6", prettyModelName("anthropic/claude-opus-4.6"))
        assertEquals("GPT-5.5", prettyModelName("openai/gpt-5.5"))
        assertEquals("Gemini 3 Flash", prettyModelName("google/gemini-3-flash"))
        assertEquals("Hermes 4 405B", prettyModelName("hermes-4-405b"))
        assertEquals("Sonnet 4", prettyModelName("claude-sonnet-4-20250514"))
    }

    @Test
    fun speechTextDropsMarkdown() {
        val text = SpeechText.fromMarkdown("## Titel\n- **Vet** en [link](https://x.nl)\n\n```kotlin\ncode()\n```\nKlaar `nu`.")
        assertEquals("Titel\nVet en link\n\n \nKlaar nu.", text)
        assertEquals("nl-NL", SpeechText.guessLanguage("Ik heb de afspraak voor je in de agenda gezet en het is niet druk."))
        assertEquals("en-US", SpeechText.guessLanguage("I put the meeting in your calendar and it is not busy."))
    }

    @Test
    fun flagsPlainHttpOutsideTheTailnet() {
        assertFalse(isInsecureRemote("http://my-server:8642"))
        assertFalse(isInsecureRemote("http://100.88.12.34:8642"))
        assertFalse(isInsecureRemote("http://my-server.tail1234.ts.net:8642"))
        assertFalse(isInsecureRemote("https://hermes.example.com"))
        assertTrue(isInsecureRemote("http://hermes.example.com"))
        assertTrue(isInsecureRemote("192.168.1.10:8642"))
    }
}
