package nl.bartvandermeeren.aight.voice

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only: when files/debug-mic.wav exists, voice input plays that recording in real time
 * instead of opening the microphone, so the emulator can test recognition end to end. Silence
 * follows the recording, like a person who stopped talking.
 */
object TestMicrophone {
    fun open(context: Context): LocalSpeechSession.AudioSource? {
        val file = File(context.filesDir, "debug-mic.wav").takeIf { it.exists() } ?: return null
        val samples = runCatching { readWav(file.readBytes()) }.getOrNull() ?: return null
        var position = 0
        return object : LocalSpeechSession.AudioSource {
            override fun read(window: ShortArray): Boolean {
                Thread.sleep(window.size * 1000L / OrukeetEngine.SAMPLE_RATE)
                for (i in window.indices) window[i] = if (position < samples.size) samples[position++] else 0
                return true
            }

            override fun close() = Unit
        }
    }

    /** 16-bit PCM WAV, any rate and channel count, as 16 kHz mono. */
    private fun readWav(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(12)
        var channels = 1
        var rate = OrukeetEngine.SAMPLE_RATE
        while (buffer.remaining() >= 8) {
            val id = String(ByteArray(4).also { buffer.get(it) }, Charsets.US_ASCII)
            val size = buffer.int
            if (id == "fmt ") {
                val start = buffer.position()
                buffer.short // format
                channels = buffer.short.toInt()
                rate = buffer.int
                buffer.position(start + size)
            } else if (id == "data") {
                val frames = minOf(size, buffer.remaining()) / 2 / channels
                val mono = FloatArray(frames) { (0 until channels).sumOf { buffer.short.toInt() }.toFloat() / channels }
                val out = ShortArray((frames.toLong() * OrukeetEngine.SAMPLE_RATE / rate).toInt())
                for (i in out.indices) {
                    val at = i.toDouble() * rate / OrukeetEngine.SAMPLE_RATE
                    val low = at.toInt().coerceAtMost(frames - 1)
                    val high = (low + 1).coerceAtMost(frames - 1)
                    val fraction = (at - low).toFloat()
                    out[i] = (mono[low] * (1 - fraction) + mono[high] * fraction).toInt().toShort()
                }
                return out
            } else {
                buffer.position(buffer.position() + size + (size and 1))
            }
        }
        error("no audio data")
    }
}
