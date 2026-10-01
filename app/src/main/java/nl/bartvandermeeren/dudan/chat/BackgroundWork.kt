package nl.bartvandermeeren.dudan.chat

import kotlinx.serialization.json.JsonObject
import nl.bartvandermeeren.dudan.data.HermesJson
import nl.bartvandermeeren.dudan.data.HermesMessage
import nl.bartvandermeeren.dudan.data.asArray
import nl.bartvandermeeren.dudan.data.asObject
import nl.bartvandermeeren.dudan.data.contentText
import nl.bartvandermeeren.dudan.data.str

enum class BackgroundStatus { Completed, PartlyFailed, Failed }

/**
 * What a subagent the agent sent off in the background reported. Hermes saves it in the transcript as
 * a user row, because that is how the model reads it, but nobody typed it.
 *
 * [key] identifies the delivery across compression, where Hermes may copy a row under a new id.
 * [interim] marks an early warning that one task of a batch failed; the batch's results still follow.
 */
data class BackgroundResult(
    val key: String,
    val delegationId: String?,
    val status: BackgroundStatus,
    val interim: Boolean,
    val summary: String,
    val taskCount: Int? = null,
    val failedCount: Int = 0,
)

/**
 * Reads detached delegate_task work from a Hermes transcript: which background runs the agent started
 * and what they delivered. The text formats are Hermes' tools/process_registry_notifications.py and
 * tools/delegate_tool_dispatch.py; the API strips display_metadata, so the headers are all there is.
 */
object BackgroundWork {
    const val DISPLAY_KIND = "async_delegation_complete"

    // [ASYNC DELEGATION COMPLETE — id], [ASYNC DELEGATION BATCH COMPLETE — id] and
    // [ASYNC DELEGATION TASK FAILED — id, task 2/3]. Hermes writes an em dash; accept a plain one too.
    private val header = Regex("""^\[ASYNC DELEGATION (BATCH COMPLETE|COMPLETE|TASK FAILED)\s*[—–-]\s*([^\],]+?)\s*(?:,\s*task (\d+)/(\d+))?]""")
    private val taskHeader = Regex("""^--- ([✓✗⚠]) TASK \d+/\d+""", RegexOption.MULTILINE)
    private val statusLine = Regex("""^Status: (\S+)""", RegexOption.MULTILINE)
    private val doneStatuses = setOf("completed", "success")
    private val delegationIdPattern = Regex(""""delegation_id"\s*:\s*"([^"]+)"""")

    fun isDelivery(message: HermesMessage): Boolean {
        if (message.role != "user") return false
        if (message.displayKind == DISPLAY_KIND) return true
        // Servers that don't send display_kind still write the header.
        return message.displayKind.isNullOrBlank() && message.content.contentText().trimStart().startsWith("[ASYNC DELEGATION ")
    }

    fun parse(message: HermesMessage): BackgroundResult {
        val text = message.content.contentText().trim()
        val match = header.find(text)
        val fallbackKey = "row:" + (message.id ?: text.hashCode().toString())
        if (match == null) {
            return BackgroundResult(fallbackKey, null, BackgroundStatus.Completed, interim = false, summary = text)
        }
        val (kind, id, task, total) = match.destructured
        val delegationId = id.trim()
        return when (kind) {
            "TASK FAILED" -> BackgroundResult(
                key = "$delegationId#task$task",
                delegationId = delegationId,
                status = BackgroundStatus.Failed,
                interim = true,
                summary = text.substring(match.range.last + 1).trim(),
                taskCount = total.toIntOrNull(),
                failedCount = 1,
            )
            "BATCH COMPLETE" -> {
                val icons = taskHeader.findAll(text).map { it.groupValues[1] }.toList()
                val ok = icons.count { it == "✓" }
                val status = when {
                    icons.isEmpty() || ok == 0 -> BackgroundStatus.Failed
                    ok < icons.size -> BackgroundStatus.PartlyFailed
                    else -> BackgroundStatus.Completed
                }
                val start = listOfNotNull(taskHeader.find(text)?.range?.first, text.indexOf("--- ERROR ---").takeIf { it >= 0 }).minOrNull()
                BackgroundResult(
                    key = delegationId,
                    delegationId = delegationId,
                    status = status,
                    interim = false,
                    summary = start?.let { text.substring(it).trim() } ?: text,
                    taskCount = icons.size.takeIf { it > 0 },
                    failedCount = icons.size - ok,
                )
            }
            else -> {
                val status = statusLine.find(text)?.groupValues?.get(1)?.lowercase()
                val done = status == null || status in doneStatuses
                val result = text.substringAfter("--- RESULT ---", "").trim()
                BackgroundResult(
                    key = delegationId,
                    delegationId = delegationId,
                    status = if (done) BackgroundStatus.Completed else BackgroundStatus.Failed,
                    interim = false,
                    summary = result.ifEmpty { text },
                    taskCount = 1,
                    failedCount = if (done) 0 else 1,
                )
            }
        }
    }

    /**
     * The delegation ids a delegate_task result says will report back later: one per completion unit
     * when Hermes split the batch, else the call's own id. Empty for work that ran in the foreground.
     */
    fun dispatchedIds(message: HermesMessage): List<String> {
        if (message.role != "tool") return emptyList()
        val text = message.content.contentText()
        val o = runCatching { HermesJson.parseToJsonElement(text) }.getOrNull().asObject()
            // A result Hermes shortened is no longer valid JSON; its ids are still in there.
            ?: return if (text.contains("\"dispatched\"")) delegationIdPattern.findAll(text).map { it.groupValues[1] }.distinct().toList() else emptyList()
        if (o.str("status") != "dispatched" && o.str("mode") != "background") return emptyList()
        val units = o["units"].asArray()?.mapNotNull { (it as? JsonObject)?.str("delegation_id") }.orEmpty()
        return units.ifEmpty { listOfNotNull(o.str("delegation_id")) }
    }

    /**
     * Background runs started at or after [sinceSeconds] (server time) that haven't delivered their
     * results yet. Early failure notices don't count: the batch still reports when its last task ends.
     */
    fun outstanding(messages: List<HermesMessage>, sinceSeconds: Double): Set<String> {
        val started = mutableSetOf<String>()
        val delivered = mutableSetOf<String>()
        messages.forEach { message ->
            if (isDelivery(message)) {
                val result = parse(message)
                if (!result.interim) result.delegationId?.let(delivered::add)
            } else if ((message.timestamp ?: Double.MAX_VALUE) >= sinceSeconds) {
                started += dispatchedIds(message)
            }
        }
        return started - delivered
    }
}
