package nl.bartvandermeeren.dudan.assist

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import nl.bartvandermeeren.dudan.MainActivity
import nl.bartvandermeeren.dudan.appContainer

/**
 * Entry point Android binds to once dudan is the default digital assistant. It doesn't advertise
 * lock-screen launch: that would show chat history over the keyguard, so Android asks to unlock first.
 * The system keeps it bound, so it also starts the wake word listener and opens the overlay for it.
 */
open class DudanVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        current = this
        if (pendingListen) {
            pendingListen = false
            if (!show(Opener.ListenEntry)) openApp(this)
        }
        appContainer.ensureWakeWord()
    }

    override fun onShutdown() {
        if (current === this) current = null
        super.onShutdown()
    }

    companion object {
        /** In the session's show arguments: the name of the [Opener] that opened it. */
        const val EXTRA_OPENER = "nl.bartvandermeeren.dudan.OPENER"

        /** The bound service while dudan is the default assistant. */
        @Volatile private var current: DudanVoiceInteractionService? = null

        /** Clark was opened before Android connected the service; [onReady] opens the overlay. */
        @Volatile private var pendingListen = false

        private const val LISTEN_WAIT_MS = 3_000L

        /**
         * The wake word was heard: open the overlay, listening. Without the assistant role there's no
         * overlay to show, so the app opens to listen instead, where Android allows that.
         */
        fun wake(context: Context) {
            if (!show(Opener.WakeWord)) openApp(context)
        }

        /**
         * Clark in the app list was opened, by a tap or by "Hi Bixby, open Clark": open the overlay,
         * listening. [activity] is in the foreground, so when the overlay can't open the app can.
         */
        fun listen(activity: Activity) {
            val connected = current != null
            if (show(Opener.ListenEntry)) return
            val isAssistant = activity.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            if (!waitsForService(connected, isAssistant)) {
                activity.startActivity(voiceCommand(activity))
                return
            }
            // dudan is the assistant, but its process just started and Android hasn't connected the
            // service yet. onReady opens the overlay; if it doesn't come, the app opens instead.
            pendingListen = true
            val context = activity.applicationContext
            Handler(Looper.getMainLooper()).postDelayed({
                if (!pendingListen) return@postDelayed
                pendingListen = false
                Log.w("DudanWake", "The assistant service didn't connect in time")
                openApp(context)
            }, LISTEN_WAIT_MS)
        }

        /** Opens the overlay for [opener], if dudan is the default assistant. */
        private fun show(opener: Opener): Boolean {
            val service = current ?: return false
            return try {
                service.showSession(
                    Bundle().apply { putString(EXTRA_OPENER, opener.name) },
                    VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
                )
                true
            } catch (e: Exception) {
                Log.w("DudanWake", "Could not show the assistant", e)
                false
            }
        }

        /** Opens the app to listen, from the background where Android allows that. */
        private fun openApp(context: Context) {
            if (!context.appContainer.phoneControl.canStartActivities()) return
            runCatching { context.startActivity(voiceCommand(context)) }
        }

        private fun voiceCommand(context: Context) = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VOICE_COMMAND)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/**
 * Whether Clark in the app list waits for Android to connect the assistant service, after the overlay
 * didn't open. Only when dudan [isAssistant] and the service isn't [connected] yet: then it comes
 * soon. A connected service that failed to show won't connect again, so the app opens instead.
 */
internal fun waitsForService(connected: Boolean, isAssistant: Boolean) = isAssistant && !connected

open class DudanSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = DudanSession(this)
}
