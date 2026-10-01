package nl.bartvandermeeren.dudan

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import nl.bartvandermeeren.dudan.chat.BackgroundStatus
import nl.bartvandermeeren.dudan.chat.BackgroundWork
import nl.bartvandermeeren.dudan.chat.HistoryMapper
import nl.bartvandermeeren.dudan.chat.MessageState
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.chat.UiMessage
import nl.bartvandermeeren.dudan.data.DeliveryAcks
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.HermesMessage
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transcript rows the way Hermes writes detached delegate_task work (tools/process_registry_notifications.py). */
internal object Deliveries {
    fun single(id: String, status: String = "completed", result: String = "De goedkoopste vlucht is KLM om 12:00, €89.") = """
        [ASYNC DELEGATION COMPLETE — $id]
        A background subagent you dispatched earlier has finished. You may have moved on since dispatching it; the full task source is below so you can act on the result or re-dispatch if things have changed.

        Dispatched: 2026-09-30 10:00:00 (4m ago)
        Original goal: Zoek de goedkoopste vlucht
        Role: leaf   Model: x/y
        Status: $status   API calls: 7   Duration: 240s
        --- RESULT ---
        $result
    """.trimIndent()

    fun batch(id: String) = """
        [ASYNC DELEGATION BATCH COMPLETE — $id]
        A background fan-out unit you dispatched earlier — 2 subagent(s) — has finished; its consolidated results are below.

        Dispatched: 2026-09-30 10:00:00 (5m ago)
        Role: leaf   Model: x/y   Total duration: 300s

        --- ✓ TASK 1/2: Prijzen checken  (status=completed, 120s) ---
        Prijzen kloppen.

        --- ✗ TASK 2/2: Hotels checken  (status=failed, 30s) ---
        (no summary — status=failed: timeout)
    """.trimIndent()

    fun taskFailed(id: String) = """
        [ASYNC DELEGATION TASK FAILED — $id, task 2/2]
        One subagent in a background fan-out you dispatched has failed while its siblings are still running.
        Task: Hotels checken
        Status: failed   Duration: 30s
        Error: timeout
    """.trimIndent()

    fun dispatched(id: String) =
        """{"status":"dispatched","mode":"background","count":1,"delegation_id":"$id","goals":["Zoek vluchten"],"note":"Results are delivered only after you END YOUR TURN."}"""

    fun row(id: Long, role: String, content: String, displayKind: String? = null, timestamp: Double? = null) =
        HermesMessage(id.toString(), role, JsonPrimitive(content), emptyList(), null, null, timestamp, displayKind, null)

    fun delivery(id: Long, content: String) = row(id, "user", content, BackgroundWork.DISPLAY_KIND)
}

class BackgroundWorkTest {
    @Test
    fun readsASingleResult() {
        val result = BackgroundWork.parse(Deliveries.delivery(9, Deliveries.single("deleg_1")))
        assertEquals("deleg_1", result.delegationId)
        assertEquals("deleg_1", result.key)
        assertEquals(BackgroundStatus.Completed, result.status)
        assertFalse(result.interim)
        assertEquals("De goedkoopste vlucht is KLM om 12:00, €89.", result.summary)

        val failed = BackgroundWork.parse(Deliveries.delivery(10, Deliveries.single("deleg_2", status = "timeout", result = "The subagent did not complete successfully (status=timeout).")))
        assertEquals(BackgroundStatus.Failed, failed.status)
    }

    @Test
    fun readsAPartlyFailedBatchAndAnEarlyFailureNotice() {
        val batch = BackgroundWork.parse(Deliveries.delivery(11, Deliveries.batch("deleg_b")))
        assertEquals(BackgroundStatus.PartlyFailed, batch.status)
        assertEquals(2, batch.taskCount)
        assertEquals(1, batch.failedCount)
        assertTrue(batch.summary, batch.summary.startsWith("--- ✓ TASK 1/2"))

        val notice = BackgroundWork.parse(Deliveries.delivery(12, Deliveries.taskFailed("deleg_b")))
        assertTrue(notice.interim)
        assertEquals("deleg_b", notice.delegationId)
        // The notice must not take the identity of the batch's own results.
        assertEquals("deleg_b#task2", notice.key)
    }

