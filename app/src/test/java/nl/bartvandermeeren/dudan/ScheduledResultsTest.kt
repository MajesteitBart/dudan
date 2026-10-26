package nl.bartvandermeeren.dudan

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import nl.bartvandermeeren.dudan.chat.Conversation
import nl.bartvandermeeren.dudan.chat.HistoryMapper
import nl.bartvandermeeren.dudan.chat.MessageState
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.chat.ScheduledLabel
import nl.bartvandermeeren.dudan.chat.StepKind
import nl.bartvandermeeren.dudan.chat.UiMessage
import nl.bartvandermeeren.dudan.data.AppVisibility
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.HermesJson
import nl.bartvandermeeren.dudan.data.HermesMessage
import nl.bartvandermeeren.dudan.data.ScheduledCatchUp
import nl.bartvandermeeren.dudan.data.ScheduledRef
import nl.bartvandermeeren.dudan.data.SeenMarks
import nl.bartvandermeeren.dudan.data.SessionSummary
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rows the way Clark writes a scheduled task's result into a dudan chat (src/channels/dudan/sessions.ts). */
internal object ScheduledRows {
    val file = """{"version":1,"session_id":"task-brief","input_id":"result-41","index":0,"name":"agenda.pdf","mime_type":"application/pdf","size":12,"sha256":"${"b".repeat(64)}"}"""

    fun row(json: String) = HermesMessage.from(HermesJson.parseToJsonElement(json).jsonObject)

    fun result(id: Int, text: String, timestamp: Double, files: String = "") = row(
        """{"id":"$id","role":"assistant","content":"$text","display_kind":"scheduled_result",
           "scheduled":{"job_id":"brief","title":"Morning brief"},"attachments":[$files],"timestamp":$timestamp}""",
    )
}

class ScheduledHistoryTest {
    private fun row(json: String) = ScheduledRows.row(json)

    @Test
    fun aScheduledRowIsAnAnswerWithItsLabelAndFiles() {
        val row = ScheduledRows.result(41, "Three meetings today.", 1_000.5, ScheduledRows.file)
        assertEquals(ScheduledRef("brief", "Morning brief"), row.scheduled)
        assertTrue(row.isScheduledResult)
        val item = HistoryMapper.map(listOf(row)).single()
        assertEquals(Role.Assistant, item.role)
        assertEquals("h_41", item.id)
        assertEquals("Three meetings today.", item.text)
        assertEquals(ScheduledLabel("Morning brief", 1_000_500), item.scheduled)
        assertEquals(1_000_500L, item.finishedAtMs)
        assertEquals("agenda.pdf", item.attachments.single().name)
        assertTrue(item.steps.isEmpty())

        // Only files, and a task without a name: the label falls back to the id.
        val bare = row("""{"id":"42","role":"assistant","content":"","display_kind":"scheduled_result","scheduled":{"job_id":"brief","title":null},"attachments":[${ScheduledRows.file.replace("result-41", "result-42")}]}""")
        val files = HistoryMapper.map(listOf(bare)).single()
        assertEquals(ScheduledLabel("brief", null), files.scheduled)
        assertEquals("", files.text)
        // A plain answer, or Hermes, has no label.
        assertNull(HistoryMapper.map(listOf(row("""{"id":"1","role":"assistant","content":"Hi"}"""))).single().scheduled)
        assertNull(row("""{"id":"1","role":"user","content":"Hi","display_kind":"scheduled_result"}""").scheduled)
    }

    @Test
    fun aResultInTheMiddleOfATurnLeavesTheTurnsWorkWithItsAnswer() {
        val ui = HistoryMapper.map(
            listOf(
                row("""{"id":"1","role":"user","content":"Weer?","timestamp":100.0}"""),
                row("""{"id":"2","role":"assistant","content":"Even kijken.","reasoning":"Look it up.","tool_calls":[{"id":"c1","function":{"name":"web_search","arguments":"{\"query\":\"weer\"}"}}],"timestamp":101.0}"""),
                ScheduledRows.result(3, "Three meetings today.", 102.0, ScheduledRows.file),
                row("""{"id":"4","role":"tool","tool_call_id":"c1","content":"12 graden","timestamp":103.0}"""),
                row("""{"id":"5","role":"assistant","content":"Het is 12 graden.","timestamp":110.0}"""),
            ),
        )
        assertEquals(listOf("h_1", "h_3", "h_5"), ui.map { it.id })
        val result = ui[1]
        assertTrue(result.steps.isEmpty() && result.reasoning.isEmpty())
        assertEquals("agenda.pdf", result.attachments.single().name)
        val answer = ui[2]
        assertNull(answer.scheduled)
        assertEquals(listOf(StepKind.Commentary, StepKind.Tool), answer.steps.map { it.kind })
        assertEquals("12 graden", answer.steps[1].result)
        assertEquals("Look it up.", answer.reasoning)
        // The turn still started with its question.
        assertEquals(10_000L, answer.workedMs)
        assertTrue(answer.attachments.isEmpty())
    }

