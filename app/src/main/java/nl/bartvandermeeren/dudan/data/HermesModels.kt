package nl.bartvandermeeren.dudan.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

internal val HermesJson = Json {
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

data class SessionSummary(
    val id: String,
    val title: String?,
    val preview: String?,
    val source: String?,
    val startedAt: Double?,
    val lastActive: Double?,
    val pinned: Boolean,
    val messageCount: Int,
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
            )
        }
    }
}

data class ToolCallRef(val id: String?, val name: String, val arguments: String?)

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
) {
    companion object {
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
    data class FinalText(val content: String) : AgentEvent
    data class Finished(val outcome: RunOutcome, val output: String?, val error: String?) : AgentEvent
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
            "assistant.completed" -> AgentEvent.FinalText(o.str("content").orEmpty())
            "run.completed" -> AgentEvent.Finished(RunOutcome.Completed, o.str("output"), null)
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
)

data class RunStatus(
    val status: String,
    val output: String?,
    val error: String?,
    val approval: ApprovalRequest?,
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