    @Test
    fun recognisesDeliveriesWithoutDisplayKindButNeverOrdinaryUserText() {
        assertTrue(BackgroundWork.isDelivery(Deliveries.row(1, "user", Deliveries.single("d"))))
        assertFalse(BackgroundWork.isDelivery(Deliveries.row(2, "user", "Wat is het weer?")))
        assertFalse(BackgroundWork.isDelivery(Deliveries.row(3, "assistant", Deliveries.single("d"))))
        // An unknown format still counts when Hermes marks it, and falls back to the row id.
        val unknown = BackgroundWork.parse(Deliveries.delivery(4, "Worker finished"))
        assertEquals("row:4", unknown.key)
        assertEquals("Worker finished", unknown.summary)
    }

    @Test
    fun findsWorkThatIsStillOut() {
        val units = """{"status":"dispatched","mode":"background","count":3,"delegation_id":"live","units":[{"delegation_id":"u1"},{"delegation_id":"u2"}]}"""
        assertEquals(listOf("u1", "u2"), BackgroundWork.dispatchedIds(Deliveries.row(1, "tool", units)))
        assertEquals(emptyList<String>(), BackgroundWork.dispatchedIds(Deliveries.row(2, "tool", """{"results":[{"status":"completed"}]}""")))
        // Hermes shortens long tool results, which breaks the JSON but keeps the ids.
        assertEquals(listOf("d9"), BackgroundWork.dispatchedIds(Deliveries.row(3, "tool", """{"status": "dispatched", "delegation_id": "d9", "note": "Resu…""")))

        val rows = listOf(
            Deliveries.row(1, "tool", Deliveries.dispatched("old"), timestamp = 100.0),
            Deliveries.row(2, "tool", Deliveries.dispatched("done"), timestamp = 2_000.0),
            Deliveries.row(3, "tool", Deliveries.dispatched("batch"), timestamp = 2_000.0),
            Deliveries.row(4, "tool", Deliveries.dispatched("open"), timestamp = 2_000.0),
            Deliveries.delivery(5, Deliveries.single("done")),
            Deliveries.delivery(6, Deliveries.taskFailed("batch")),
        )
        assertEquals(setOf("batch", "open"), BackgroundWork.outstanding(rows, sinceSeconds = 1_000.0))
    }
}

class BackgroundHistoryTest {
    @Test
    fun deliveriesBecomeBackgroundItemsNotUserSpeech() {
        val ui = HistoryMapper.map(
            listOf(
                Deliveries.row(1, "user", "Zoek vluchten", timestamp = 100.0),
                Deliveries.row(2, "assistant", "Ik heb een subagent op pad gestuurd.", timestamp = 101.0),
                Deliveries.delivery(3, Deliveries.single("deleg_1")),
                // Compression can leave a copy of the same delivery under a new row id.
                Deliveries.delivery(4, Deliveries.single("deleg_1")),
                Deliveries.row(5, "user", "", "hidden"),
            ),
        )
        assertEquals(listOf(Role.User, Role.Assistant, Role.Background), ui.map { it.role })
        assertEquals("h_3", ui[2].id)
        assertEquals(BackgroundStatus.Completed, ui[2].background?.status)
        assertEquals(1, ui.count { it.role == Role.User })
    }

