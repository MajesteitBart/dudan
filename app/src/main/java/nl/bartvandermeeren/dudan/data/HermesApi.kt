package nl.bartvandermeeren.dudan.data

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Client for the Hermes Agent API server (gateway/platforms/api_server.py). */
class HermesApi(
    private val http: OkHttpClient,
    private val server: suspend () -> ServerConfig,
) {
    data class ServerConfig(val baseUrl: String, val apiKey: String)

    class HermesException(val status: Int, override val message: String, val code: String? = null) : IOException(message)

    // ---- Health and discovery -----------------------------------------------------------------

    /** Returns the advertised model alias (profile name) on success; throws on auth or network errors. */
    suspend fun verify(): String? = capabilities().model

    suspend fun capabilities(): ServerCapabilities = ServerCapabilities.from(getJson("v1", "capabilities").asObject())

    suspend fun modelOptions(): ModelCatalog {
        val root = getJson("api", "model", "options").asObject() ?: JsonObject(emptyMap())
        return ModelCatalogParser.parse(root)
    }

    suspend fun skills(): List<SkillInfo> {
        val root = getJson("v1", "skills")
        val items = root.asObject()?.get("data").asArray() ?: root.asArray() ?: JsonArray(emptyList())
        return items.mapNotNull { it.asObject() }.mapNotNull { o ->
            val name = o.str("name") ?: return@mapNotNull null
            SkillInfo(name, o.str("description"), o.str("category"))
        }
    }

    /** One skill's SKILL.md. Stock Hermes doesn't have this endpoint; see [ServerCapabilities.readsSkills]. */
    suspend fun skill(name: String): SkillDetail {
        val o = getJson("v1", "skills", name).asObject() ?: throw HermesException(500, "Hermes returned no skill")
        return SkillDetail.from(o, name)
    }

    // ---- Sessions -------------------------------------------------------------------------------

    suspend fun listSessions(source: String?, limit: Int = 100): List<SessionSummary> {
        val root = getJson("api", "sessions", query = mapOf("limit" to limit.toString(), "source" to source))
        return root.asObject()?.get("data").asArray().orEmpty().mapNotNull { it.asObject()?.let(SessionSummary::from) }
    }

    suspend fun createSession(id: String, title: String? = null) {
        val body = buildJsonObject {
            put("id", id)
            put("source", "api_server")
            if (!title.isNullOrBlank()) put("title", title)
        }
        try {
            send(request("api", "sessions").post(body.toBody()).build())
        } catch (e: HermesException) {
            if (e.status != 409) throw e // already exists: fine
        }
    }

    suspend fun updateSession(id: String, title: String? = null, pinned: Boolean? = null) {
        val body = buildJsonObject {
            if (title != null) put("title", title)
            if (pinned != null) put("pinned", pinned)
        }
        send(request("api", "sessions", id).patch(body.toBody()).build())
    }

    suspend fun deleteSession(id: String) {
        send(request("api", "sessions", id).delete().build())
    }

    /** The session's transcript: the latest 500 rows, which is Hermes' default page. */
    suspend fun sessionMessages(id: String): MessagePage = messagePage(getJson("api", "sessions", id, "messages"))

    /**
     * The last [limit] rows, for polling. A server that ignores `order` returns the oldest rows instead
     * and doesn't echo the order back; from then on this falls back to the default page.
     */
    suspend fun sessionTail(id: String, limit: Int): MessagePage {
        val base = server().baseUrl
        if (tailUnsupportedOn != base) {
            val root = getJson("api", "sessions", id, "messages", query = mapOf("order" to "latest", "limit" to limit.toString()))
            if (root.asObject()?.get("pagination").asObject()?.str("order") == "latest") return messagePage(root)
            tailUnsupportedOn = base
        }
        return sessionMessages(id)
    }

    /**
     * [limit] rows ending [offset] rows before the newest, oldest first; Hermes pages back from the end
     * with `order=latest`. Null when the server doesn't page that way.
     */
    suspend fun sessionRowsBefore(id: String, offset: Int, limit: Int): List<HermesMessage>? {
        val query = mapOf("order" to "latest", "limit" to limit.toString(), "offset" to offset.toString())
        val root = getJson("api", "sessions", id, "messages", query = query)
        if (root.asObject()?.get("pagination").asObject()?.str("order") != "latest") return null
        return messagePage(root).messages
    }

    @Volatile private var tailUnsupportedOn: String? = null

    private fun messagePage(root: JsonElement): MessagePage {
        val o = root.asObject()
        return MessagePage(o?.str("session_id"), o?.get("data").asArray().orEmpty().mapNotNull { it.asObject()?.let(HermesMessage::from) })
    }

    // ---- Agent turns ----------------------------------------------------------------------------

    /**
     * Starts a run bound to a session. Runs keep going on the server when the client disconnects,
     * which matters on a phone that switches networks or gets backgrounded mid-task.
     */
    /**
     * [instructions] is added to the system prompt of this turn only; Hermes doesn't store it, so it
     * reaches no other channel.
     */
    suspend fun startRun(sessionId: String, input: String, model: ModelChoice, instructions: String? = null): String =
        admitRun(sessionId, input, model, instructions, idempotencyKey = null).runId

    /**
     * Starts a run like [startRun]. Requests with the same [idempotencyKey] and body get the same run:
     * Hermes then answers with that run's current status and `replayed`.
     */
    suspend fun admitRun(
        sessionId: String,
        input: String,
        model: ModelChoice,
        instructions: String?,
        idempotencyKey: String?,
    ): RunAdmission {
        val body = buildJsonObject {
            put("input", input)
            put("session_id", sessionId)
            putModel(model)
            if (!instructions.isNullOrBlank()) put("instructions", instructions)
        }
        val request = request("v1", "runs").post(body.toBody())
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey)
        val root = sendJson(request.build()).asObject()
        val runId = root?.str("run_id") ?: throw HermesException(500, "Hermes did not return a run id")
        return RunAdmission(runId, root.str("status"), root.bool("replayed") ?: false)
    }

    data class RunAdmission(val runId: String, val status: String?, val replayed: Boolean)

    fun runEvents(runId: String): Flow<AgentEvent> = sse {
        request("v1", "runs", runId, "events").header("Accept", "text/event-stream").get().build()
    }

    suspend fun runStatus(runId: String): RunStatus {
        val o = getJson("v1", "runs", runId).asObject() ?: JsonObject(emptyMap())
        return RunStatus(
            status = o.str("status").orEmpty(),
            output = o.str("output"),
            error = o.str("error"),
            approval = o["approval"].asObject()?.let(AgentEventParser::parseApproval),
        )
    }

    suspend fun stopRun(runId: String) {
        send(request("v1", "runs", runId, "stop").post(JsonObject(emptyMap()).toBody()).build())
    }

    suspend fun resolveApproval(runId: String, choice: String, requestId: String?) {
        val body = buildJsonObject {
            put("choice", choice)
            if (requestId != null) put("request_id", requestId)
        }
        send(request("v1", "runs", runId, "approval").post(body.toBody()).build())
    }

    /** Session chat stream, used for turns with images (the Runs endpoint only takes text input). */
    fun sessionChatStream(sessionId: String, message: JsonElement, model: ModelChoice, instructions: String? = null): Flow<AgentEvent> = sse {
        val body = buildJsonObject {
            put("message", message)
            putModel(model)
            if (!instructions.isNullOrBlank()) put("system_message", instructions)
        }
        request("api", "sessions", sessionId, "chat", "stream")
            .header("Accept", "text/event-stream")
            .post(body.toBody())
            .build()
    }

    // ---- Scheduled jobs -------------------------------------------------------------------------

    suspend fun jobs(): List<JobInfo> {
        val root = getJson("api", "jobs", query = mapOf("include_disabled" to "true"))
        return root.asObject()?.get("jobs").asArray().orEmpty().mapNotNull { it.asObject()?.let(::parseJob) }
    }

    suspend fun jobAction(jobId: String, action: String) {
        send(request("api", "jobs", jobId, action).post(JsonObject(emptyMap()).toBody()).build())
    }

    private fun parseJob(o: JsonObject): JobInfo? {
        val id = o.str("id") ?: return null
        val schedule = o["schedule"].let { it.asObject()?.str("display") ?: it.asObject()?.str("expr") ?: (it as? JsonPrimitive)?.contentOrNull }
        return JobInfo(
            id = id,
            name = o.str("name") ?: o.str("prompt")?.take(60) ?: id,
            schedule = o.str("schedule_display") ?: schedule,
            prompt = o.str("prompt"),
            enabled = o.bool("enabled") ?: true,
            paused = o.bool("paused") ?: (o.str("state") == "paused"),
            nextRunAt = o.str("next_run_at"),
            lastRunAt = o.str("last_run_at"),
            lastStatus = o.str("last_status"),
        )
    }

    // ---- Plumbing -------------------------------------------------------------------------------

    private fun JsonObjectBuilder.putModel(model: ModelChoice) {
        if (!model.model.isNullOrBlank()) {
            put("model", model.model)
            if (!model.provider.isNullOrBlank()) put("provider", model.provider)
        }
        // Two separate Hermes options: how long the model thinks, and priority processing.
        val options = buildJsonObject {
            model.effort?.let { put("reasoning_effort", it.wire) }
            if (model.fast) put("fast", true)
        }
        if (options.isNotEmpty()) put("model_options", options)
    }

    private suspend fun request(vararg segments: String, query: Map<String, String?> = emptyMap()): Request.Builder {
        val config = server()
        val base: HttpUrl = normalizeBaseUrl(config.baseUrl).toHttpUrlOrNull()
            ?: throw HermesException(0, "Server URL is not valid")
        val url = base.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) }
        }.build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
    }

    private suspend fun getJson(vararg segments: String, query: Map<String, String?> = emptyMap()): JsonElement =
        sendJson(request(*segments, query = query).get().build())

    private suspend fun send(request: Request) {
        sendJson(request)
    }

    private suspend fun sendJson(request: Request): JsonElement {
        val response = http.newCall(request).await()
        // Own the response from here on, so cancellation before the IO switch still closes it.
        try {
            return withContext(Dispatchers.IO) {
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw errorFrom(response.code, text)
                if (text.isBlank()) JsonNull else runCatching { HermesJson.parseToJsonElement(text) }.getOrDefault(JsonNull)
            }
        } finally {
            response.close()
        }
    }

    private fun sse(makeRequest: suspend () -> Request): Flow<AgentEvent> = callbackFlow {
        val call = http.newCall(makeRequest())
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        close(errorFrom(resp.code, resp.body?.string().orEmpty()))
                        return@use
                    }
                    val frames = SseReader(resp.body!!.source())
                    while (isActive) {
                        val frame = frames.next() ?: break
                        AgentEventParser.parse(frame.event, frame.data)?.let { send(it) }
                    }
                    close()
                }
            } catch (e: IOException) {
                close(if (call.isCanceled()) null else e)
            }
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }.buffer(Channel.UNLIMITED)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun JsonObject.toBody() = toString().toRequestBody(JSON)

        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return url
            if (!url.contains("://")) url = "http://$url"
            url = url.trimEnd('/')
            if (url.endsWith("/v1")) url = url.removeSuffix("/v1")
            return url
        }

        internal fun errorFrom(status: Int, body: String): HermesException {
            val root = runCatching { HermesJson.parseToJsonElement(body) }.getOrNull().asObject()
            val error = root?.get("error")
            val message = error.asObject()?.str("message")
                ?: (error as? JsonPrimitive)?.contentOrNull
                ?: root?.str("message")
                ?: root?.str("detail")
                ?: body.take(200).ifBlank { "HTTP $status" }
            val code = error.asObject()?.str("code") ?: root?.str("code")
            return HermesException(status, message, code)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        // If the caller was cancelled while the response arrived, nobody else will close it.
        override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, value, _ -> value.close() }
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

