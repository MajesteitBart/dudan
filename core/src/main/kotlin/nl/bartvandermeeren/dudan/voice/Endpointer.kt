package nl.bartvandermeeren.dudan.voice

/**
 * Decides when a spoken utterance starts and ends, from one speech probability per audio window
 * (Silero VAD). Plain logic without audio, so the timing rules are testable.
 */
class Endpointer(
    private val windowMs: Int = 32,
    /** Probability that counts as speech before the utterance starts. */
    private val threshold: Float = 0.5f,
    /** Once talking, a lower bar keeps soft syllables and breaths from counting as silence. */
    private val keepThreshold: Float = 0.35f,
    /** Continuous speech needed to start, so a cough or click doesn't. */
    private val startSpeechMs: Int = 160,
    /**
     * Silence after speech that ends the utterance. Long enough by default that a pause at a comma or
     * to find a word doesn't cut the sentence off; Settings > Speech input changes it.
     */
    private val endSilenceMs: Int = DEFAULT_END_SILENCE_MS,
    /** Nothing said this long after the microphone opened: give up, like Android's recognizer. */
    private val noSpeechTimeoutMs: Int = 8_000,
    private val maxUtteranceMs: Int = 60_000,
) {
    enum class Event { None, Started, Ended, NoSpeech, TooLong }

    var started = false
        private set

    /** Windows of audio that belong to the speech that triggered the start. */
    val startWindows: Int get() = startSpeechMs / windowMs

    private var elapsedMs = 0
    private var speechRunMs = 0
    private var silenceRunMs = 0
    private var utteranceMs = 0

    fun accept(probability: Float): Event {
        elapsedMs += windowMs
        if (!started) {
            speechRunMs = if (probability >= threshold) speechRunMs + windowMs else 0
            if (speechRunMs >= startSpeechMs) {
                started = true
                utteranceMs = speechRunMs
                silenceRunMs = 0
                return Event.Started
            }
            return if (elapsedMs >= noSpeechTimeoutMs) Event.NoSpeech else Event.None
        }
        utteranceMs += windowMs
        silenceRunMs = if (probability >= keepThreshold) 0 else silenceRunMs + windowMs
        return when {
            silenceRunMs >= endSilenceMs -> Event.Ended
            utteranceMs >= maxUtteranceMs -> Event.TooLong
            else -> Event.None
        }
    }

    /** Trailing silence at the moment the utterance ended, so it can be trimmed before decoding. */
    val trailingSilenceMs: Int get() = silenceRunMs

    companion object {
        const val DEFAULT_END_SILENCE_MS = 2_000

        /** What the setting offers: shorter cuts sentences off, longer makes every question wait. */
        val END_SILENCE_RANGE_MS = 1_000..5_000
    }
}
