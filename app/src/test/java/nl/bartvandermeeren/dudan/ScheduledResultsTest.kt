package nl.bartvandermeeren.dudan

import kotlinx.serialization.json.jsonObject
import nl.bartvandermeeren.dudan.chat.HistoryMapper
import nl.bartvandermeeren.dudan.chat.MessageState
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.chat.ScheduledLabel
import nl.bartvandermeeren.dudan.chat.StepKind
import nl.bartvandermeeren.dudan.chat.UiMessage
import nl.bartvandermeeren.dudan.data.HermesJson
import nl.bartvandermeeren.dudan.data.HermesMessage
import nl.bartvandermeeren.dudan.data.ScheduledRef
import nl.bartvandermeeren.dudan.data.SessionSummary
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
