package nl.bartvandermeeren.dudan

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.bartvandermeeren.dudan.device.AppMatcher
import nl.bartvandermeeren.dudan.device.LaunchableApp
import nl.bartvandermeeren.dudan.device.LinkRules
import nl.bartvandermeeren.dudan.device.McpHandler
import nl.bartvandermeeren.dudan.device.McpHttpServer
import nl.bartvandermeeren.dudan.device.PhoneControl
import nl.bartvandermeeren.dudan.device.PhoneTool
import nl.bartvandermeeren.dudan.device.Tailnet
import nl.bartvandermeeren.dudan.device.objectSchema
import nl.bartvandermeeren.dudan.device.requireInt
import nl.bartvandermeeren.dudan.device.requireString
import nl.bartvandermeeren.dudan.device.stringProperty
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val echo = PhoneTool("echo", "Echoes text", objectSchema("text" to stringProperty("Text"), required = listOf("text"))) {
    "echo: ${it.requireString("text")}"
}
private val count = PhoneTool("count", "Checks a number", objectSchema(), readOnly = true) { "n=${it.requireInt("n", 1, 10)}" }
private val broken = PhoneTool("broken", "Always fails", objectSchema()) { throw IllegalStateException("boom") }

private fun parse(text: String?) = Json.parseToJsonElement(text!!).jsonObject

class McpHandlerTest {
    private val handler = McpHandler(listOf(echo, count, broken), "1.2.3")

