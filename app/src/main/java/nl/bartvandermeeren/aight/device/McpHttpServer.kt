package nl.bartvandermeeren.aight.device

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * The HTTP side of phone control: one endpoint, POST /mcp, for Hermes' MCP client. That's its only
 * client, so it keeps to what that client sends: Content-Length bodies on reused connections.
 *
 * It listens on all interfaces and drops a connection before reading anything unless [acceptsLocal]
 * approves the address the connection came in on, so only the Tailscale address answers. [token] is
 * read per request, so a renewed token applies without a restart.
 */
class McpHttpServer(
    private val port: Int,
    private val token: () -> String,
    private val handler: McpHandler,
    private val acceptsLocal: (InetAddress) -> Boolean,
    private val requestTimeoutMs: Int = REQUEST_TIMEOUT_MS,
) {
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val workers: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "aight-mcp").apply { isDaemon = true } }
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    @Volatile private var socket: ServerSocket? = null

    /** The port it listens on, which differs from the requested one when that was 0. */
    val localPort: Int get() = socket?.localPort ?: -1

    fun start() {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        socket = server
        thread(name = "aight-mcp-accept", isDaemon = true) { acceptLoop(server) }
    }

    /** Stops listening and closes open connections; blocked reads don't notice an interrupt. */
    fun stop() {
        runCatching { socket?.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            if (!acceptsLocal(client.localAddress) || !slots.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            try {
                workers.execute {
                    // An uncaught exception on this thread would take the whole app down, overlay included.
                    try {
                        serve(client)
                    } catch (_: Exception) {
                    } catch (_: StackOverflowError) {
                    } finally {
                        slots.release()
                    }
                }
            } catch (_: Exception) {
                slots.release()
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) = client.use {
        clients += client
        try {
            serveRequests(client)
        } finally {
            clients -= client
        }
    }

    private fun serveRequests(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        while (true) {
            try {
                val request = readRequest(client, input) ?: return
                val response = respond(request)
                write(output, response)
                if (response.close) return
            } catch (_: BadRequest) {
                runCatching { write(output, Response(400, close = true)) }
                return
            } catch (_: IOException) {
                return // idle or too slow, or the client went away
            }
        }
    }

    private fun respond(request: Request): Response {
        if (request.path.substringBefore('?').trimEnd('/') != "/mcp") return Response(404, close = request.close)
        // Browsers send Origin; Hermes doesn't. Refusing it keeps web pages on the phone out.
        if (request.headers.containsKey("origin")) return Response(403, close = true)
        val expected = token().takeIf { it.isNotBlank() }?.let { "Bearer $it".toByteArray(Charsets.UTF_8) }
        val auth = request.headers["authorization"]?.trim()?.toByteArray(Charsets.UTF_8)
        if (expected == null || auth == null || !MessageDigest.isEqual(auth, expected)) {
            return Response(401, json("""{"error":"A valid bearer token is required"}"""), close = true)
        }
        if (request.method != "POST") return Response(405, headers = mapOf("Allow" to "POST"), close = request.close)
        val reply = handler.handle(request.body.toString(Charsets.UTF_8))
        return if (reply == null) Response(202, close = request.close) else Response(200, json(reply), close = request.close)
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        val close: Boolean get() = headers["connection"].equals("close", ignoreCase = true)
    }

    private class Response(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
        val close: Boolean = false,
    )

    private class BadRequest : IOException()

    private fun json(text: String) = text.toByteArray(Charsets.UTF_8)

    /** Null on a clean end of stream between requests. */
    private fun readRequest(client: Socket, input: InputStream): Request? {
        client.soTimeout = IDLE_TIMEOUT_MS
        val first = input.read()
        if (first == -1) return null
        // From its first byte on, a request must arrive whole in time, so a slow sender can't hold a slot.
        client.soTimeout = requestTimeoutMs
        val deadline = System.nanoTime() + requestTimeoutMs * 1_000_000L
        val head = readHead(input, first, deadline)
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw BadRequest()
        val headers = lines.drop(1).filter { it.isNotEmpty() }.associate { line ->
            val colon = line.indexOf(':').takeIf { it > 0 } ?: throw BadRequest()
            line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) throw BadRequest()
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: throw BadRequest() } ?: 0
        if (length !in 0..MAX_BODY_BYTES) throw BadRequest()
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            checkDeadline(deadline)
            val n = input.read(body, read, length - read)
            if (n == -1) throw EOFException()
            read += n
        }
        return Request(parts[0].uppercase(), parts[1], headers, body)
    }

    private fun readHead(input: InputStream, first: Int, deadline: Long): String {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        var b = first
        while (true) {
            if (b == -1) throw EOFException()
            buffer.write(b)
            if (buffer.size() > MAX_HEAD_BYTES) throw BadRequest()
            matched = when {
                b == HEAD_END[matched].toInt() -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == HEAD_END.size) return buffer.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
            checkDeadline(deadline)
            b = input.read()
        }
    }

    private fun checkDeadline(deadline: Long) {
        if (System.nanoTime() > deadline) throw SocketTimeoutException("Request took too long")
    }

    private fun write(output: OutputStream, response: Response) {
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${REASONS[response.status] ?: "Status"}\r\n")
            if (response.body.isNotEmpty()) append("Content-Type: application/json\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            if (response.close) append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }

    private companion object {
        const val MAX_CONNECTIONS = 8
        const val IDLE_TIMEOUT_MS = 30_000
        const val REQUEST_TIMEOUT_MS = 10_000
        const val MAX_HEAD_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 1024 * 1024
        val HEAD_END = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val REASONS = mapOf(
            200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed",
        )
    }
}
