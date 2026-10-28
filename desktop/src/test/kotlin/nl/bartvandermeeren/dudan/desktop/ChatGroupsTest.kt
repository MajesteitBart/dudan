package nl.bartvandermeeren.dudan.desktop

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.bartvandermeeren.dudan.chat.ChatEngine
import nl.bartvandermeeren.dudan.data.SessionSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGroupsTest {
    private val now = 1_800_000_000_000L
    private val nowS = now / 1000.0

    private fun chat(id: String, activeAgo: Double, title: String = id, pinned: Boolean = false, startedAgo: Double = activeAgo) =
        SessionSummary(id, title, "first question of $id", "api_server", nowS - startedAgo, nowS - activeAgo, pinned, 2)

    private val dir: File = Files.createTempDirectory("dudan-chats").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun eachChatLandsInOneGroupWorkingFirst() {
        val sessions = listOf(chat("open", 60.0), chat("running", 30.0), chat("snoozed", 600.0), chat("settled", 3600.0), chat("snoozedAndRunning", 10.0))
        val marks = ServerMarks(
            baseline = nowS - 10_000,
            chats = mapOf(
                "snoozed" to ChatMark(snoozedUntil = now + 3_600_000),
                "settled" to ChatMark(settled = nowS - 3600.0),
                "snoozedAndRunning" to ChatMark(snoozedUntil = now + 3_600_000),
            ),
        )
        val activity = mapOf("running" to ChatEngine.Activity(now - 5_000, turn = true), "snoozedAndRunning" to ChatEngine.Activity(now, turn = false))
        val groups = ChatGroups.arrange(sessions, marks, activity, now, ThreadSort.Activity)
        assertEquals(listOf("open"), groups.open.map { it.session.id })
        assertEquals(listOf("snoozedAndRunning", "running"), groups.working.map { it.session.id })
        assertEquals(listOf("snoozed"), groups.snoozed.map { it.session.id })
        assertEquals(listOf("settled"), groups.settled.map { it.session.id })
    }

    @Test
    fun aSettledChatComesBackWhenSomethingNewArrives() {
        val mark = ChatMark(settled = nowS - 3600.0)
        assertTrue(ChatGroups.isSettled(chat("a", 3600.0), mark))
        assertFalse(ChatGroups.isSettled(chat("a", 60.0), mark))
    }

    @Test
    fun anExpiredSnoozeNoLongerHides() {
        val marks = ServerMarks(chats = mapOf("a" to ChatMark(snoozedUntil = now - 1)))
        val groups = ChatGroups.arrange(listOf(chat("a", 60.0)), marks, emptyMap(), now, ThreadSort.Activity)
        assertEquals(listOf("a"), groups.open.map { it.session.id })
    }

    @Test
    fun unreadCountsFromTheFirstListAndFromWhatWasSeen() {
        val old = chat("old", 7200.0)
        val new = chat("new", 60.0)
        // Nothing is unread before the first list sets the baseline.
        assertFalse(ChatGroups.isUnread(new, ChatMark(), baseline = null))
        assertFalse(ChatGroups.isUnread(old, ChatMark(), baseline = nowS - 3600))
        assertTrue(ChatGroups.isUnread(new, ChatMark(), baseline = nowS - 3600))
        assertFalse(ChatGroups.isUnread(new, ChatMark(seen = nowS - 60), baseline = nowS - 3600))
        assertTrue(ChatGroups.isUnread(old, ChatMark(unread = true), baseline = nowS))
        // A chat that exists only here goes by this PC's clock, so it isn't new by time; marked, it is.
        val local = new.copy(local = true)
        assertFalse(ChatGroups.isUnread(local, ChatMark(), baseline = nowS - 3600))
        assertTrue(ChatGroups.isUnread(local, ChatMark(unread = true), baseline = nowS - 3600))
    }

    @Test
    fun pinnedChatsLeadEverySort() {
        val sessions = listOf(chat("b", 10.0, title = "Beta", startedAgo = 500.0), chat("a", 20.0, title = "alpha", startedAgo = 100.0), chat("p", 900.0, title = "Zulu", pinned = true))
        fun order(sort: ThreadSort) = ChatGroups.arrange(sessions, ServerMarks(), emptyMap(), now, sort).open.map { it.session.id }
        assertEquals(listOf("p", "b", "a"), order(ThreadSort.Activity))
        assertEquals(listOf("p", "a", "b"), order(ThreadSort.Created))
        assertEquals(listOf("p", "a", "b"), order(ThreadSort.Title))
    }

    @Test
    fun searchLooksAtTitlesAndFirstQuestions() {
        val sessions = listOf(chat("one", 10.0, title = "Holiday plans"), chat("two", 20.0, title = "Taxes"))
        val groups = ChatGroups.arrange(sessions, ServerMarks(), emptyMap(), now, ThreadSort.Activity)
        assertEquals(listOf("one"), groups.matching("holiday").open.map { it.session.id })
        assertEquals(listOf("two"), groups.matching("question of two").open.map { it.session.id })
    }

    @Test
    fun aChatTheSearchHidesKeepsItsState() {
        val sessions = listOf(chat("settled", 3600.0, title = "Taxes"), chat("open", 60.0, title = "Holiday plans"))
        val groups = ChatGroups.arrange(sessions, ServerMarks(chats = mapOf("settled" to ChatMark(settled = nowS))), emptyMap(), now, ThreadSort.Activity)
        val shown = groups.matching("holiday")
        assertEquals(null, shown.row("settled"))
        // What the menu and Ctrl+E go by: the unfiltered groups.
        assertEquals(ChatGroup.Settled, groups.row("settled")?.group)
    }

    @Test
    fun theListWalksVisibleGroupsInOrder() {
        val sessions = listOf(chat("open", 60.0), chat("settled", 3600.0))
        val marks = ServerMarks(chats = mapOf("settled" to ChatMark(settled = nowS)))
        val groups = ChatGroups.arrange(sessions, marks, emptyMap(), now, ThreadSort.Activity)
        assertEquals(listOf("open"), groups.visible(setOf(ChatGroup.Settled)).map { it.session.id })
        assertEquals(listOf("open", "settled"), groups.visible(emptySet()).map { it.session.id })
    }

    @Test
    fun timesReadAsInT3Code() {
        assertEquals("now", ChatTimes.ago(nowS - 20, now, "now"))
        assertEquals("5m", ChatTimes.ago(nowS - 5 * 60, now, "now"))
        assertEquals("4h", ChatTimes.ago(nowS - 4 * 3600 - 59, now, "now"))
        assertEquals("3d", ChatTimes.ago(nowS - 3 * 86400, now, "now"))
        assertEquals("2w", ChatTimes.ago(nowS - 15 * 86400, now, "now"))
        assertEquals("15 Nov 2026", ChatTimes.ago(LocalDateTime.of(2026, 11, 15, 12, 0).toEpochSecond(ZoneOffset.UTC).toDouble(), now, "now", Locale.UK, ZoneId.of("UTC")))
        assertEquals("45m", ChatTimes.left(now + 45 * 60_000, now))
        assertEquals("4h", ChatTimes.left(now + 4 * 3_600_000 - 60_000, now))
        assertEquals("5d", ChatTimes.left(now + 5 * 86_400_000, now))
        assertEquals("2m", ChatTimes.running(now - 150_000, now))
    }

    @Test
    fun snoozesEndOnTheHourOrTheMorning() {
        val zone = ZoneId.of("Europe/Amsterdam")
        // A Wednesday evening.
        val wednesday = LocalDateTime.of(2026, 10, 28, 21, 13).atZone(zone).toInstant().toEpochMilli()
        fun at(option: SnoozeOption) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(option.until(wednesday, zone)), zone)
        assertEquals(LocalDateTime.of(2026, 10, 28, 22, 13), at(SnoozeOption.OneHour))
        assertEquals(LocalDateTime.of(2026, 10, 29, 9, 0), at(SnoozeOption.Tomorrow))
        assertEquals(LocalDateTime.of(2026, 11, 2, 9, 0), at(SnoozeOption.NextWeek))
    }

    @Test
    fun marksArePerServerAndSurviveARestart() {
        val marks = ChatMarks(dir, scope)
        marks.use("http://clark:8642")
        marks.startUnreadFrom(nowS - 100)
        marks.settle("a", nowS - 50)
        marks.snooze("b", now - 1)
        marks.wake(now)
        assertTrue(marks.current.value.of("b").unread)
        assertEquals(null, marks.current.value.of("b").snoozedUntil)
        marks.use("http://other:8642")
        assertEquals(ServerMarks(), marks.current.value)

        Thread.sleep(200)
        val again = ChatMarks(dir, scope).apply { use("http://clark:8642") }
        assertEquals(nowS - 100, again.current.value.baseline!!, 0.0)
        assertEquals(nowS - 50, again.current.value.of("a").settled!!, 0.0)
        assertTrue(again.current.value.of("b").unread)
        // Without a Hermes time, seeing it only clears unread: it sets no line for what counts as new.
        again.markUnread("c")
        again.seen("c", null)
        assertEquals(ChatMark(), again.current.value.of("c"))
        // Seeing it clears unread; a chat with nothing left to remember drops out of the file.
        again.seen("b", nowS)
        assertFalse(again.current.value.of("b").unread)
        again.forget("a")
        assertFalse("a" in again.current.value.chats)
    }
}
