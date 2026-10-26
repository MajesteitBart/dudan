package nl.bartvandermeeren.dudan

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import nl.bartvandermeeren.dudan.chat.*
import nl.bartvandermeeren.dudan.data.*
import nl.bartvandermeeren.dudan.ui.chat.removeAfterFailedSave
import nl.bartvandermeeren.dudan.ui.components.ImageLimits
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

    @Test fun aLiveFileOnlyAnswerKeepsItsIdWhenTheTranscriptCatchesUp() {
        // Its row holds the file's Save; a new id would draw the row again.
        val question = UiMessage("local-q", Role.User, text = "Send it")
        val answer = UiMessage("local-a", Role.Assistant, state = MessageState.Done, attachments = parse("[$attachment]"))
        val saved = HistoryMapper.map(listOf(
            row("""{"id":"q","role":"user","content":"Send it"}"""),
            row("""{"id":"a","role":"assistant","content":"","attachments":[$attachment]}"""),
        ))
        val caughtUp = HistoryMapper.reconcile(listOf(question, answer), saved)
        assertTrue(caughtUp.caughtUp)
        assertEquals("local-a", caughtUp.messages.single { it.attachments.isNotEmpty() }.id)
        // Also when the files sit on the work, with no reply after it.
        val work = HistoryMapper.map(listOf(
            row("""{"id":"q","role":"user","content":"Send it"}"""),
            row("""{"id":"call","role":"assistant","content":"","tool_calls":[{"id":"c1","function":{"name":"attach_file","arguments":"{}"}}],"attachments":[$attachment]}"""),
            row("""{"id":"result","role":"tool","content":"attached","tool_call_id":"c1"}"""),
        ))
        assertEquals("local-a", HistoryMapper.reconcile(listOf(question, answer), work).messages.single { it.attachments.isNotEmpty() }.id)
        // Another file in the same turn isn't this answer.
        val other = saved.map { it.copy(attachments = it.attachments.map { file -> file.copy(index = 1) }) }
        assertFalse(HistoryMapper.reconcile(listOf(question, answer), other).messages.any { it.id == "local-a" })
    }

    @Test fun aFailedSaveOnlyRemovesAFileItCreatedOrStartedWriting() {
        // The picker's new document is empty: remove it.
        assertTrue(removeAfterFailedSave(pickedSize = 0, opened = false))
        // The user chose to overwrite an existing file and the download or the check failed: keep it.
        assertFalse(removeAfterFailedSave(pickedSize = 2048, opened = false))
        assertFalse(removeAfterFailedSave(pickedSize = null, opened = false))
        // Writing truncated it, so what is left is partial either way.
        assertTrue(removeAfterFailedSave(pickedSize = 2048, opened = true))
    }

    private fun png(interlaced: Boolean) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
        byteArrayOf(0, 0, 0, 13) + "IHDR".toByteArray() + byteArrayOf(0, 0, 0x40, 0, 0, 0, 0x40, 0) +
        byteArrayOf(8, 6, 0, 0, if (interlaced) 1 else 0) + ByteArray(4)

    /** SOI, an APP0 segment, a frame header of [frame] type with three components, and a first scan with [scanned] of them. */
    private fun jpeg(frame: Int, scanned: Int = 3): ByteArray {
        fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
        val app0 = bytes(0xFF, 0xE0, 0, 16) + "JFIF".toByteArray() + ByteArray(10)
        val sof = bytes(0xFF, frame, 0, 17, 8, 0x10, 0, 0x10, 0, 3) + ByteArray(9)
        val sos = bytes(0xFF, 0xDA, 0, 6 + 2 * scanned, scanned) + ByteArray(2 * scanned + 3)
        return bytes(0xFF, 0xD8) + app0 + bytes(0xFF, 0xFF) + sof + sos + ByteArray(64)
    }

    private fun webp(chunk: String) = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + chunk.toByteArray() + ByteArray(32)

    private fun limit(bytes: ByteArray) = ImageLimits.maxPixels(bytes.inputStream())

    @Test fun decodersThatHoldTheWholeImageGetTheSmallerPixelLimit() {
        assertEquals(ImageLimits.STREAMED_PIXELS, limit(png(interlaced = false)))
        assertEquals(ImageLimits.BUFFERED_PIXELS, limit(png(interlaced = true)))
        assertEquals(ImageLimits.STREAMED_PIXELS, limit(jpeg(0xC0)))
        assertEquals(ImageLimits.STREAMED_PIXELS, limit(jpeg(0xC1)))
        assertEquals(ImageLimits.BUFFERED_PIXELS, limit(jpeg(0xC2)))
        // Sequential, but one component per scan.
        assertEquals(ImageLimits.BUFFERED_PIXELS, limit(jpeg(0xC0, scanned = 1)))
        // A scan before any frame header, or a file that ends early.
        assertEquals(ImageLimits.BUFFERED_PIXELS, limit(byteArrayOf(-1, -40, -1, -38, 0, 8, 3) + ByteArray(16)))
        assertEquals(ImageLimits.BUFFERED_PIXELS, limit(jpeg(0xC0).copyOf(26)))
        assertEquals(ImageLimits.STREAMED_PIXELS, limit(webp("VP8 ")))
        assertEquals(ImageLimits.BUFFERED_PIXELS, limit(webp("VP8L")))
        assertEquals(ImageLimits.BUFFERED_PIXELS, limit(webp("VP8X")))
        assertEquals(ImageLimits.BUFFERED_PIXELS, limit("GIF89a".toByteArray() + ByteArray(32)))
        assertEquals(ImageLimits.BUFFERED_PIXELS, limit(ByteArray(0)))

        // A 50 MP phone photo decodes row by row; the same size interlaced doesn't.
        assertTrue(ImageLimits.allows(8_160, 6_120, ImageLimits.STREAMED_PIXELS))
        assertFalse(ImageLimits.allows(8_160, 6_120, ImageLimits.BUFFERED_PIXELS))
        assertFalse(ImageLimits.allows(16_384, 16_384, ImageLimits.STREAMED_PIXELS))
        assertFalse(ImageLimits.allows(16_385, 1, ImageLimits.STREAMED_PIXELS))
        assertFalse(ImageLimits.allows(0, 100, ImageLimits.STREAMED_PIXELS))
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
