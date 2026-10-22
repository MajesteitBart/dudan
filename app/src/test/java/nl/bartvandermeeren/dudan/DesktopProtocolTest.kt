package nl.bartvandermeeren.dudan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.bartvandermeeren.dudan.data.*
import org.junit.Assert.*
import org.junit.Test

class DesktopProtocolTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/desktop-v1.json")!!.readText()) as JsonObject

    @Test fun sharedFixtureAndOldServers() {
        val caps = ServerCapabilities.from(fixture["capabilities"] as JsonObject)
        assertTrue(caps.desktop!!.control)
        assertNull(ServerCapabilities.from(JsonObject(emptyMap())).desktop)
        val session = DesktopSession.from(fixture["session"] as JsonObject, "synthetic-chat")!!
        assertEquals(DesktopState.Live, session.state)
        assertEquals(1280, session.width)
        assertFalse(session.agentPaused)
        assertNull(DesktopSession.from(fixture["session"] as JsonObject, "other-chat"))
        assertNull(DesktopSession.from(JsonObject((fixture["session"] as JsonObject) + ("version" to JsonPrimitive(2))), "synthetic-chat"))
    }

    @Test fun stateMappingFailsClosed() {
        val base = fixture["session"] as JsonObject
        for ((wire, state) in mapOf("unavailable" to DesktopState.Unavailable, "connecting" to DesktopState.Connecting,
            "human-controlled" to DesktopState.HumanControlled, "reconnecting" to DesktopState.Reconnecting,
            "stopped" to DesktopState.Stopped, "unknown-future-state" to DesktopState.Error)) {
            assertEquals(state, DesktopSession.from(JsonObject(base + ("state" to JsonPrimitive(wire))), "synthetic-chat")!!.state)
        }
    }

    @Test fun aspectFitCoordinatesCoverPortraitLandscapeAndFoldable() {
        assertEquals(640 to 400, DesktopCoordinates.map(200f, 400f, 400f, 800f, 1280, 800))
        assertNull(DesktopCoordinates.map(200f, 100f, 400f, 800f, 1280, 800))
        assertEquals(640 to 400, DesktopCoordinates.map(600f, 300f, 1200f, 600f, 1280, 800))
        assertNull(DesktopCoordinates.map(1f, 300f, 1200f, 600f, 1280, 800))
        assertEquals(640 to 400, DesktopCoordinates.map(442f, 552f, 884f, 1104f, 1280, 800))
        assertNull(DesktopCoordinates.map(Float.NaN, 0f, 100f, 100f, 1280, 800))
        assertNull(DesktopCoordinates.map(0f, 0f, Float.NaN, 100f, 1280, 800))
        assertNull(DesktopCoordinates.map(0f, 0f, 100f, Float.POSITIVE_INFINITY, 1280, 800))
        assertNull(DesktopCoordinates.map(0f, 0f, 0f, 100f, 1280, 800))
        assertNull(DesktopCoordinates.map(1280f, 800f, 1280f, 800f, 1280, 800))
    }

    @Test fun previewsTakeTheDesktopsShapeAndSixteenByTenUntilItIsKnown() {
        assertEquals(1.6f, DesktopCoordinates.aspect(1280, 800), 0.0001f)
        assertEquals(0.5f, DesktopCoordinates.aspect(400, 800), 0.0001f)
        assertEquals(1.6f, DesktopCoordinates.aspect(0, 0), 0.0001f)
        assertEquals(1.6f, DesktopCoordinates.aspect(1280, 0), 0.0001f)
        // At that shape a preview fills its box exactly: no letterbox to tap into.
        val (w, h) = 576f to 576f / DesktopCoordinates.aspect(1280, 800)
        assertEquals(0 to 0, DesktopCoordinates.map(0f, 0f, w, h, 1280, 800))
        assertEquals(1279 to 799, DesktopCoordinates.map(w - 0.01f, h - 0.01f, w, h, 1280, 800))
    }
}