    private fun call(name: String, arguments: String) =
        parse(handler.handle("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""))

    @Test
    fun initializeEchoesAKnownProtocolVersionAndAdvertisesTools() {
        val reply = parse(handler.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""))
        val result = reply["result"]!!.jsonObject
        assertEquals(1, reply["id"]!!.jsonPrimitive.int)
        assertEquals("2025-03-26", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"))
        assertEquals("1.2.3", result["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun initializeAnswersAnUnknownVersionWithItsNewest() {
        val reply = parse(handler.handle("""{"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"2099-01-01"}}"""))
        assertEquals(McpHandler.PROTOCOL_VERSIONS.first(), reply["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("a", reply["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun notificationsGetNoBody() {
        assertNull(handler.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun listsToolsWithSchemasAndHints() {
        val tools = parse(handler.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("echo", "count", "broken"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val first = tools[0].jsonObject
        assertEquals("object", first["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(first["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
        assertTrue(tools[1].jsonObject["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun callsATool() {
        val result = call("echo", """{"text":"hoi"}""")["result"]!!.jsonObject
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("echo: hoi", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolFailuresComeBackAsToolErrorsForTheAgent() {
        val missing = call("echo", "{}")["result"]!!.jsonObject
        assertTrue(missing["isError"]!!.jsonPrimitive.boolean)
        assertEquals("Missing argument: text", missing["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val outOfRange = call("count", """{"n":11}""")["result"]!!.jsonObject
        assertEquals("n must be between 1 and 10", outOfRange["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val crashed = call("broken", "{}")["result"]!!.jsonObject
        assertTrue(crashed["isError"]!!.jsonPrimitive.boolean)
        assertEquals("broken failed on the phone: boom", crashed["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun acceptsNumbersSentAsStrings() {
        val result = call("count", """{"n":"4"}""")["result"]!!.jsonObject
        assertEquals("n=4", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun protocolErrorsUseJsonRpcCodes() {
        assertEquals(McpHandler.INVALID_PARAMS, call("nope", "{}")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val unknown = parse(handler.handle("""{"jsonrpc":"2.0","id":3,"method":"resources/list"}"""))
        assertEquals(McpHandler.METHOD_NOT_FOUND, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(McpHandler.PARSE_ERROR, parse(handler.handle("{not json"))["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun answersBatchesAndSkipsTheirNotifications() {
        val reply = Json.parseToJsonElement(
            handler.handle("""[{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":4,"method":"ping"}]""")!!,
        ) as JsonArray
        assertEquals(1, reply.size)
        assertEquals(JsonObject(emptyMap()), reply[0].jsonObject["result"])
    }
}

class McpHttpServerTest {
    private var token = "secret-token"
    private val server = McpHttpServer(0, { token }, McpHandler(listOf(echo), "test"), { true }).also { it.start() }
    private val http = OkHttpClient()
    private val url get() = "http://127.0.0.1:${server.localPort}/mcp"
    private val json = "application/json".toMediaType()

    @After
    fun tearDown() = server.stop()

    private fun post(body: String, auth: String? = "Bearer $token", origin: String? = null) = http.newCall(
        Request.Builder().url(url).post(body.toRequestBody(json)).apply {
            auth?.let { header("Authorization", it) }
            origin?.let { header("Origin", it) }
        }.build(),
    ).execute()

    @Test
    fun servesMcpOverPost() {
        post("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"text":"x"}}}""").use { response ->
            assertEquals(200, response.code)
            assertEquals("application/json", response.header("Content-Type"))
            val text = parse(response.body!!.string())["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            assertEquals("echo: x", text)
        }
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").use { assertEquals(202, it.code) }
    }

    @Test
    fun refusesMissingOrWrongTokensAndBrowsers() {
        post("{}", auth = null).use { assertEquals(401, it.code) }
        post("{}", auth = "Bearer nope").use { assertEquals(401, it.code) }
        post("{}", origin = "http://evil.example").use { assertEquals(403, it.code) }
    }

    @Test
    fun aRenewedTokenAppliesRightAway() {
        val old = token
        token = "renewed-token"
        post("{}", auth = "Bearer $old").use { assertEquals(401, it.code) }
        post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").use { assertEquals(200, it.code) }
    }

    @Test
    fun aBlankTokenAuthorizesNothing() {
        token = ""
        post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", auth = "Bearer ").use { assertEquals(401, it.code) }
    }

    @Test
    fun onlyPostIsAllowedAndOnlyOnMcp() {
        val auth = "Bearer $token"
        http.newCall(Request.Builder().url(url).header("Authorization", auth).get().build()).execute().use {
            assertEquals(405, it.code)
            assertEquals("POST", it.header("Allow"))
        }
        http.newCall(Request.Builder().url(url).header("Authorization", auth).head().build()).execute().use { assertEquals(405, it.code) }
        http.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").get().build()).execute().use { assertEquals(404, it.code) }
    }

    @Test
    fun keepsTheConnectionOpenBetweenRequests() {
        Socket("127.0.0.1", server.localPort).use { socket ->
            val out = socket.getOutputStream()
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            repeat(2) { i ->
                val body = """{"jsonrpc":"2.0","id":$i,"method":"ping"}"""
                out.write("POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $token\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                out.flush()
                assertEquals("HTTP/1.1 200 OK", input.readLine())
                var length = 0
                while (true) {
                    val line = input.readLine()
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:")) length = line.substringAfter(':').trim().toInt()
                }
                val reply = CharArray(length).also { input.read(it) }.concatToString()
                assertEquals(i, parse(reply)["id"]!!.jsonPrimitive.int)
            }
        }
    }

    @Test
    fun answersMalformedRequestsWith400() {
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.getOutputStream().write("NONSENSE\r\n\r\n".toByteArray())
            val status = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1)).readLine()
            assertEquals("HTTP/1.1 400 Bad Request", status)
        }
    }

    @Test
    fun cutsOffRequestsThatArriveTooSlowly() {
        val strict = McpHttpServer(0, { token }, McpHandler(listOf(echo), "test"), { true }, requestTimeoutMs = 300).also { it.start() }
        try {
            Socket("127.0.0.1", strict.localPort).use { socket ->
                socket.soTimeout = 5_000
                val out = socket.getOutputStream()
                out.write("POST /mcp HTTP/1.1\r\n".toByteArray())
                Thread.sleep(500)
                // The server gave up on the request and closed the connection.
                val closed = runCatching {
                    out.write("Host: x\r\n\r\n".toByteArray())
                    socket.getInputStream().read()
                }.getOrDefault(-1)
                assertEquals(-1, closed)
            }
        } finally {
            strict.stop()
        }
    }

    @Test
    fun dropsConnectionsTheAddressCheckRefuses() {
        val closed = McpHttpServer(0, { token }, McpHandler(listOf(echo), "test"), { false }).also { it.start() }
        try {
            Socket("127.0.0.1", closed.localPort).use { socket ->
                socket.getOutputStream().write("POST /mcp HTTP/1.1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                // Closed unread, so depending on timing the client sees an end of stream or a reset.
                assertEquals(-1, runCatching { socket.getInputStream().read() }.getOrDefault(-1))
            }
        } finally {
            closed.stop()
        }
    }
}

class AppMatcherTest {
    private val apps = listOf(
        LaunchableApp("Spotify", "com.spotify.music", "Main"),
        LaunchableApp("Google Maps", "com.google.android.apps.maps", "Maps"),
        LaunchableApp("Maps.me", "com.mapswithme.maps.pro", "Main"),
        LaunchableApp("Agenda", "com.samsung.android.calendar", "Main"),
        LaunchableApp("Google Agenda", "com.google.android.calendar", "Main"),
        LaunchableApp("Café Radio", "nl.cafe.radio", "Main"),
        LaunchableApp("Samsung Health", "com.sec.android.app.shealth", "Main"),
        LaunchableApp("Samsung Health", "com.sec.android.app.shealth", "Widget"),
    )

    private fun label(query: String) = (AppMatcher.find(query, apps) as? AppMatcher.Match.One)?.app?.label

    @Test
    fun exactNamesAndPackagesWin() {
        assertEquals("Spotify", label("spotify"))
        assertEquals("Agenda", label("agenda")) // exact beats "Google Agenda"
        assertEquals("Google Agenda", label("com.google.android.calendar"))
    }

    @Test
    fun prefixesWordsAndAccents() {
        assertEquals("Spotify", label("Spot"))
        assertEquals("Café Radio", label("cafe radio"))
        assertEquals("Google Maps", label("google maps"))
    }

    @Test
    fun reportsAmbiguityAndMisses() {
        val several = AppMatcher.find("maps", apps)
        assertTrue(several is AppMatcher.Match.Several)
        assertEquals(2, (several as AppMatcher.Match.Several).apps.size)
        assertEquals(AppMatcher.Match.None, AppMatcher.find("whatsapp", apps))
        assertEquals(AppMatcher.Match.None, AppMatcher.find("  ", apps))
    }

    @Test
    fun severalEntriesOfOneAppCountAsThatApp() {
        assertEquals("Samsung Health", label("health"))
    }
}

class LinkRulesTest {
    @Test
    fun opensOrdinaryLinksAndDeepLinks() {
        listOf("https://nos.nl", "geo:0,0?q=Rijksmuseum", "google.navigation:q=Utrecht", "spotify:playlist:x", "mailto:a@b.nl", "whatsapp://send?text=hoi")
            .forEach { assertEquals(it, LinkRules.Action.View, LinkRules.actionFor(it)) }
    }

    @Test
    fun phoneNumbersGoToTheDialer() {
        assertEquals(LinkRules.Action.Dial, LinkRules.actionFor("tel:+31612345678"))
    }

    @Test
    fun refusesSchemesThatReachFilesOrComponents() {
        listOf("intent:#Intent;component=x/y;end", "file:///sdcard/x", "content://x/y", "javascript:alert(1)", "no scheme", "")
            .forEach { assertNull(it, LinkRules.actionFor(it)) }
    }
}

class OpenedSomethingTest {
    @Test
    fun onlyTurnsThatOpenedAnAppOrLinkCount() {
        assertTrue(PhoneControl.openedSomething(listOf("web_search", "mcp__phone__open_app")))
        assertTrue(PhoneControl.openedSomething(listOf("mcp__fold__open_link")))
        assertFalse(PhoneControl.openedSomething(listOf("mcp__phone__set_timer", "mcp__phone__phone_status", "terminal")))
        assertFalse(PhoneControl.openedSomething(listOf("mcp__phone__open_application_settings")))
        assertFalse(PhoneControl.openedSomething(emptyList()))
    }
}

class TailnetTest {
    @Test
    fun recognizesTailscaleRanges() {
        assertTrue(Tailnet.isTailscaleAddress(InetAddress.getByName("100.77.56.78")))
        assertTrue(Tailnet.isTailscaleAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(Tailnet.isTailscaleAddress(InetAddress.getByName("fd7a:115c:a1e0::1")))
        assertFalse(Tailnet.isTailscaleAddress(InetAddress.getByName("100.128.0.1")))
        assertFalse(Tailnet.isTailscaleAddress(InetAddress.getByName("192.168.1.20")))
        assertFalse(Tailnet.isTailscaleAddress(InetAddress.getByName("127.0.0.1")))
    }

    @Test
    fun loopbackIsNotOwnTailnetAddress() {
        assertFalse(Tailnet.isOwnAddress(InetAddress.getByName("127.0.0.1")))
    }
}
