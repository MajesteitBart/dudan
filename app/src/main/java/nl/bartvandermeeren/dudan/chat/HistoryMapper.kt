package nl.bartvandermeeren.dudan.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.bartvandermeeren.dudan.data.AttachmentNotes
import nl.bartvandermeeren.dudan.data.HermesJson
import nl.bartvandermeeren.dudan.data.HermesMessage
import nl.bartvandermeeren.dudan.data.contentImages
import nl.bartvandermeeren.dudan.data.contentText

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

        // Compression can leave two copies of one delivery; show it once.
        val delivered = mutableSetOf<String>()

        messages.forEachIndexed { index, message ->
            if (message.displayKind == "hidden") return@forEachIndexed
            val timestampMs = message.timestamp?.let { (it * 1000).toLong() }
            // A background result is stored as a user row for the model's sake; nobody typed it.
            if (BackgroundWork.isDelivery(message)) {
                flushWork(index, timestampMs)
                val result = BackgroundWork.parse(message)
                if (delivered.add(result.key)) {
                    out += UiMessage(
                        id = message.id?.let { "h_$it" } ?: "h_$index",
                        role = Role.Background,
                        text = result.summary,
                        finishedAtMs = timestampMs,
                        background = result,
                    )
                }
                turnStartedAt = timestampMs
                return@forEachIndexed
            }
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

    /**
     * Merges a fresh transcript into what a chat shows. The transcript replaces it once it holds the
     * last turn this app ran. That turn is the first question with the same text after the last item
     * the chat already took from the transcript, so turns other clients added later don't hide it.
     * Until the transcript holds it, because the turn never reached Hermes, was stopped, or its reply
     * isn't saved yet, the chat keeps its own turn and only gains the background results it doesn't
     * show, placed before or after that turn as in the transcript. Results are matched by delivery,
     * since compression can copy a row under a new id.
     */
    fun reconcile(shown: List<UiMessage>, transcript: List<UiMessage>): Reconciled {
        val questionIndex = shown.indexOfLast { it.role == Role.User }
        val question = shown.getOrNull(questionIndex)
        if (question == null || question.id.startsWith("h_")) return Reconciled(keepReplyIds(shown, transcript), caughtUp = true)
        val anchor = shown.subList(0, questionIndex).lastOrNull { it.id.startsWith("h_") }?.id
        val from = anchor?.let { id -> transcript.indexOfFirst { it.id == id } + 1 } ?: 0
        val savedQuestion = (from until transcript.size).firstOrNull {
            transcript[it].role == Role.User && transcript[it].text.trim() == question.text.trim()
        }
        val answer = shown.drop(questionIndex + 1).lastOrNull { it.role == Role.Assistant }
        if (savedQuestion != null && holdsTurn(transcript, savedQuestion, answer)) {
            return Reconciled(keepReplyIds(shown, transcript), caughtUp = true)
        }
        val known = shown.mapNotNullTo(mutableSetOf()) { it.background?.key }
        fun results(indices: IntRange) = indices.map { transcript[it] }.filter { it.role == Role.Background && it.background?.key !in known }
        val before = if (savedQuestion != null) results(from until savedQuestion) else emptyList()
        val after = results((savedQuestion?.plus(1) ?: from) until transcript.size)
        return Reconciled(shown.subList(0, questionIndex) + before + shown.subList(questionIndex, shown.size) + after, caughtUp = false)
    }

    /** [caughtUp] is true when the transcript held everything the chat showed and replaced it. */
    data class Reconciled(val messages: List<UiMessage>, val caughtUp: Boolean)

    /** Whether the transcript's copy of this app's last turn, asked at [savedQuestion], can replace it. */
    private fun holdsTurn(transcript: List<UiMessage>, savedQuestion: Int, answer: UiMessage?): Boolean {
        // A stopped turn stays as the user left it; Hermes may have saved half a reply.
        if (answer?.state == MessageState.Cancelled) return false
        val savedReply = transcript.drop(savedQuestion + 1).any { it.role == Role.Assistant && it.text.isNotBlank() }
        return savedReply || answer == null || (answer.state == MessageState.Done && answer.text.isBlank())
    }

    /**
     * Replies this app streamed keep their ids on the saved copies, so a reply being read aloud keeps
     * its stop button. Each question is matched to its saved copy in order (by id when it has one,
     * else the next question with the same text), and a reply only within its own question's turn.
     */
    private fun keepReplyIds(shown: List<UiMessage>, transcript: List<UiMessage>): List<UiMessage> {
        val ids = mutableMapOf<Int, String>()
        var next = 0
        var turn = -1
        shown.forEach { message ->
            if (message.role == Role.User) {
                turn = if (message.id.startsWith("h_")) transcript.indexOfFirst { it.id == message.id }
                else (next until transcript.size).firstOrNull { transcript[it].role == Role.User && transcript[it].text.trim() == message.text.trim() } ?: -1
                if (turn >= 0) next = turn + 1
                return@forEach
            }
            if (message.role != Role.Assistant || message.id.startsWith("h_") || message.text.isBlank() || turn < 0) return@forEach
            val end = (turn + 1 until transcript.size).firstOrNull { transcript[it].role == Role.User } ?: transcript.size
            val reply = (turn + 1 until end).lastOrNull { transcript[it].role == Role.Assistant && transcript[it].text.trim() == message.text.trim() }
            if (reply != null) ids[reply] = message.id
        }
        return if (ids.isEmpty()) transcript else transcript.mapIndexed { index, message -> ids[index]?.let { message.copy(id = it) } ?: message }
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
