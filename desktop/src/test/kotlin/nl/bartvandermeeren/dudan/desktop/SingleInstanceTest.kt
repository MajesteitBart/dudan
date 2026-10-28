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
        val first = SingleInstance(port) { shown.countDown() }
        try {
            assertTrue(first.claim())
            assertFalse(SingleInstance(port) {}.claim())
            assertTrue("the first dudan was asked to show itself", shown.await(2, TimeUnit.SECONDS))
        } finally {
            first.release()
        }
    }

    @Test
    fun anotherProgramOnThePortDoesNotKeepDudanFromStarting() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { stranger ->
            // It accepts the connection but never answers as dudan would.
            assertTrue(SingleInstance(stranger.localPort) {}.claim())
        }
    }

    @Test
    fun aTestProfileGetsAPortOfItsOwn() {
        val installed = SingleInstance.portFor(null)
        val profile = SingleInstance.portFor("E:/tmp/dudan-dev")
        assertTrue(profile != installed)
        assertTrue(profile in installed + 1..installed + 1000)
        // The same folder, written another way, is the same profile.
        assertTrue(profile == SingleInstance.portFor("e:\\TMP\\dudan-dev"))
        assertTrue(installed == SingleInstance.portFor(" "))
    }
}
