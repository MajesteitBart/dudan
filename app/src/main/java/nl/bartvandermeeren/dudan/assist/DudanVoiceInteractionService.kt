package nl.bartvandermeeren.dudan.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Entry point Android binds to once dudan is the default digital assistant. It doesn't advertise
 * lock-screen launch: that would show chat history over the keyguard, so Android asks to unlock first.
 */
open class DudanVoiceInteractionService : VoiceInteractionService()

open class DudanSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = DudanSession(this)
}
