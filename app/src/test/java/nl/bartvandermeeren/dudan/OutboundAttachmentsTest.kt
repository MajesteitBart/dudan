package nl.bartvandermeeren.dudan

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import nl.bartvandermeeren.dudan.chat.*
import nl.bartvandermeeren.dudan.data.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OutboundAttachmentsTest {
    // The example from docs/outbound-attachments.md, as Clark documents it too.
    private val attachment = """{"version":1,"session_id":"chat-a","input_id":"input-a","index":0,"name":"screen.png","mime_type":"image/png","size":12,"sha256":"${"a".repeat(64)}"}"""

    private fun row(json: String) = HermesMessage.from(HermesJson.parseToJsonElement(json).jsonObject)
    private fun parse(json: String) = OutboundAttachment.parse(HermesJson.parseToJsonElement(json))
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun parsesTheDocumentedShape() {
        val file = parse("[$attachment]").single()
        assertEquals(OutboundAttachment("chat-a", "input-a", 0, "screen.png", "image/png", 12, "a".repeat(64)), file)
        assertTrue(file.previewable)
        assertTrue(parse("""{"attachments":[]}""").isEmpty())
        assertTrue(OutboundAttachment.parse(null).isEmpty())
    }

    @Test fun attachmentOnlyAnswerSurvivesHistory() {
        val answer = row("""{"id":"answer","role":"assistant","content":"","attachments":[$attachment]}""")
        val mapped = HistoryMapper.map(listOf(answer)).single()
        assertEquals("screen.png", mapped.attachments.single().name)
        assertEquals("", mapped.text)
    }

    @Test fun historyShowsAnAnswersFilesOnce() {
        // Clark can list the files on any assistant row of the answer, also the one that called attach_file.
        val call = row("""{"id":"call","role":"assistant","content":"","tool_calls":[{"id":"c1","function":{"name":"attach_file","arguments":"{}"}}],"attachments":[$attachment]}""")
        val result = row("""{"id":"result","role":"tool","content":"attached","tool_call_id":"c1"}""")
        val answer = row("""{"id":"answer","role":"assistant","content":"Here it is.","attachments":[$attachment]}""")
        val again = row("""{"id":"again","role":"assistant","content":"And again.","attachments":[$attachment]}""")
        val mapped = HistoryMapper.map(listOf(row("""{"id":"q","role":"user","content":"Send it"}"""), call, result, answer, again))
        assertEquals(listOf(1, 0), mapped.filter { it.role == Role.Assistant }.map { it.attachments.size })
        // Listed only on the row that called the tool, they still reach the reply.
        val bare = row("""{"id":"answer","role":"assistant","content":"Here it is."}""")
        assertEquals(1, HistoryMapper.map(listOf(call, result, bare)).single().attachments.size)
        // And with no reply after it, the work itself carries them.
        assertEquals(1, HistoryMapper.map(listOf(call, result)).single().attachments.size)
    }

    @Test fun liveAndReplayUseTheSameAttachmentAndDoNotDuplicateIt() {
        val event = AgentEventParser.parse("assistant.completed", """{"content":"","attachments":[$attachment]}""")!!
        val end = AgentEventParser.parse("run.completed", """{"output":"","attachments":[$attachment]}""")!!
        val message = UiMessage("live", Role.Assistant, state = MessageState.Streaming)
        val once = TurnReducer.apply(TurnReducer.apply(message, event, 1), end, 2)
        assertEquals(1, once.attachments.size)
        assertEquals(once, TurnReducer.apply(once, end, 2))
        assertEquals(MessageState.Done, once.state)
        // A terminal event without the array keeps what the answer brought.
        val plain = AgentEventParser.parse("run.completed", """{"output":"old server"}""") as AgentEvent.Finished
        assertTrue(plain.attachments.isEmpty())
        assertEquals(1, TurnReducer.apply(TurnReducer.apply(message, event, 1), plain, 2).attachments.size)
    }

    @Test fun aLiveFileOnlyAnswerStaysUntilTheTranscriptHasIt() {
        val question = UiMessage("local-q", Role.User, text = "Send it")
        val answer = UiMessage("local-a", Role.Assistant, state = MessageState.Done, attachments = parse("[$attachment]"))
        val transcript = HistoryMapper.map(listOf(row("""{"id":"q","role":"user","content":"Send it"}""")))
        assertFalse(HistoryMapper.reconcile(listOf(question, answer), transcript).caughtUp)
        val saved = HistoryMapper.map(listOf(
            row("""{"id":"q","role":"user","content":"Send it"}"""),
            row("""{"id":"a","role":"assistant","content":"","attachments":[$attachment]}"""),
        ))
        assertTrue(HistoryMapper.reconcile(listOf(question, answer), saved).caughtUp)
    }

    @Test fun unsafeMetadataCannotSelectAnotherUrlOrFilesystemPath() {
        assertTrue(parse("[$attachment]".replace("input-a", "../secret")).isEmpty())
        assertTrue(parse("[$attachment]".replace("chat-a", "..")).isEmpty())
        assertTrue(parse("[$attachment]".replace("chat-a", "chat/other")).isEmpty())
        assertTrue(parse("[$attachment]".replace("\"version\":1", "\"version\":2")).isEmpty())
        assertTrue(parse("[$attachment]".replace("\"size\":12", "\"size\":${100L * 1024 * 1024 + 1}")).isEmpty())
        assertTrue(parse("[$attachment]".replace("a".repeat(64), "A".repeat(64))).isEmpty())
        assertTrue(parse("[$attachment]".replace("\"index\":0", "\"index\":100000")).isEmpty())
        val name = parse("[$attachment]".replace("screen.png", "../‮screen.png")).single().name
        assertFalse(name.contains('/') || name.contains('‮') || name.startsWith("."))
        assertEquals("application/octet-stream", parse("[$attachment]".replace("image/png", "image/png; charset=x")).single().mime)
        assertEquals(1, parse("[$attachment,$attachment]").size)
    }

    @Test fun downloadUsesBearerHeaderAndVerifiesBytesWithoutFollowingRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = "synthetic APK bytes".toByteArray()
            val file = OutboundAttachment("chat a?#", "input-a", 3, "sample.apk", "application/vnd.android.package-archive", bytes.size.toLong(), sha256(bytes))
            val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "synthetic-credential") }
            val target = File.createTempFile("attachment-test", ".tmp")
            try {
                server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
                api.downloadAttachment(file, target)
                assertArrayEquals(bytes, target.readBytes())
                val request = server.takeRequest()
                // Each id is its own encoded path segment; the credential only travels in the header.
                assertEquals("/api/sessions/chat%20a%3F%23/attachments/input-a/3", request.path)
                assertEquals("Bearer synthetic-credential", request.getHeader("Authorization"))
                assertFalse(request.path!!.contains("credential"))

                // Same size, other bytes.
                server.enqueue(MockResponse().setBody("x".repeat(bytes.size)))
                assertTrue(runCatching { api.downloadAttachment(file, target) }.isFailure)
                assertFalse(target.exists())

                // More bytes than announced, without a Content-Length to give it away first.
                server.enqueue(MockResponse().setChunkedBody(String(bytes) + "and more", 4))
                assertTrue(runCatching { api.downloadAttachment(file, target) }.isFailure)
                assertFalse(target.exists())

                server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":{"code":"attachment_unavailable","message":"gone"}}"""))
                val gone = runCatching { api.downloadAttachment(file, target) }.exceptionOrNull()
                assertEquals(410, (gone as HermesApi.HermesException).status)
                assertFalse(target.exists())

                // Following the redirect would fetch the right bytes from the other path.
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere").toString()))
                server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
                assertTrue(runCatching { api.downloadAttachment(file, target) }.isFailure)
                assertFalse(target.exists())
                assertEquals(5, server.requestCount)
            } finally {
                target.delete()
            }
        }
    }

    @Test fun runStatusCarriesTheAnswersFiles() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "synthetic-credential") }
            server.enqueue(MockResponse().setBody("""{"status":"completed","output":"","attachments":[$attachment]}"""))
            assertEquals("screen.png", api.runStatus("run-1").attachments.single().name)
            server.enqueue(MockResponse().setBody("""{"status":"completed","output":"Done"}"""))
            assertTrue(api.runStatus("run-2").attachments.isEmpty())
        }
    }
}
