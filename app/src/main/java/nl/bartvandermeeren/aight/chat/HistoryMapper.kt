package nl.bartvandermeeren.aight.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.bartvandermeeren.aight.data.AttachmentNotes
import nl.bartvandermeeren.aight.data.HermesJson
import nl.bartvandermeeren.aight.data.HermesMessage
import nl.bartvandermeeren.aight.data.contentImages
import nl.bartvandermeeren.aight.data.contentText

/**
 * Turns a Hermes transcript into chat bubbles. Tool calls and the text a model writes beside them
 * collapse into the steps of the next answer, the way Gemini folds its work under one reply.
 */
object HistoryMapper {
    fun map(messages: List<HermesMessage>): List<UiMessage> {
        val out = mutableListOf<UiMessage>()
        val steps = mutableListOf<Step>()
        val reasoning = StringBuilder()
        var turnStartedAt: Long? = null

        fun flushWork(index: Int, finishedAt: Long?) {
            if (steps.isEmpty() && reasoning.isEmpty()) return
            out += UiMessage(
                id = "h_work_$index",
                role = Role.Assistant,
                steps = steps.toList(),
                reasoning = reasoning.toString(),
                startedAtMs = turnStartedAt,
                finishedAtMs = finishedAt,
            )
            steps.clear()
            reasoning.setLength(0)
        }

        messages.forEachIndexed { index, message ->
            if (message.displayKind == "hidden") return@forEachIndexed
            val timestampMs = message.timestamp?.let { (it * 1000).toLong() }
            when (message.role) {
                "user" -> {
                    flushWork(index, timestampMs)
                    // Attached files travel as notes in the text; show them as files again.
                    val (text, files) = AttachmentNotes.parse(message.content.contentText())
                    val images = message.content.contentImages().map(::ImageRef)
                    if (text.isNotBlank() || images.isNotEmpty() || files.isNotEmpty()) {
                        out += UiMessage(id = message.id?.let { "h_$it" } ?: "h_$index", role = Role.User, text = text, images = images, files = files)
                    }
                    turnStartedAt = timestampMs
                }
                "assistant" -> {
                    message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning.append(it.trim()).append("\n\n") }
                    val text = message.content.contentText()
                    if (message.toolCalls.isNotEmpty()) {
                        if (text.isNotBlank()) steps += Step(StepKind.Commentary, text.trim())
                        message.toolCalls.forEach { call ->
                            steps += Step(StepKind.Tool, call.name, detail = summarizeArguments(call.arguments), callId = call.id)
                        }
                    } else if (text.isNotBlank()) {
                        out += UiMessage(
                            id = message.id?.let { "h_$it" } ?: "h_$index",
                            role = Role.Assistant,
                            text = text,
                            steps = steps.toList(),
                            reasoning = reasoning.toString().trim(),
                            startedAtMs = turnStartedAt,
                            finishedAtMs = timestampMs,
                        )
                        steps.clear()
                        reasoning.setLength(0)
                    }
                }
                "tool" -> {
                    val result = message.content.contentText()
                    val stepIndex = steps.indexOfLast { it.callId != null && it.callId == message.toolCallId }
                        .takeIf { it >= 0 } ?: steps.indexOfLast { it.kind == StepKind.Tool && it.result == null }
                    if (stepIndex >= 0) {
                        steps[stepIndex] = steps[stepIndex].copy(result = result.take(500), failed = looksFailed(result))
                    }
                }
            }
        }
        flushWork(messages.size, null)
        return out
    }

    /** One-line summary of tool arguments: the command, query, URL or path when there is one. */
    fun summarizeArguments(arguments: String?): String? {
        if (arguments.isNullOrBlank()) return null
        val obj = runCatching { HermesJson.parseToJsonElement(arguments) }.getOrNull() as? JsonObject
            ?: return arguments.take(200)
        val preferred = listOf("command", "query", "url", "path", "file_path", "goal", "task", "prompt", "text", "name")
        val value = preferred.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull }
            ?: obj.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        return value?.take(200)
    }

    private fun looksFailed(result: String): Boolean {
        val head = result.take(200).lowercase()
        return head.startsWith("{\"error\"") || head.startsWith("error:") || head.startsWith("blocked:")
    }
}
