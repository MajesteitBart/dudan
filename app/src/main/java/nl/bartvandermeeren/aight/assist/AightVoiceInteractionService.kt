package nl.bartvandermeeren.aight.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Entry point Android binds to once aight is the default digital assistant. It doesn't advertise
 * lock-screen launch: that would show chat history over the keyguard, so Android asks to unlock first.
 */
class AightVoiceInteractionService : VoiceInteractionService()

class AightSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AightSession(this)
}
