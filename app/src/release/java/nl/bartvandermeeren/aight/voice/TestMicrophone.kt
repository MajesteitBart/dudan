package nl.bartvandermeeren.aight.voice

import android.content.Context

/** Release builds always use the real microphone; the debug build can play a test recording instead. */
object TestMicrophone {
    @Suppress("UNUSED_PARAMETER")
    fun open(context: Context): LocalSpeechSession.AudioSource? = null
}
