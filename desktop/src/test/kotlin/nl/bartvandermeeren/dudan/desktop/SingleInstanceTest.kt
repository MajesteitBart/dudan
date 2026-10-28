package nl.bartvandermeeren.dudan.desktop

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleInstanceTest {
    private fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test
    fun aSecondStartShowsTheFirstAndStops() {
        val port = freePort()
        val shown = CountDownLatch(1)
        val first = SingleInstance("bart|", port) { shown.countDown() }
        try {
            assertTrue(first.claim())
            assertFalse(SingleInstance("bart|", port) {}.claim())
            assertTrue("the first dudan was asked to show itself", shown.await(2, TimeUnit.SECONDS))
        } finally {
            first.release()
        }
    }

    @Test
    fun anotherProgramOnThePortDoesNotKeepDudanFromStarting() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { stranger ->
            // It accepts the connection but never answers as dudan would.
            assertTrue(SingleInstance("bart|", stranger.localPort) {}.claim())
        }
    }

    @Test
    fun eachAccountAndProfileIsADudanOfItsOwn() {
        val installed = SingleInstance.identityOf("Bart", null)
        val profile = SingleInstance.identityOf("Bart", "E:/tmp/dudan-dev")
        assertTrue(profile != installed)
        assertTrue(SingleInstance.identityOf("Sanne", null) != installed)
        // The same folder, written another way, is the same profile, and a blank one is none.
        assertTrue(profile == SingleInstance.identityOf("bart", "e:\\TMP\\dudan-dev"))
        assertTrue(installed == SingleInstance.identityOf("Bart", " "))
        assertTrue(SingleInstance.portFor(installed) in 48652 until 49652)
    }

    @Test
    fun anotherAccountsDudanOnThePortIsNotThisOne() {
        val port = freePort()
        var shown = false
        val other = SingleInstance("sanne|", port) { shown = true }
        try {
            assertTrue(other.claim())
            // Bart's dudan finds Sanne's on the port, which doesn't answer him, so his runs.
            assertTrue(SingleInstance("bart|", port) {}.claim())
            Thread.sleep(200)
            assertFalse("the other account's dudan was asked to show itself", shown)
        } finally {
            other.release()
        }
    }
}
