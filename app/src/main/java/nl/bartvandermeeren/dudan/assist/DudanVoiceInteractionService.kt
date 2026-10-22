package nl.bartvandermeeren.dudan.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
        appContainer.ensureWakeWord()
    }

    override fun onShutdown() {
        if (current === this) current = null
        super.onShutdown()
    }

    companion object {
        /** In the session's show arguments when the wake word opened it. */
        const val EXTRA_WAKE_WORD = "nl.bartvandermeeren.dudan.WAKE_WORD"

        /** The bound service while dudan is the default assistant. */
        @Volatile private var current: DudanVoiceInteractionService? = null

        /**
         * The wake word was heard: open the overlay, listening. Without the assistant role there's no
         * overlay to show, so the app opens to listen instead, where Android allows that.
         */
        fun wake(context: Context) {
            val service = current
            if (service != null) {
                try {
                    service.showSession(
                        Bundle().apply { putBoolean(EXTRA_WAKE_WORD, true) },
                        VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
                    )
                    return
                } catch (e: Exception) {
                    Log.w("DudanWake", "Could not show the assistant", e)
                }
            }
            if (!context.appContainer.phoneControl.canStartActivities()) return
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VOICE_COMMAND)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { context.startActivity(intent) }
        }
    }
}

open class DudanSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = DudanSession(this)
}
