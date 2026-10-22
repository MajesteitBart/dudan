package nl.bartvandermeeren.dudan.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.min

/** Trusted server metadata, never parsed from assistant prose or OpenUI. No media URLs on the wire. */
data class DesktopCapabilities(val control: Boolean) {
    companion object {
        fun from(root: JsonObject?): DesktopCapabilities? {
            val o = root?.get("features").asObject()?.get("desktop").asObject() ?: return null
            if ((o["version"] as? JsonPrimitive)?.intOrNull != 1 || o.bool("supported") != true) return null
            if (o["transports"].asArray()?.any { (it as? JsonPrimitive)?.content == "png-poll" } != true) return null
            return DesktopCapabilities(o.bool("control") == true)
        }
    }
}

enum class DesktopState { Unavailable, Connecting, Live, HumanControlled, Reconnecting, Stopped, Error }

data class DesktopSession(
    val id: String, val sessionId: String, val taskId: String, val runId: String, val resourceId: String,
    val state: DesktopState, val agentPaused: Boolean, val epoch: Long, val width: Int, val height: Int,
    val lastFrameAt: Long?, val problem: String?,
) {
    companion object {
        fun from(o: JsonObject, sessionId: String): DesktopSession? {
            if ((o["version"] as? JsonPrimitive)?.intOrNull != 1 || o.str("session_id") != sessionId || o.str("transport") != "png-poll") return null
            val id = o.str("id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,100}")) } ?: return null
            val state = when (o.str("state")) {
                "unavailable" -> DesktopState.Unavailable
                "connecting" -> DesktopState.Connecting
                "live" -> DesktopState.Live
                "human-controlled" -> DesktopState.HumanControlled
                "reconnecting" -> DesktopState.Reconnecting
                "stopped" -> DesktopState.Stopped
                else -> DesktopState.Error
            }
            val width = (o["width"] as? JsonPrimitive)?.intOrNull ?: 0
            val height = (o["height"] as? JsonPrimitive)?.intOrNull ?: 0
            if (width !in 0..8192 || height !in 0..8192) return null
            return DesktopSession(id, sessionId, o.str("task_id").orEmpty(), o.str("run_id").orEmpty(), o.str("resource_id").orEmpty(), state,
                o.bool("agent_paused") != false, (o["epoch"] as? JsonPrimitive)?.longOrNull ?: return null, width, height,
                (o["last_frame_at"] as? JsonPrimitive)?.longOrNull, o.str("problem"))
        }
    }
}

/** Aspect-fit coordinates. Touches in the letterbox never become desktop input. */
object DesktopCoordinates {
    /** The desktop's width over its height; 16:10 until the server has reported a size. */
    fun aspect(desktopWidth: Int, desktopHeight: Int): Float =
        if (desktopWidth > 0 && desktopHeight > 0) desktopWidth.toFloat() / desktopHeight else 16f / 10f

    fun map(x: Float, y: Float, viewWidth: Float, viewHeight: Float, desktopWidth: Int, desktopHeight: Int): Pair<Int, Int>? {
        if (!viewWidth.isFinite() || !viewHeight.isFinite() || viewWidth <= 0 || viewHeight <= 0 || desktopWidth <= 0 || desktopHeight <= 0 || !x.isFinite() || !y.isFinite()) return null
        val scale = min(viewWidth / desktopWidth, viewHeight / desktopHeight)
        val left = (viewWidth - desktopWidth * scale) / 2
        val top = (viewHeight - desktopHeight * scale) / 2
        if (x < left || y < top || x >= left + desktopWidth * scale || y >= top + desktopHeight * scale) return null
        return ((x - left) / scale).toInt().coerceIn(0, desktopWidth - 1) to ((y - top) / scale).toInt().coerceIn(0, desktopHeight - 1)
    }
}
