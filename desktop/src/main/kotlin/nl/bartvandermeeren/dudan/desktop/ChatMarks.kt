package nl.bartvandermeeren.dudan.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the chat list remembers about one chat beside what Hermes keeps (its title and pin). */
@Serializable
data class ChatMark(
    /** The chat's last activity (Hermes' clock, in seconds) when it was last on screen. */
    val seen: Double? = null,
    /** Marked unread by hand, or by a snooze that ran out. */
    val unread: Boolean = false,
    /** The chat's last activity when it was settled; anything newer brings it back. */
    val settled: Double? = null,
    /** When the snooze ends, in epoch milliseconds. */
    val snoozedUntil: Long? = null,
) {
    val isEmpty: Boolean get() = this == ChatMark()
}

/** One server's marks. Chats active after [baseline] that were never on screen count as unread. */
@Serializable
data class ServerMarks(val baseline: Double? = null, val chats: Map<String, ChatMark> = emptyMap()) {
    fun of(id: String): ChatMark = chats[id] ?: ChatMark()
}

/**
 * The chat list's own state per chat: seen, unread, settled and snoozed, kept per server in chats.json
 * beside settings.json. It stays on this PC, as T3 Code's sidebar state stays on its machine; Hermes
 * has no field for it, and the phone doesn't show it.
 */
class ChatMarks(private val dir: File, private val scope: CoroutineScope) {
    @Serializable
    private data class Stored(val servers: Map<String, ServerMarks> = emptyMap())

    private val file = File(dir, "chats.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Mutex()
    // Changed on the Swing thread, written on an IO thread.
    @Volatile private var stored: Stored = runCatching { json.decodeFromString<Stored>(file.readText()) }.getOrElse { Stored() }
    private var server = ""

    private val _current = MutableStateFlow(ServerMarks())
    /** The marks of the server set with [use]. */
    val current: StateFlow<ServerMarks> = _current.asStateFlow()

    /** Switches to [serverUrl]'s marks. */
    fun use(serverUrl: String) {
        server = serverUrl
        _current.value = stored.servers[serverUrl] ?: ServerMarks()
    }

    /** Starts counting unread chats from [lastActive]: what came before the first list stays read. */
    fun startUnreadFrom(lastActive: Double) {
        if (_current.value.baseline == null) change { it.copy(baseline = lastActive) }
    }

    /** The chat was on screen up to [lastActive], Hermes' time. Without one it's only no longer unread. */
    fun seen(id: String, lastActive: Double?) {
        val mark = _current.value.of(id)
        val upTo = listOfNotNull(mark.seen, lastActive).maxOrNull()
        if (!mark.unread && mark.seen == upTo) return
        update(id) { it.copy(seen = upTo, unread = false) }
    }

    fun markUnread(id: String) = update(id) { it.copy(unread = true) }

    fun settle(id: String, lastActive: Double?) = update(id) { it.copy(settled = lastActive ?: 0.0, snoozedUntil = null) }

    fun unsettle(id: String) = update(id) { it.copy(settled = null) }

    fun snooze(id: String, untilMs: Long) = update(id) { it.copy(snoozedUntil = untilMs, settled = null) }

    fun unsnooze(id: String) = update(id) { it.copy(snoozedUntil = null) }

    /** Brings back chats whose snooze ran out by [nowMs], as unread, so they stand out in the list. */
    fun wake(nowMs: Long) {
        val due = _current.value.chats.filterValues { it.snoozedUntil != null && it.snoozedUntil <= nowMs }.keys
        if (due.isEmpty()) return
        change { marks -> marks.copy(chats = marks.chats + due.associateWith { marks.of(it).copy(snoozedUntil = null, unread = true) }) }
    }

    fun forget(id: String) = change { it.copy(chats = it.chats - id) }

    private fun update(id: String, transform: (ChatMark) -> ChatMark) = change { marks ->
        val next = transform(marks.of(id))
        marks.copy(chats = if (next.isEmpty) marks.chats - id else marks.chats + (id to next))
    }

    private fun change(transform: (ServerMarks) -> ServerMarks) {
        if (server.isEmpty()) return
        val next = transform(_current.value)
        if (next == _current.value) return
        _current.value = next
        stored = stored.copy(servers = stored.servers + (server to next))
        val snapshot = stored
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                // The newest state wins: a write queued behind this one carries a later snapshot.
                if (snapshot !== stored) return@withLock
                dir.mkdirs()
                val temp = File(dir, "chats.json.tmp")
                temp.writeText(json.encodeToString(Stored.serializer(), snapshot))
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }
}
