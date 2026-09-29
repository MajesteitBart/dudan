package nl.bartvandermeeren.dudan.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import nl.bartvandermeeren.dudan.voice.SpeechRecognizers

/**
 * Android requires a digital assistant to ship a recognition service, and selecting the assistant
 * makes that service the system default. This one forwards to the phone's real recognizer, so
 * voice input in other apps keeps working after dudan becomes the assistant.
 */
open class DudanRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        release()
        val recognizer = SpeechRecognizers.create(this)
        if (recognizer == null) {
            safe { callback.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        delegate = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = safe { callback.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { callback.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = safe { callback.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = safe { if (buffer != null) callback.bufferReceived(buffer) }
            override fun onEndOfSpeech() = safe { callback.endOfSpeech() }
            override fun onError(error: Int) = safe { callback.error(error) }
            override fun onResults(results: Bundle?) = safe { callback.results(results ?: Bundle()) }
            override fun onPartialResults(partialResults: Bundle?) = safe { callback.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(intent)
    }

    override fun onStopListening(callback: Callback) {
        delegate?.stopListening()
    }

    override fun onCancel(callback: Callback) {
        release()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        delegate?.runCatching { cancel(); destroy() }
        delegate = null
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // The caller went away; nothing to report to.
        }
    }
}
