package nl.bartvandermeeren.dudan.chat

import nl.bartvandermeeren.dudan.data.AgentEvent
import nl.bartvandermeeren.dudan.data.RunOutcome

/** Folds streamed agent events into the assistant message of the running turn. Pure, so it is unit tested. */
object TurnReducer {
    fun apply(message: UiMessage, event: AgentEvent, nowMs: Long): UiMessage = when (event) {
        is AgentEvent.RunStarted, AgentEvent.StreamClosed -> message

        is AgentEvent.TextDelta -> message.copy(text = message.text + event.text, reconnecting = false)

        is AgentEvent.Reasoning -> message.copy(reasoning = message.reasoning + event.text)

        is AgentEvent.ToolStarted -> message.copy(
            steps = message.steps + Step(StepKind.Tool, event.tool, detail = event.preview, running = true),
        )

        is AgentEvent.ToolFinished -> {
            val index = message.steps.indexOfLast { it.running && it.kind == StepKind.Tool && it.title == event.tool }
            if (index < 0) {
                message.copy(
                    steps = message.steps + Step(
                        StepKind.Tool, event.tool, result = event.preview, failed = event.failed, durationSec = event.durationSec,
                    ),
                )
            } else {
                message.copy(
                    steps = message.steps.toMutableList().also {
                        it[index] = it[index].copy(
                            running = false, failed = event.failed, durationSec = event.durationSec, result = event.preview,
                        )
                    },
                )
            }
        }

        is AgentEvent.Commentary -> {
            // Commentary that was already streamed as text belongs in the steps, not in the answer.
            val text = if (event.alreadyStreamed) message.text.removeLastOccurrence(event.text).trimEnd() else message.text
            message.copy(text = text, steps = message.steps + Step(StepKind.Commentary, event.text.trim()))
        }

        is AgentEvent.ApprovalRequested -> message.copy(approval = event.request)

        is AgentEvent.ApprovalResolved -> {
            val current = message.approval
            val matches = current != null && (event.requestId == null || current.requestId == null || current.requestId == event.requestId)
            if (matches) message.copy(approval = null) else message
        }

        is AgentEvent.SubagentStarted -> message.copy(
            steps = message.steps + Step(StepKind.Subagent, event.preview?.take(160) ?: "Subagent", running = true),
        )

        is AgentEvent.SubagentFinished -> {
            val index = message.steps.indexOfFirst { it.running && it.kind == StepKind.Subagent }
            if (index < 0) {
                message
            } else {
                message.copy(
                    steps = message.steps.toMutableList().also {
                        it[index] = it[index].copy(
                            running = false,
                            result = event.summary,
                            failed = event.status != null && event.status !in setOf("completed", "success", "ok", "done"),
                        )
                    },
                )
            }
        }

        is AgentEvent.FinalText -> if (event.content.isNotBlank()) message.copy(text = event.content) else message

        is AgentEvent.Finished -> finish(message, event.outcome, event.output, event.error, nowMs)

        is AgentEvent.Failed -> message.copy(
            state = MessageState.Failed,
            error = event.message,
            finishedAtMs = nowMs,
            approval = null,
            steps = message.steps.stopRunning(),
            reconnecting = false,
            stopping = false,
        )
    }

    /** Settles a turn. A failed or interrupted run stays failed even when it streamed some text first. */
    fun finish(message: UiMessage, outcome: RunOutcome, output: String?, error: String?, nowMs: Long): UiMessage {
        val state = when (outcome) {
            RunOutcome.Completed -> MessageState.Done
            RunOutcome.Cancelled -> MessageState.Cancelled
            RunOutcome.Failed, RunOutcome.Interrupted -> MessageState.Failed
        }
        return message.copy(
            text = output?.takeIf { it.isNotBlank() } ?: message.text,
            state = state,
            error = if (state == MessageState.Failed) error ?: message.error ?: "The run did not finish." else null,
            finishedAtMs = nowMs,
            approval = null,
            steps = message.steps.stopRunning(),
            reconnecting = false,
            stopping = false,
        )
    }

    private fun List<Step>.stopRunning() = map { if (it.running) it.copy(running = false) else it }

    private fun String.removeLastOccurrence(part: String): String {
        val index = lastIndexOf(part)
        return if (index < 0) this else removeRange(index, index + part.length)
    }
}
