package nl.bartvandermeeren.dudan.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * A short beep that says voice input listens now. The phone's recognizer starts a moment after the wake
 * word and doesn't get what was said before it, so this tells someone who isn't looking when to ask.
 * It plays at media volume, like replies read aloud.
 */
object ListeningTone {
    private const val VOLUME = 80
    private const val DURATION_MS = 150

    fun play() {
        val tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
        } catch (e: RuntimeException) {
            // Android can refuse a tone generator; the waveform still shows that it listens.
            Log.w("DudanSpeech", "no listening tone", e)
            return
        }
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, DURATION_MS)
        Handler(Looper.getMainLooper()).postDelayed(tone::release, DURATION_MS + 200L)
    }
}
