package nl.bartvandermeeren.dudan.chat

import nl.bartvandermeeren.dudan.data.ApprovalRequest
import nl.bartvandermeeren.dudan.data.FileRef
import nl.bartvandermeeren.dudan.data.ModelChoice
import nl.bartvandermeeren.dudan.data.SessionSummary

/** [Background] is a result a subagent delivered after the agent's turn ended; see [BackgroundResult]. */
enum class Role { User, Assistant, Background }

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
    /** Files the user attached; they are on the Hermes host, see data/AttachmentNotes. */
    val files: List<FileRef> = emptyList(),
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
    /** Set on [Role.Background] items. */
    val background: BackgroundResult? = null,
    /** Set on a user turn sent by "Review result and finish"; a retry checks again that nobody continued meanwhile. */
    val review: ReviewRequest? = null,
) {
    val isStreaming: Boolean get() = state == MessageState.Streaming
    val reviewsBackground: Boolean get() = review != null
    val workedMs: Long? get() = if (startedAtMs != null && finishedAtMs != null) finishedAtMs - startedAtMs else null
}

/**
 * How a "Review result and finish" turn asked Hermes for its run: the idempotency key and, once sent,
 * the model and whether rich replies were on. A retry asks again with exactly these, since Hermes only
 * matches a repeated key with the same request.
 */
data class ReviewRequest(val key: String, val model: ModelChoice? = null, val richReplies: Boolean? = null)

data class Conversation(
    val sessionId: String,
    val messages: List<UiMessage> = emptyList(),
    /** Created locally; the Hermes session row does not exist until the first message is sent. */
    val isNew: Boolean = false,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadError: String? = null,
    /** "Review and finish" is checking with Hermes before it starts the turn. */
    val reviewing: Boolean = false,
    val reviewError: String? = null,
) {
    val isBusy: Boolean get() = messages.lastOrNull()?.isStreaming == true
    val showGreeting: Boolean get() = messages.isEmpty() && !loading && loadError == null

    /**
     * Background results came in after the agent's last turn and it hasn't looked at them. Only the
     * user continues from here: arriving results never start a turn by themselves. An early failure
     * notice alone doesn't count, because the rest of its batch is still running.
     */
    val awaitingReview: Boolean
        get() = messages.takeLastWhile { it.role == Role.Background }.any { it.background?.interim == false }
}

data class SessionsState(
    val items: List<SessionSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loadedOnce: Boolean = false,
)