/** Flattens /api/model/options into pickable rows without depending on its exact shape. */
internal object ModelCatalogParser {
    fun parse(root: JsonObject): ModelCatalog {
        val currentProvider = root.str("current_provider") ?: root["current"].asObject()?.str("provider") ?: root.str("provider")
        val currentModel = root.str("current_model") ?: root["current"].asObject()?.str("model") ?: root.str("model")
        val providers = root["providers"].asArray() ?: JsonArray(emptyList())
        val options = mutableListOf<ModelOption>()
        for (element in providers) {
            val p = element.asObject() ?: continue
            val slug = p.str("slug") ?: p.str("id") ?: continue
            val usable = listOf("authenticated", "configured", "available", "is_configured")
                .mapNotNull { p.bool(it) }
                .let { flags -> flags.isEmpty() || flags.any { it } }
            if (!usable) continue
            val name = p.str("name") ?: p.str("label") ?: slug
            val models = p["models"].asArray() ?: continue
            // {"gpt-6-luna": {"fast": true, "reasoning": true}, ...}
            val capabilities = p["capabilities"].asObject()
            for (m in models) {
                val (id, label) = when (m) {
                    is JsonPrimitive -> (m.contentOrNull ?: continue).let { it to it }
                    is JsonObject -> {
                        val id = m.str("id") ?: m.str("model") ?: m.str("name") ?: continue
                        id to (m.str("label") ?: m.str("display_name") ?: m.str("name") ?: id)
                    }
                    else -> continue
                }
                val isCurrent = slug.equals(currentProvider, ignoreCase = true) && id == currentModel
                val caps = capabilities?.get(id).asObject()
                options += ModelOption(slug, name, id, label, isCurrent, reasoning = caps?.bool("reasoning"), fast = caps?.bool("fast"))
            }
        }
        return ModelCatalog(currentProvider, currentModel, options)
    }
}