    @Test
    fun theIssueFixtureIsAttributedToBackgroundWork() {
        val row = HermesMessage.from(
            nl.bartvandermeeren.dudan.data.HermesJson.parseToJsonElement(
                """{"id":3,"role":"user","content":"Worker finished","display_kind":"async_delegation_complete"}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        val item = HistoryMapper.map(listOf(row)).single()
        assertEquals(Role.Background, item.role)
        assertEquals("Worker finished", item.text)
    }

    private fun user(id: String, text: String) = UiMessage(id, Role.User, text)
    private fun answer(id: String, text: String, state: MessageState = MessageState.Done) = UiMessage(id, Role.Assistant, text, state = state)
    private fun result(id: String) = HistoryMapper.map(listOf(Deliveries.delivery(id.removePrefix("h_").toLong(), Deliveries.single("d$id")))).single()

    @Test
    fun theTranscriptWinsOnceItHoldsTheLastLocalTurn() {
        val shown = listOf(user("l_1", "Vraag"), answer("l_2", "Antwoord"))
        val transcript = listOf(user("h_1", "Vraag"), UiMessage("h_work", Role.Assistant), answer("h_2", "Antwoord"), result("h_3"))
        val merged = HistoryMapper.reconcile(shown, transcript)
        assertTrue(merged.caughtUp)
        // The streamed reply keeps its id, so read-aloud keeps its stop button; the question takes Hermes' id.
        assertEquals(listOf("h_1", "h_work", "l_2", "h_3"), merged.messages.map { it.id })
        assertEquals(transcript.map { it.copy(id = "") }, merged.messages.map { it.copy(id = "") })
        // And keeps it on the next refresh.
        assertEquals(merged.messages, HistoryMapper.reconcile(merged.messages, transcript).messages)
        // A chat that came from the transcript takes the fresh one as it is.
        assertEquals(transcript, HistoryMapper.reconcile(transcript.take(2), transcript).messages)

        // Turns another client added after this app's turn don't hide it.
        val local = listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), user("l_3", "Vraag"), answer("l_4", "Antwoord"))
        val later = listOf(
            user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), user("h_3", "Vraag"), answer("h_4", "Antwoord"),
            user("h_5", "Via Telegram"), answer("h_6", "Telegram-antwoord"),
        )
        assertEquals(listOf("h_1", "h_2", "h_3", "l_4", "h_5", "h_6"), HistoryMapper.reconcile(local, later).messages.map { it.id })
    }

    @Test
    fun aStreamedReplyKeepsItsIdWithinItsOwnTurnOnly() {
        // Another client later got a reply with the same text; the id stays on this app's reply.
        val shown = listOf(user("l_1", "Vraag"), answer("l_2", "OK"))
        val transcript = listOf(user("h_1", "Vraag"), answer("h_2", "OK"), user("h_3", "Nog een"), answer("h_4", "OK"))
        val merged = HistoryMapper.reconcile(shown, transcript).messages
        assertEquals(listOf("h_1", "l_2", "h_3", "h_4"), merged.map { it.id })
        assertEquals(merged, HistoryMapper.reconcile(merged, transcript).messages)
    }

    @Test
    fun everyStreamedReplyKeepsItsIdWhenSeveralTurnsCatchUpAtOnce() {
        val shown = listOf(user("l_1", "Vraag 1"), answer("l_2", "Antwoord 1"), user("l_3", "Vraag 2"), answer("l_4", "Antwoord 2"))
        val transcript = listOf(user("h_1", "Vraag 1"), answer("h_2", "Antwoord 1"), user("h_3", "Vraag 2"), answer("h_4", "Antwoord 2"))
        val merged = HistoryMapper.reconcile(shown, transcript).messages
        assertEquals(listOf("h_1", "l_2", "h_3", "l_4"), merged.map { it.id })
        assertEquals(merged, HistoryMapper.reconcile(merged, transcript).messages)
    }

    @Test
    fun aResultFromBeforeAStoppedTurnGoesBeforeIt() {
        val stopped = listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), user("l_3", "Nieuw"), answer("l_4", "Half", MessageState.Cancelled))
        // The result landed just before the user sent "Nieuw", which Hermes saved before the stop.
        val transcript = listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), result("h_5"), user("h_6", "Nieuw"))
        val merged = HistoryMapper.reconcile(stopped, transcript)
        assertFalse(merged.caughtUp)
        assertEquals(listOf("h_1", "h_2", "h_5", "l_3", "l_4"), merged.messages.map { it.id })
    }

    @Test
    fun aQuestionThatNeverReachedHermesIsNotMistakenForAnEarlierOneWithTheSameText() {
        val shown = listOf(user("h_1", "Vraag"), answer("h_2", "Antwoord"), user("l_3", "Vraag"), answer("l_4", "", MessageState.Failed))
        val merged = HistoryMapper.reconcile(shown, listOf(user("h_1", "Vraag"), answer("h_2", "Antwoord")))
        assertFalse(merged.caughtUp)
        assertEquals(shown, merged.messages)
    }

    @Test
    fun aCompressedCopyOfAShownResultIsNotAddedTwice() {
        val stopped = listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), user("l_3", "Nieuw"), answer("l_4", "Half", MessageState.Cancelled))
        val shown = stopped + HistoryMapper.map(listOf(Deliveries.delivery(10, Deliveries.single("dX"))))
        // Compression kept the delivery under a new row id.
        val transcript = listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord")) +
            HistoryMapper.map(listOf(Deliveries.delivery(20, Deliveries.single("dX"))))
        assertEquals(shown, HistoryMapper.reconcile(shown, transcript).messages)
    }

    @Test
    fun aTurnHermesHasNotSavedStaysAndOnlyGainsNewResults() {
        // The reply isn't saved yet: keep it.
        val shown = listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), user("l_3", "Vraag"), answer("l_4", "Antwoord"))
        assertEquals(shown, HistoryMapper.reconcile(shown, listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), user("h_3", "Vraag"))).messages)

        // A stopped turn stays stopped, and a result that came in afterwards is added below it.
        val stopped = listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), user("l_3", "Nieuw"), answer("l_4", "Half", MessageState.Cancelled))
        val merged = HistoryMapper.reconcile(stopped, listOf(user("h_1", "Eerder"), answer("h_2", "Eerder antwoord"), result("h_9")))
        assertFalse(merged.caughtUp)
        assertEquals(stopped + result("h_9"), merged.messages)

        // A turn that failed before reaching Hermes keeps its error and retry.
        val failed = listOf(user("l_1", "Vraag"), answer("l_2", "", MessageState.Failed))
        assertEquals(failed, HistoryMapper.reconcile(failed, emptyList()).messages)
    }
}

class DeliveryAcksTest {
    @Test
    fun recordsEachResultOnceAndKeepsTheLatest() {
        val (first, isNew) = DeliveryAcks.add("", "server|a")
        assertTrue(isNew)
        val (same, again) = DeliveryAcks.add(first, "server|a")
        assertFalse(again)
        assertEquals(first, same)
        val (capped, _) = DeliveryAcks.add("x\ny\nz", "w", limit = 3)
        assertEquals("y\nz\nw", capped)
    }
}

class MessagePagesTest {
    @Test
    fun tailPollsAskForTheLatestRowsAndFallBackWhenTheServerIgnoresOrder() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"session_id":"tip","data":[{"id":7,"role":"user","content":"x"}],"pagination":{"order":"latest","limit":40}}"""))
        // An older Hermes without `order` answers with its default page and no echo.
        server.enqueue(MockResponse().setBody("""{"data":[{"id":1,"role":"user","content":"oud"}]}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":1,"role":"user","content":"oud"}]}"""))
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        server.start()
        val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "key") }

        val page = api.sessionTail("s", 40)
        assertEquals("tip", page.sessionId)
        assertEquals("/api/sessions/s/messages?order=latest&limit=40", server.takeRequest().path)

        api.sessionTail("s", 40)
        assertEquals("/api/sessions/s/messages?order=latest&limit=40", server.takeRequest().path)
        assertEquals("/api/sessions/s/messages", server.takeRequest().path)
        assertNull(api.sessionTail("s", 40).sessionId)
        assertEquals("/api/sessions/s/messages", server.takeRequest().path)
        server.shutdown()
    }
}