    @Test
    fun aStoppedTurnsWorkStaysAboveResultsThatCameInAfterIt() {
        val stopped = listOf(
            row("""{"id":"1","role":"user","content":"Weer?","timestamp":100.0}"""),
            row("""{"id":"2","role":"assistant","content":"","reasoning":"Look it up.","tool_calls":[{"id":"c1","function":{"name":"web_search","arguments":"{\"query\":\"weer\"}"}}],"timestamp":101.0}"""),
            row("""{"id":"3","role":"tool","tool_call_id":"c1","content":"Tool web_search was aborted","timestamp":102.0}"""),
        )
        val before = HistoryMapper.map(stopped)
        assertEquals(listOf("h_1", "h_work_2"), before.map { it.id })

        // Clark writes a result once the stopped turn has ended, so it follows the turn's tool rows.
        val withResult = stopped + ScheduledRows.result(4, "Three meetings today.", 200.0)
        val ui = HistoryMapper.map(withResult)
        assertEquals(listOf("h_1", "h_work_2", "h_4"), ui.map { it.id })
        val work = ui[1]
        assertEquals(listOf(StepKind.Tool), work.steps.map { it.kind })
        assertEquals("Tool web_search was aborted", work.steps.single().result)
        assertEquals("Look it up.", work.reasoning.trim())
        assertTrue(ui[2].steps.isEmpty() && ui[2].reasoning.isEmpty())

        // More results and the next question leave the work where it is, under the same id.
        val later = withResult + ScheduledRows.result(5, "Two meetings today.", 300.0) +
            row("""{"id":"6","role":"user","content":"En morgen?","timestamp":400.0}""")
        assertEquals(listOf("h_1", "h_work_2", "h_4", "h_5", "h_6"), HistoryMapper.map(later).map { it.id })
    }

    private fun user(id: String, text: String) = UiMessage(id, Role.User, text)
    private fun answer(id: String, text: String, state: MessageState = MessageState.Done) = UiMessage(id, Role.Assistant, text, state = state)
    private fun scheduled(id: Int, text: String) = HistoryMapper.map(listOf(ScheduledRows.result(id, text, 500.0))).single()

