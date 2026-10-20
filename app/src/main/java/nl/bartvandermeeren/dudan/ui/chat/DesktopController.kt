package nl.bartvandermeeren.dudan.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import nl.bartvandermeeren.dudan.data.DesktopSession
import nl.bartvandermeeren.dudan.data.DesktopState
import nl.bartvandermeeren.dudan.data.HermesApi

data class DesktopViewState(val session: DesktopSession, val bitmap: Bitmap? = null, val stale: Boolean = true,
    val receivedAt: Long? = null, val controlling: Boolean = false, val busy: Boolean = false, val error: Boolean = false)

/** Lifetime belongs to the visible card/viewer. Secrets stay in memory and never enter chat/export/settings. */
class DesktopController(private val api: HermesApi, initial: DesktopSession,
    private val decodeFrame: (ByteArray) -> Bitmap? = ::decodeDesktopFrame) {
    private val mutable = MutableStateFlow(DesktopViewState(initial))
    val state = mutable.asStateFlow()
    private val viewer = UUID.randomUUID().toString()
    private val mutex = Mutex()
    private val watcher = Mutex()
    private var lease: String? = null
    private var sequence = 0L
    private var leaseEpoch = 0L
    private var grant: String? = null
    private var grantExpires = 0L
    private var watching = false

    private suspend fun action(name: String, body: JsonObject = buildJsonObject {}): JsonObject =
        api.desktopAction(mutable.value.session.sessionId, mutable.value.session.id, name, viewer, body)

    suspend fun watch(expanded: Boolean) {
        // Compose can start the replacement effect while the old effect is releasing its lease.
        watcher.withLock { watchFrames(expanded) }
    }

    private suspend fun watchFrames(expanded: Boolean) {
        watching = true
        try {
            while (true) {
                try {
                    mutex.withLock {
                        val current = mutable.value.session
                        val detail = api.desktopDetail(current.sessionId, current.id)
                        mutable.value = mutable.value.copy(session = detail)
                        if (detail.state == DesktopState.Stopped) return
                        if (lease != null) {
                            action("renew", buildJsonObject { put("lease", lease) })
                        }
                        if (grant == null || grantExpires - System.currentTimeMillis() < 5000) {
                            val g = action("grant")
                            grant = (g["grant"] as? JsonPrimitive)?.content ?: error("Missing grant")
                            grantExpires = (g["expires_at"] as? JsonPrimitive)?.longOrNull ?: 0
                        }
                        val bytes = api.desktopFrame(current.sessionId, current.id, viewer, grant!!)
                        val bitmap = withContext(Dispatchers.Default) { decodeFrame(bytes) }
                        mutable.value = mutable.value.copy(bitmap = bitmap, stale = false, receivedAt = System.currentTimeMillis(), error = false)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    mutable.value = mutable.value.copy(stale = true, error = true)
                    grant = null
                    release()
                }
                delay(if (expanded) 1000 else 10_000)
            }
        } finally {
            watching = false
            mutable.value = mutable.value.copy(controlling = false, stale = true)
            withContext(NonCancellable) { withTimeoutOrNull(3000) { mutex.withLock { release() } } }
        }
    }

    suspend fun takeControl() = operation {
        check(watching)
        val response = action("acquire")
        lease = (response["lease"] as? JsonPrimitive)?.content ?: error("Missing lease")
        if (!watching) { release(); return@operation }
        leaseEpoch = (response["epoch"] as? JsonPrimitive)?.longOrNull ?: error("Missing epoch")
        sequence = 0
        mutable.value = mutable.value.copy(controlling = true)
        refresh()
    }

    suspend fun handBack() = operation {
        check(watching)
        action("handback", buildJsonObject { lease?.let { put("lease", it) }; put("epoch", mutable.value.session.epoch) })
        lease = null
        mutable.value = mutable.value.copy(controlling = false)
        refresh()
    }

    suspend fun send(input: JsonObject) = operation {
        val token = lease ?: return@operation
        check(watching && !mutable.value.stale)
        sequence++ // A lost response is never retried with the same or a new sequence.
        action("input", buildJsonObject { put("lease", token); put("sequence", sequence); put("epoch", leaseEpoch); put("input", input) })
    }

    private suspend fun refresh() {
        val current = mutable.value.session
        mutable.value = mutable.value.copy(session = api.desktopDetail(current.sessionId, current.id))
    }

    suspend fun release() {
        val token = lease
        lease = null
        mutable.value = mutable.value.copy(controlling = false)
        if (token != null) try { action("release", buildJsonObject { put("lease", token) }) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Server lease expires; agent remains fenced. */ }
    }

    private suspend fun operation(block: suspend () -> Unit) {
        mutex.withLock {
            mutable.value = mutable.value.copy(busy = true)
            try { block(); mutable.value = mutable.value.copy(error = false) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = true, stale = true); release() }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}

private fun decodeDesktopFrame(bytes: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Invalid frame")
}
