package nl.bartvandermeeren.dudan

import nl.bartvandermeeren.dudan.assist.Opener
import nl.bartvandermeeren.dudan.assist.waitsForService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenEntryTest {
    @Test
    fun theListenEntryAndTheWakeWordListenAlsoWithListenRightAwayOff() {
        assertTrue(Opener.ListenEntry.listens(listenOnInvoke = false, hasMic = true))
        assertTrue(Opener.WakeWord.listens(listenOnInvoke = false, hasMic = true))
        assertFalse(Opener.Assist.listens(listenOnInvoke = false, hasMic = true))
        Opener.entries.forEach { assertTrue(it.listens(listenOnInvoke = true, hasMic = true)) }
    }

    @Test
    fun withoutTheMicrophoneOnlyTheSideButtonOpensForTyping() {
        // Listening without the microphone opens the app to ask for it. Clark and the wake word were
        // opened to listen, so they ask; the side button may have been opened to type.
        assertTrue(Opener.ListenEntry.listens(listenOnInvoke = false, hasMic = false))
        assertTrue(Opener.WakeWord.listens(listenOnInvoke = false, hasMic = false))
        assertFalse(Opener.Assist.listens(listenOnInvoke = true, hasMic = false))
    }

    @Test
    fun openingAgainWhileOpenListensAgainForTheWakeWordAndTheListenEntry() {
        assertTrue(Opener.ListenEntry.listensAgain)
        assertTrue(Opener.WakeWord.listensAgain)
        assertFalse(Opener.Assist.listensAgain)
    }

    @Test
    fun theShowArgumentsNameTheOpener() {
        Opener.entries.forEach { assertEquals(it, Opener.from(it.name)) }
        // The side button and other apps asking for the assistant name none.
        assertEquals(Opener.Assist, Opener.from(null))
        assertEquals(Opener.Assist, Opener.from("Bixby"))
    }

    @Test
    fun itWaitsOnlyForAnAssistantServiceThatIsStillConnecting() {
        assertTrue(waitsForService(connected = false, isAssistant = true))
        // A connected service that failed to show won't connect again, so the app opens right away.
        assertFalse(waitsForService(connected = true, isAssistant = true))
        // Without the assistant role there's no service to wait for.
        assertFalse(waitsForService(connected = false, isAssistant = false))
    }
}
