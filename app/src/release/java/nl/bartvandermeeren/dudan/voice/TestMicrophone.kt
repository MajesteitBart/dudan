package nl.bartvandermeeren.dudan.voice

import android.content.Context

/** Release builds always use the real microphone; the debug build can play a test recording instead. */
object TestMicrophone {
    const val VOICE_INPUT = "debug-mic.wav"
    const val WAKE_WORD = "debug-wake.wav"

    @Suppress("UNUSED_PARAMETER")
    fun open(context: Context, name: String = VOICE_INPUT): LocalSpeechSession.AudioSource? = null
}