    @Test
    fun aResultIsNotTheSavedReplyOfTheTurnItLandedIn() {
        val shown = listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), user("l_3", "Vraag"), answer("l_4", "Antwoord"))
        // The question is saved and a result came in behind it; the reply isn't saved yet.
        val transcript = listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), user("h_3", "Vraag"), scheduled(9, "Rapport"))
        val merged = HistoryMapper.reconcile(shown, transcript)
        assertFalse(merged.caughtUp)
        // The chat keeps its own turn and shows the result after it, once.
        assertEquals(listOf("h_1", "h_2", "l_3", "l_4", "h_9"), merged.messages.map { it.id })
        assertEquals(merged.messages, HistoryMapper.reconcile(merged.messages, transcript).messages)

        // Once the reply is saved, the transcript wins and the streamed reply keeps its id.
        val saved = transcript + answer("h_5", "Antwoord")
        val caughtUp = HistoryMapper.reconcile(merged.messages, saved)
        assertTrue(caughtUp.caughtUp)
        assertEquals(listOf("h_1", "h_2", "h_3", "h_9", "l_4"), caughtUp.messages.map { it.id })
    }

    @Test
    fun aStoppedTurnStillGainsResultsThatComeInLater() {
        val stopped = listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), user("l_3", "Nieuw"), answer("l_4", "Half", MessageState.Cancelled))
        val transcript = listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), scheduled(7, "Rapport"))
        val merged = HistoryMapper.reconcile(stopped, transcript)
        assertFalse(merged.caughtUp)
        assertEquals(listOf("h_1", "h_2", "l_3", "l_4", "h_7"), merged.messages.map { it.id })
        assertEquals(merged.messages, HistoryMapper.reconcile(merged.messages, transcript).messages)
        // The stopped answer still offers Retry.
        assertEquals("l_4", Conversation("s", merged.messages).retryId)
    }

    @Test
    fun aFailedOrStoppedAnswerKeepsRetryUntilSomethingButAResultFollows() {
        val failed = listOf(user("h_1", "Vraag"), answer("l_2", "", MessageState.Failed))
        assertEquals("l_2", Conversation("s", failed).retryId)
        assertEquals("l_2", Conversation("s", failed + scheduled(3, "Rapport") + scheduled(4, "Nog een")).retryId)
        // A finished answer, a new question or a background result takes it away.
        assertNull(Conversation("s", listOf(user("h_1", "Vraag"), answer("l_2", "Antwoord"))).retryId)
        assertNull(Conversation("s", failed + user("l_3", "Nieuw")).retryId)
        assertNull(Conversation("s", failed + UiMessage("h_5", Role.Background, "Klaar")).retryId)
        // Nothing was asked: a chat with only results has nothing to retry.
        assertNull(Conversation("s", listOf(scheduled(3, "Rapport"))).retryId)
    }

    @Test
    fun aResultAfterABackgroundResultLeavesItWaitingForReview() {
        val started = listOf(Deliveries.row(1, "user", "Zoek vluchten"), Deliveries.row(2, "assistant", "Gestart."), Deliveries.delivery(3, Deliveries.single("deleg_1")))
        val one = HistoryMapper.map(started + ScheduledRows.result(4, "Rapport", 500.0))
        assertEquals(listOf(Role.User, Role.Assistant, Role.Background, Role.Assistant), one.map { it.role })
        assertTrue(Conversation("s", one).awaitingReview)
        // Review result and finish stays on the background result, however many results follow it.
        assertEquals("h_3", Conversation("s", one).reviewId)
        val two = HistoryMapper.map(started + ScheduledRows.result(4, "Rapport", 500.0) + ScheduledRows.result(5, "Nog een", 600.0))
        assertTrue(Conversation("s", two).awaitingReview)
        assertEquals("h_3", Conversation("s", two).reviewId)

        // An ordinary turn still settles it, also with another result after that turn.
        val answered = two + user("l_6", "Wat nu?") + answer("l_7", "Niets meer te doen.")
        assertFalse(Conversation("s", answered).awaitingReview)
        assertNull(Conversation("s", answered).reviewId)
        assertFalse(Conversation("s", answered + scheduled(8, "Rapport")).awaitingReview)
    }

    @Test
    fun aResultAmongABatchsResultsLeavesTheBatchWaitingForItsEnd() {
        val notice = listOf(
            Deliveries.row(1, "user", "Check alles"), Deliveries.row(2, "assistant", "Gestart."),
            Deliveries.delivery(3, Deliveries.taskFailed("deleg_b")), ScheduledRows.result(4, "Rapport", 500.0),
        )
        // An early failure notice alone still waits for the rest of its batch.
        assertFalse(Conversation("s", HistoryMapper.map(notice)).awaitingReview)
        val done = Conversation("s", HistoryMapper.map(notice + Deliveries.delivery(5, Deliveries.batch("deleg_b")) + ScheduledRows.result(6, "Nog een", 600.0)))
        assertTrue(done.awaitingReview)
        assertEquals("h_5", done.reviewId)
    }

    @Test
    fun aStreamedReplyNeverLendsItsIdToAResultWithTheSameText() {
        val shown = listOf(user("l_1", "Vraag"), answer("l_2", "OK"))
        val transcript = listOf(user("h_1", "Vraag"), answer("h_2", "OK"), scheduled(3, "OK"))
        val merged = HistoryMapper.reconcile(shown, transcript).messages
        assertEquals(listOf("h_1", "l_2", "h_3"), merged.map { it.id })
        assertEquals(ScheduledLabel("Morning brief", 500_000), merged[2].scheduled)
    }

    @Test
    fun sessionsAndJobsReadClarksNewFields() {
        val task = SessionSummary.from(HermesJson.parseToJsonElement("""{"id":"task-brief","title":"Morning brief","last_active":1000.5,"job_id":"brief"}""").jsonObject)!!
        assertEquals("brief", task.jobId)
        val chat = SessionSummary.from(HermesJson.parseToJsonElement("""{"id":"dudan_1","title":"Chat","job_id":null}""").jsonObject)!!
        assertNull(chat.jobId)
        // Hermes sends no job_id at all.
        assertNull(SessionSummary.from(HermesJson.parseToJsonElement("""{"id":"20260101_x","source":"api_server"}""").jsonObject)!!.jobId)
    }
}

