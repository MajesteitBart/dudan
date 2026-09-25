package nl.bartvandermeeren.aight.chat

import nl.bartvandermeeren.aight.data.ApprovalRequest
import nl.bartvandermeeren.aight.data.SessionSummary

enum class Role { User, Assistant }

enum class MessageState { Streaming, Done, Failed, Cancelled }

enum class StepKind { Tool, Commentary, Subagent }

data class Step(
    val kind: StepKind,
    val title: String,
    val detail: String? = null,
    val result: String? = null,
    val running: Boolean = false,
    val failed: Boolean = false,
    val durationSec: Double? = null,
    val callId: String? = null,
)

/** An image in a message; [source] is a data: URL or a content:// URI. */
data class ImageRef(val source: String)

data class UiMessage(
    val id: String,
    val role: Role,
    val text: String = "",
    val images: List<ImageRef> = emptyList(),
    val steps: List<Step> = emptyList(),
    val reasoning: String = "",
    val state: MessageState = MessageState.Done,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val approval: ApprovalRequest? = null,
    val error: String? = null,
    /** True while the stream dropped and the app is polling Hermes for the run result. */
    val reconnecting: Boolean = false,
    /** Stop was requested; waiting for Hermes to confirm the run ended. */
    val stopping: Boolean = false,
) {
    val isStreaming: Boolean get() = state == MessageState.Streaming
    val workedMs: Long? get() = if (startedAtMs != null && finishedAtMs != null) finishedAtMs - startedAtMs else null
}

data class Conversation(
    val sessionId: String,
    val messages: List<UiMessage> = emptyList(),
    /** Created locally; the Hermes session row does not exist until the first message is sent. */
    val isNew: Boolean = false,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadError: String? = null,
) {
    val isBusy: Boolean get() = messages.lastOrNull()?.isStreaming == true
}

data class SessionsState(
    val items: List<SessionSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loadedOnce: Boolean = false,
)
