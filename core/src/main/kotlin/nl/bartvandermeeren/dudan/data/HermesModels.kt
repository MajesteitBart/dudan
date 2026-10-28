package nl.bartvandermeeren.dudan.data

import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

val HermesJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

// Hermes payloads are loosely typed (SQLite rows, 0/1 flags, float timestamps), so parse by hand.
internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.dbl(key: String): Double? =
    (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } }

/**
 * A page of chats, and [serverSeconds], the server's clock when it listed them: its Date header, which counts whole
 * seconds. Null when it sent none.
 */
data class SessionListing(val sessions: List<SessionSummary>, val serverSeconds: Double?)

/** A chat in the drawer. [jobId] is set on a chat Clark made for a scheduled task's results; Hermes sends none. */
data class SessionSummary(
    val id: String,
    val title: String?,
    val preview: String?,
    val source: String?,
    val startedAt: Double?,
    val lastActive: Double?,
    val pinned: Boolean,
    val messageCount: Int,
    val jobId: String? = null,
    /** Made by this app for a chat Hermes hasn't listed yet, so its times are the device's clock, not Hermes'. */
    val local: Boolean = false,
) {
    val displayTitle: String
        get() = title?.let(AttachmentNotes::clean)?.trim()?.takeIf { it.isNotEmpty() }
            ?: preview?.let(AttachmentNotes::clean)?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(80)
            ?: ""

    companion object {
        fun from(o: JsonObject): SessionSummary? {
            val id = o.str("id") ?: return null
            return SessionSummary(
                id = id,
                title = o.str("title"),
                preview = o.str("preview"),
                source = o.str("source"),
                startedAt = o.dbl("started_at"),
                lastActive = o.dbl("last_active") ?: o.dbl("started_at"),
                pinned = o.bool("pinned") ?: false,
                messageCount = o.int("message_count") ?: 0,
                jobId = o.str("job_id")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

data class ToolCallRef(val id: String?, val name: String, val arguments: String?)

/** Rows of a session's transcript. [sessionId] is the live session Hermes resolved, which changes when it compresses a chat. */
data class MessagePage(val sessionId: String?, val messages: List<HermesMessage>)

/** Which scheduled task delivered a result row, from Clark's `scheduled: {job_id, title}`; either can be missing. */
data class ScheduledRef(val jobId: String?, val title: String?)

/** A transcript row. [scheduled] is set on a scheduled task's result; see [isScheduledResult]. */
data class HermesMessage(
    val id: String?,
    val role: String,
    val content: JsonElement?,
    val toolCalls: List<ToolCallRef>,
    val toolCallId: String?,
    val toolName: String?,
    val timestamp: Double?,
    val displayKind: String?,
    val reasoning: String?,
    val attachments: List<OutboundAttachment> = emptyList(),
    val scheduled: ScheduledRef? = null,
) {
    /**
     * Clark writes a scheduled task's result into its chat between turns, as an assistant row with this display kind.
     * Nobody asked for it in that chat, so it belongs to no turn.
     */
    val isScheduledResult: Boolean get() = role == "assistant" && displayKind == SCHEDULED_RESULT

    companion object {
        /** The `display_kind` of a scheduled task's result in Clark's transcript. */
        const val SCHEDULED_RESULT = "scheduled_result"

        fun from(o: JsonObject): HermesMessage = HermesMessage(
            id = o.str("id"),
            role = o.str("role").orEmpty(),
            content = o["content"],
            toolCalls = o["tool_calls"].parseToolCalls(),
            toolCallId = o.str("tool_call_id"),
            toolName = o.str("tool_name"),
            timestamp = o.dbl("timestamp"),
            displayKind = o.str("display_kind"),
            reasoning = o.str("reasoning_content") ?: o.str("reasoning"),
            attachments = OutboundAttachment.parse(o["attachments"]),
            scheduled = o["scheduled"].asObject()?.let { s ->
                ScheduledRef(s.str("job_id")?.takeIf { it.isNotBlank() }, s.str("title")?.takeIf { it.isNotBlank() })
            },
        )

        private fun JsonElement?.parseToolCalls(): List<ToolCallRef> {
            val array = when (this) {
                is JsonArray -> this
                // Some rows store tool_calls as a JSON string.
                is JsonPrimitive -> contentOrNull?.let { runCatching { HermesJson.parseToJsonElement(it) }.getOrNull() }.asArray()
                else -> null
            } ?: return emptyList()
            return array.mapNotNull { element ->
                val call = element.asObject() ?: return@mapNotNull null
                val function = call["function"].asObject()
                val name = function?.str("name") ?: call.str("name") ?: return@mapNotNull null
                val args = function?.get("arguments") ?: call["arguments"]
                ToolCallRef(
                    id = call.str("id"),
                    name = name,
                    arguments = when (args) {
                        is JsonPrimitive -> args.contentOrNull
                        null, JsonNull -> null
                        else -> args.toString()
                    },
                )
            }
        }
    }
}

/** Text of an OpenAI-style content field: a string or a list of typed parts. */
fun JsonElement?.contentText(): String = when (val content = this.normalizedContent()) {
    null, JsonNull -> ""
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.mapNotNull { part ->
        when (part) {
            is JsonPrimitive -> part.contentOrNull
            is JsonObject -> when (part.str("type")) {
                "text", "input_text", "output_text" -> part.str("text")
                else -> null
            }
            else -> null
        }
    }.joinToString("\n")
    is JsonObject -> content.str("text").orEmpty()
}

/** Image URLs (usually data: URLs) inside an OpenAI-style content field. */
fun JsonElement?.contentImages(): List<String> {
    val parts = this.normalizedContent() as? JsonArray ?: return emptyList()
    return parts.mapNotNull { part ->
        val o = part.asObject() ?: return@mapNotNull null
        when (o.str("type")) {
            "image_url" -> o["image_url"].let { it.asObject()?.str("url") ?: (it as? JsonPrimitive)?.contentOrNull }
            "input_image" -> o.str("image_url") ?: o["image_url"].asObject()?.str("url")
            else -> null
        }
    }
}

private fun JsonElement?.normalizedContent(): JsonElement? {
    // Multimodal content can come back JSON-encoded inside a string.
    if (this is JsonPrimitive && isString) {
        val raw = content.trimStart()
        if (raw.startsWith("[{") && raw.contains("\"type\"")) {
            runCatching { HermesJson.parseToJsonElement(raw) }.getOrNull()?.let { return it }
        }
    }
    return this
}

data class ApprovalRequest(
    val runId: String,
    val command: String?,
    val description: String?,
    val choices: List<String>,
    val requestId: String?,
    val pendingChoice: String? = null,
)

enum class RunOutcome { Completed, Failed, Cancelled, Interrupted }

/** Normalized agent event. Hermes' Runs stream and session stream use slightly different names. */
sealed interface AgentEvent {
    data class RunStarted(val runId: String?) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class Reasoning(val text: String) : AgentEvent
    data class ToolStarted(val tool: String, val preview: String?) : AgentEvent
    data class ToolFinished(val tool: String, val preview: String?, val failed: Boolean, val durationSec: Double?) : AgentEvent
    data class Commentary(val text: String, val alreadyStreamed: Boolean) : AgentEvent
    data class ApprovalRequested(val request: ApprovalRequest) : AgentEvent
    data class ApprovalResolved(val requestId: String?) : AgentEvent
    data class SubagentStarted(val preview: String?) : AgentEvent
    data class SubagentFinished(val summary: String?, val status: String?) : AgentEvent
    data class FinalText(val content: String, val attachments: List<OutboundAttachment> = emptyList()) : AgentEvent
    data class Finished(val outcome: RunOutcome, val output: String?, val error: String?, val attachments: List<OutboundAttachment> = emptyList()) : AgentEvent
    data class Failed(val message: String) : AgentEvent
    data object StreamClosed : AgentEvent
}

object AgentEventParser {
    fun parse(sseEventName: String?, data: String): AgentEvent? {
        val o = runCatching { HermesJson.parseToJsonElement(data) }.getOrNull().asObject() ?: return null
        // Runs stream frames carry the name inside the payload; session streams use the SSE event line.
        val name = sseEventName?.takeIf { it.isNotBlank() && it != "message" } ?: o.str("event") ?: return null
        return when (name) {
            "run.started" -> AgentEvent.RunStarted(o.str("run_id"))
            "message.delta", "assistant.delta" -> o.str("delta")?.let { AgentEvent.TextDelta(it) }
            "reasoning.available" -> o.str("text")?.takeIf { it.isNotEmpty() }?.let { AgentEvent.Reasoning(it) }
            "tool.progress" ->
                if (o.str("tool_name") == "_thinking") o.str("delta")?.takeIf { it.isNotEmpty() }?.let { AgentEvent.Reasoning(it) } else null
            "tool.started" -> AgentEvent.ToolStarted(o.str("tool") ?: o.str("tool_name") ?: "tool", o.str("preview"))
            "tool.completed", "tool.failed" -> AgentEvent.ToolFinished(
                tool = o.str("tool") ?: o.str("tool_name") ?: "tool",
                preview = o.str("preview"),
                failed = name == "tool.failed" || o.bool("error") == true,
                durationSec = o.dbl("duration"),
            )
            "message.interim", "assistant.commentary" ->
                o.str("text")?.takeIf { it.isNotBlank() }?.let { AgentEvent.Commentary(it, o.bool("already_streamed") ?: false) }
            "approval.request" -> AgentEvent.ApprovalRequested(parseApproval(o))
            "approval.responded" -> AgentEvent.ApprovalResolved(o.str("request_id"))
            "subagent.start" -> AgentEvent.SubagentStarted(o.str("preview") ?: o.str("goal"))
            "subagent.complete" -> AgentEvent.SubagentFinished(o.str("summary") ?: o.str("preview"), o.str("status"))
            "assistant.completed" -> AgentEvent.FinalText(o.str("content").orEmpty(), OutboundAttachment.parse(o["attachments"]))
            "run.completed" -> AgentEvent.Finished(RunOutcome.Completed, o.str("output"), null, OutboundAttachment.parse(o["attachments"]))
            "run.failed" -> AgentEvent.Finished(RunOutcome.Failed, o.str("output"), o.str("error"))
            "run.cancelled" -> AgentEvent.Finished(RunOutcome.Cancelled, o.str("output"), o.str("error"))
            "run.interrupted" -> AgentEvent.Finished(RunOutcome.Interrupted, o.str("output"), o.str("error"))
            "error" -> AgentEvent.Failed(o.str("message") ?: o["error"].asObject()?.str("message") ?: "Unknown error")
            "done" -> AgentEvent.StreamClosed
            else -> null
        }
    }

    internal fun parseApproval(o: JsonObject) = ApprovalRequest(
        runId = o.str("run_id").orEmpty(),
        command = o.str("command"),
        description = o.str("description") ?: o.str("reason") ?: o.str("pattern_description"),
        choices = o["choices"].asArray()?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotEmpty() } ?: listOf("once", "deny"),
        requestId = o.str("request_id"),
    )
}

data class SkillInfo(val name: String, val description: String?, val category: String?)

/**
 * One skill's SKILL.md from GET /v1/skills/{name}: [body] is the markdown after the frontmatter, [path]
 * where it lives under Hermes' skills folder, and [files] its references, scripts and templates.
 */
data class SkillDetail(
    val name: String,
    val description: String?,
    val category: String?,
    val path: String?,
    val body: String,
    val files: List<String>,
) {
    companion object {
        fun from(o: JsonObject, requested: String): SkillDetail = SkillDetail(
            name = o.str("name") ?: requested,
            description = o.str("description")?.takeIf { it.isNotBlank() },
            category = o.str("category")?.takeIf { it.isNotBlank() },
            path = o.str("path")?.takeIf { it.isNotBlank() },
            body = o.str("body") ?: o.str("content").orEmpty(),
            // {"references": ["references/api.md"], "scripts": [...]}
            files = when (val linked = o["linked_files"]) {
                is JsonObject -> linked.values.flatMap { it.asArray().orEmpty().mapNotNull { f -> (f as? JsonPrimitive)?.contentOrNull } }
                is JsonArray -> linked.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            },
        )
    }
}

/**
 * What GET /v1/capabilities says: the model alias Hermes advertises and the names of its endpoints,
 * from `endpoints: {"skills": {"method": "GET", "path": "/v1/skills"}, ...}`.
 */
data class ServerCapabilities(val model: String?, val endpoints: Set<String>, val desktop: DesktopCapabilities? = null) {
    /** GET /v1/skills/{name}, which stock Hermes lacks; tools/hermes-patches adds it. */
    val readsSkills: Boolean get() = "skill" in endpoints

    /**
     * Whether the app may create, change and delete scheduled tasks. Stock Hermes advertises no job endpoints
     * but has them all; Clark lists `jobs`, and only a Clark that also lists `job_create` takes the new ones.
     */
    val managesJobs: Boolean get() = "job_create" in endpoints || "jobs" !in endpoints

    companion object {
        fun from(o: JsonObject?): ServerCapabilities = ServerCapabilities(
            model = o?.str("model"),
            endpoints = o?.get("endpoints").asObject()?.filterValues { it is JsonObject }?.keys.orEmpty(),
            desktop = DesktopCapabilities.from(o),
        )
    }
}

/**
 * A scheduled task from GET /api/jobs, in Hermes' shape or Clark's imitation of it. [schedule] is the server's own
 * display text; [scheduleExpr] the raw schedule (a cron, Clark's `at YYYY-MM-DDTHH:MM`, Hermes' `run_at` or
 * `every Nm`) and [scheduleKind] Hermes' `cron`, `interval` or `once`. [editable] is false for tasks the server
 * won't let the app change, with [editNote] saying why; [nameEditable] is false where the name is the task's id.
 * [deliver] is where results go, null when the server doesn't say. Clark also sends [sessionId], the chat in dudan its
 * results go to, and [lastShadow], whether its last run's results were held back because Clark runs in shadow mode.
 */
data class JobInfo(
    val id: String,
    val name: String,
    val schedule: String?,
    val prompt: String?,
    val enabled: Boolean,
    val paused: Boolean,
    val nextRunAt: String?,
    val lastRunAt: String?,
    val lastStatus: String?,
    val scheduleExpr: String? = null,
    val scheduleKind: String? = null,
    val timezone: String? = null,
    val editable: Boolean = true,
    val editNote: String? = null,
    val nameEditable: Boolean = true,
    val deliver: String? = null,
    val sessionId: String? = null,
    val lastShadow: Boolean = false,
) {
    /** The zone whose clock the server runs this task's cron on, when it says and it's a zone Java knows. */
    val zoneId: ZoneId? get() = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    companion object {
        fun from(o: JsonObject): JobInfo? {
            val id = o.str("id") ?: return null
            val schedule = o["schedule"]
            val scheduleObject = schedule.asObject()
            val expr = scheduleObject?.let { s ->
                s.str("expr") ?: s.str("run_at") ?: s.int("minutes")?.let { "every ${it}m" } ?: s.str("value")
            } ?: (schedule as? JsonPrimitive)?.contentOrNull
            val kind = scheduleObject?.str("kind") ?: expr?.let { if (it.trimStart().startsWith("at ")) "once" else "cron" }
            return JobInfo(
                id = id,
                name = o.str("name") ?: o.str("prompt")?.take(60) ?: id,
                schedule = o.str("schedule_display") ?: scheduleObject?.str("display") ?: expr,
                prompt = o.str("prompt"),
                enabled = o.bool("enabled") ?: true,
                paused = o.bool("paused") ?: (o.str("state") == "paused"),
                nextRunAt = o.str("next_run_at"),
                lastRunAt = o.str("last_run_at"),
                lastStatus = o.str("last_status"),
                scheduleExpr = expr,
                scheduleKind = kind,
                timezone = o.str("timezone") ?: scheduleObject?.str("timezone"),
                editable = o.bool("editable") ?: true,
                editNote = o.str("edit_note")?.takeIf { it.isNotBlank() },
                nameEditable = o.bool("name_editable") ?: true,
                deliver = o.str("deliver"),
                sessionId = o.str("session_id")?.takeIf { it.isNotBlank() },
                lastShadow = o.bool("last_shadow") ?: false,
            )
        }
    }
}

/** A place a task's results can go, from the list's `delivery_targets`; only Clark sends these. */
data class DeliveryTarget(val id: String, val label: String)

/** GET /api/jobs: the tasks and, when the server has them, the places results can go. */
data class JobList(val jobs: List<JobInfo>, val deliveryTargets: List<DeliveryTarget> = emptyList()) {
    companion object {
        fun from(root: JsonObject?): JobList = JobList(
            jobs = root?.get("jobs").asArray()?.mapNotNull { it.asObject()?.let(JobInfo::from) }.orEmpty(),
            deliveryTargets = root?.get("delivery_targets").asArray()?.mapNotNull { element ->
                val o = element.asObject() ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                DeliveryTarget(id, o.str("label")?.takeIf { it.isNotBlank() } ?: id)
            }.orEmpty(),
        )
    }
}

/** What "Nowhere (job log only)" sends as `deliver`. */
const val DELIVER_NOWHERE = "none"

/** Clark's target for results that go to a chat in dudan: the task's own chat, or the chat that scheduled it. */
const val DELIVER_APP = "@dudan"

/**
 * Where a new task's results go: to this app when the server offers it, else to the first place it offers, else
 * nowhere. A task that reaches nobody is rarely meant.
 */
fun newTaskDelivery(targets: List<DeliveryTarget>): String =
    targets.firstOrNull { it.id == DELIVER_APP }?.id ?: targets.firstOrNull()?.id ?: DELIVER_NOWHERE

/** POST /api/jobs. [timezone] and [deliver] stay out of the body when null. */
data class JobDraft(val name: String, val schedule: String, val prompt: String, val timezone: String?, val deliver: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("schedule", schedule)
        put("prompt", prompt)
        if (timezone != null) put("timezone", timezone)
        if (deliver != null) put("deliver", deliver)
    }
}

/**
 * PATCH /api/jobs/{id}: only the fields that changed. [timezone] goes along with [schedule] only, since Hermes
 * drops keys it doesn't know and answers a body with nothing left in it with a 400.
 */
data class JobChanges(
    val name: String? = null,
    val schedule: String? = null,
    val prompt: String? = null,
    val timezone: String? = null,
    val deliver: String? = null,
) {
    val isEmpty: Boolean get() = name == null && schedule == null && prompt == null && deliver == null

    fun toJson(): JsonObject = buildJsonObject {
        if (name != null) put("name", name)
        if (schedule != null) {
            put("schedule", schedule)
            if (timezone != null) put("timezone", timezone)
        }
        if (prompt != null) put("prompt", prompt)
        if (deliver != null) put("deliver", deliver)
    }
}

data class RunStatus(
    val status: String,
    val output: String?,
    val error: String?,
    val approval: ApprovalRequest?,
    val attachments: List<OutboundAttachment> = emptyList(),
) {
    val isTerminal: Boolean get() = status in setOf("completed", "failed", "cancelled", "interrupted")
    val outcome: RunOutcome
        get() = when (status) {
            "completed" -> RunOutcome.Completed
            "cancelled" -> RunOutcome.Cancelled
            "interrupted" -> RunOutcome.Interrupted
            else -> RunOutcome.Failed
        }
}

/**
 * One model the user can pick, flattened from Hermes' /api/model/options payload. [reasoning] and
 * [fast] say whether it takes a thinking level and priority processing; null when Hermes doesn't say.
 */
data class ModelOption(
    val provider: String,
    val providerName: String,
    val model: String,
    val label: String,
    val isCurrent: Boolean,
    val reasoning: Boolean? = null,
    val fast: Boolean? = null,
)

data class ModelCatalog(val currentProvider: String?, val currentModel: String?, val options: List<ModelOption>) {
    /** The model [choice] runs on: the one it names, or the server default when it names none. */
    fun optionFor(choice: ModelChoice): ModelOption? =
        if (choice.model == null) options.firstOrNull { it.isCurrent }
        else options.firstOrNull { it.model == choice.model && (choice.provider == null || it.provider == choice.provider) }

    /** [choice] without a thinking level or fast mode its model reports it doesn't take. Unknown models keep both. */
    fun supported(choice: ModelChoice): ModelChoice {
        val option = optionFor(choice) ?: return choice
        return choice.copy(
            effort = choice.effort.takeIf { option.reasoning != false },
            fast = choice.fast && option.fast == true,
        )
    }
}