class ScheduledCatchUpTest {
    private fun session(id: String, lastActive: Double?, jobId: String? = id.removePrefix("task-"), title: String? = "Morning brief") =
        SessionSummary(id, title, null, "dudan", null, lastActive, false, 0, jobId)

    private fun reply(id: Int, timestamp: Double) = ScheduledRows.row("""{"id":"$id","role":"assistant","content":"Hi","timestamp":$timestamp}""")

    @Test
    fun theFirstRoundCountsEverythingAsSeenByTheServersClock() {
        val sessions = listOf(session("task-brief", 900.0), session("task-late", 1_050.5), session("dudan_1", 990.0, jobId = null))
        // The Date header counts whole seconds, so activity in the same second can be later.
        val now = ScheduledCatchUp.serverNow(sessions, dateSeconds = 1_050.0)!!
        assertEquals(1_050.5, now, 0.0)
        val seen = ScheduledCatchUp.start("http://clark", now)
        assertEquals(1_050.5, seen.since, 0.0)
        assertTrue(seen.marks.isEmpty())
        assertTrue(ScheduledCatchUp.due(sessions, seen).isEmpty())
        // A result that lands after the first round is reported, however far the phone's clock is ahead.
        val later = listOf(session("task-brief", 1_051.0)) + sessions.drop(1)
        assertEquals(listOf("task-brief"), ScheduledCatchUp.due(later, seen).map { it.id })
        assertEquals(listOf("2"), ScheduledCatchUp.fresh(listOf(ScheduledRows.result(1, "Old", 900.0), ScheduledRows.result(2, "New", 1_051.0)), seen.markFor("task-brief")).map { it.id })

        // The header alone, or the activity alone, is enough; with neither there is no round.
        assertEquals(2_000.0, ScheduledCatchUp.serverNow(sessions, dateSeconds = 2_000.0)!!, 0.0)
        assertEquals(1_050.5, ScheduledCatchUp.serverNow(sessions, dateSeconds = null)!!, 0.0)
        assertEquals(500.0, ScheduledCatchUp.serverNow(emptyList(), dateSeconds = 500.0)!!, 0.0)
        assertNull(ScheduledCatchUp.serverNow(listOf(session("x", null)), dateSeconds = null))
    }

