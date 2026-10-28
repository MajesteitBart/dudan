package nl.bartvandermeeren.dudan.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CountDownLatch
import javax.swing.SwingUtilities

/**
 * A system-wide shortcut through Win32 RegisterHotKey, so dudan hears it over every other app, the way
 * the phone's side button reaches the assistant. Windows delivers the press as WM_HOTKEY to the thread
 * that registered it, so that thread runs its own message loop until [stop].
 */
class GlobalShortcut(
    private val modifiers: Int = MOD_CONTROL or MOD_ALT,
    private val key: Int = VK_SPACE,
    private val onPress: () -> Unit,
) {
    @Volatile private var threadId = 0
    private var thread: Thread? = null

    /** Registers the shortcut; false when another app already holds it. */
    fun start(): Boolean {
        stop()
        val ready = CountDownLatch(1)
        var registered = false
        thread = Thread({
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            // MOD_NOREPEAT: holding the keys down opens the window once.
            registered = User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID, modifiers or MOD_NOREPEAT, key)
            ready.countDown()
            if (!registered) return@Thread
            val msg = WinUser.MSG()
            try {
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WinUser.WM_HOTKEY && msg.wParam.toInt() == HOTKEY_ID) SwingUtilities.invokeLater(onPress)
                }
            } finally {
                User32.INSTANCE.UnregisterHotKey(null, HOTKEY_ID)
            }
        }, "dudan-shortcut").apply {
            isDaemon = true
            start()
        }
        ready.await()
        if (!registered) thread = null
        return registered
    }

    /** Ends the message loop, which unregisters the shortcut. */
    fun stop() {
        val running = thread ?: return
        User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        running.join(1_000)
        thread = null
    }

    companion object {
        private const val HOTKEY_ID = 1
        const val MOD_ALT = 0x0001
        const val MOD_CONTROL = 0x0002
        const val MOD_SHIFT = 0x0004
        private const val MOD_NOREPEAT = 0x4000
        const val VK_SPACE = 0x20
    }
}
