package nl.bartvandermeeren.dudan

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchIntentTest {
    /** The flags the overlay starts the app with when it hands off a chat. */
    private val handOff = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    @Test
    fun aHandOffThatStartsTheAppIsHandled() {
        assertTrue(handlesLaunchIntent(restored = false, handOff))
    }

    @Test
    fun reopeningFromRecentsDoesNotReplayTheHandOff() {
        assertFalse(handlesLaunchIntent(restored = false, handOff or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
    }

    @Test
    fun aRestoredActivityDoesNotHandleItAgain() {
        assertFalse(handlesLaunchIntent(restored = true, handOff))
    }
}