    @Test
    fun theListingCarriesTheServersClock() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Date", "Mon, 26 Oct 2026 07:00:00 GMT")
                .setBody("""{"object":"list","data":[{"id":"task-brief","last_active":1792997990.25,"job_id":"brief"}]}"""),
        )
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[]}"""))
        server.start()
        val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "secret") }
        val listing = api.sessionListing(source = "api_server")
        assertEquals(1_792_998_000.0, listing.serverSeconds!!, 0.0)
        assertEquals("brief", listing.sessions.single().jobId)
        assertEquals("/api/sessions?limit=100&source=api_server", server.takeRequest().path)
        // MockWebServer, like a server without the header, sends none.
        assertNull(api.sessionListing(source = "api_server").serverSeconds)
        server.shutdown()
    }

    @Test
    fun onlyTheChatOnScreenCountsAsShown() {
        val before = Triple(AppVisibility.activityResumed, AppVisibility.chatOnScreen, AppVisibility.openSessionId)
        try {
            AppVisibility.activityResumed = true
            AppVisibility.openSessionId = "task-brief"
            AppVisibility.chatOnScreen = true
            assertTrue(AppVisibility.shows("task-brief"))
            assertFalse(AppVisibility.shows("task-weekly"))
            // The task list or Settings covers the chat.
            AppVisibility.chatOnScreen = false
            assertFalse(AppVisibility.shows("task-brief"))
            AppVisibility.chatOnScreen = true
            AppVisibility.activityResumed = false
            assertFalse(AppVisibility.shows("task-brief"))
        } finally {
            AppVisibility.activityResumed = before.first
            AppVisibility.chatOnScreen = before.second
            AppVisibility.openSessionId = before.third
        }
    }

    @Test
    fun onlyTaskChatsWithSomethingAfterTheirMarkAreRead() {
        val seen = SeenMarks("http://clark", since = 1_000.0, marks = mapOf("task-brief" to 1_200.0))
        val sessions = listOf(
            session("task-brief", 1_200.0),
            session("task-weekly", 1_300.0),
            // A task chat Clark made after the first round counts from then.
            session("task-new", 1_001.0),
            session("task-old", 999.0),
            // A chat without a job id, such as every chat on stock Hermes, is never read.
            session("dudan_1", 5_000.0, jobId = null),
        )
        assertEquals(listOf("task-weekly", "task-new"), ScheduledCatchUp.due(sessions, seen).map { it.id })
        assertEquals(1_000.0, seen.markFor("task-new"), 0.0)
    }

    @Test
    fun onlyResultsAfterTheMarkAreNew() {
        val rows = listOf(
            ScheduledRows.result(1, "Old", 1_000.0),
            reply(2, 1_100.0),
            ScheduledRows.result(4, "Newest", 1_300.0),
            ScheduledRows.result(3, "New", 1_200.0),
        )
        assertEquals(listOf("3", "4"), ScheduledCatchUp.fresh(rows, mark = 1_000.0).map { it.id })
        assertTrue(ScheduledCatchUp.fresh(rows, mark = 1_300.0).isEmpty())
    }

    @Test
    fun aChatCountsAsSeenUpToItsNewestRowOnceThatCaughtUpWithItsActivity() {
        val rows = listOf(ScheduledRows.result(3, "New", 1_200.0))
        assertEquals(1_200.0, ScheduledCatchUp.advance(1_000.0, lastActive = 1_150.0, rows, nowSeconds = 1_300.0), 0.0)
        assertEquals(1_250.0, ScheduledCatchUp.advance(1_000.0, lastActive = 1_250.0, rows, nowSeconds = 2_000.0), 0.0)
        // Clark marked the chat active but hasn't written the result yet: look again next round.
        assertEquals(1_200.0, ScheduledCatchUp.advance(1_000.0, lastActive = 1_250.0, rows, nowSeconds = 1_260.0), 0.0)
        assertEquals(1_000.0, ScheduledCatchUp.advance(1_000.0, lastActive = 1_250.0, emptyList(), nowSeconds = 1_260.0), 0.0)
        // Never back.
        assertEquals(1_500.0, ScheduledCatchUp.advance(1_500.0, lastActive = 1_250.0, rows, nowSeconds = 1_260.0), 0.0)
    }

    @Test
    fun marksOnlyMoveForwardAndSurviveARoundTrip() {
        val seen = SeenMarks("http://clark:8652", since = 1_000.25, marks = mapOf("task-brief" to 1_200.5))
        assertEquals(seen, SeenMarks.decode(seen.encode()))
        assertEquals(seen, seen.seen("task-brief", 1_100.0))
        assertEquals(1_300.0, seen.seen("task-brief", 1_300.0).markFor("task-brief"), 0.0)
        assertEquals(seen, seen.seen("bad\tid", 2_000.0))
        assertNull(SeenMarks.decode(null))
        assertNull(SeenMarks.decode("http://clark"))
        assertEquals(SeenMarks("s", 5.0), SeenMarks.decode("s\n5.0\nbroken line\n\tno id"))
    }

    @Test
    fun aChatThatLosesItsMarkDoesNotReportOldResultsAgain() {
        var seen = SeenMarks("s", since = 0.0)
        (1..SeenMarks.LIMIT + 2).forEach { seen = seen.seen("task-$it", it.toDouble()) }
        assertEquals(SeenMarks.LIMIT, seen.marks.size)
        assertNull(seen.marks["task-1"])
        assertEquals(2.0, seen.since, 0.0)
        assertEquals(2.0, seen.markFor("task-1"), 0.0)
    }

    @Test
    fun aNotificationNamesTheTaskAndShowsTheStartOfTheResult() {
        val long = "**Agenda**: " + "word ".repeat(80)
        val row = ScheduledRows.result(1, long, 1.0)
        val text = ScheduledCatchUp.preview(row)
        assertEquals(ScheduledCatchUp.PREVIEW_CHARS, text.length)
        assertTrue(text.startsWith("Agenda: word"))
        assertTrue(text.endsWith("…"))
        assertEquals("Morning brief", ScheduledCatchUp.title(row, session("task-brief", 1.0, title = "Other")))
        val unnamed = ScheduledRows.row("""{"id":"1","role":"assistant","content":"","display_kind":"scheduled_result","scheduled":{"job_id":"brief"}}""")
        assertEquals("", ScheduledCatchUp.preview(unnamed))
        assertEquals("Chat title", ScheduledCatchUp.title(unnamed, session("task-brief", 1.0, title = "Chat title")))
        assertEquals("brief", ScheduledCatchUp.title(unnamed, session("task-brief", 1.0, title = null)))
    }
}
