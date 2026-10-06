package nl.bartvandermeeren.dudan.assist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.chat.ChatEngine
import nl.bartvandermeeren.dudan.chat.TurnOrigin
import nl.bartvandermeeren.dudan.data.AppContainer
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.data.AppVisibility
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.ui.components.ImageCodec
import nl.bartvandermeeren.dudan.voice.SpeechInput

enum class OpenMode { Chat, Voice, Live }

/** State and behavior of one assistant overlay, shared by every show/hide cycle of the session. */
class AssistState(
    private val context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val openInApp: (sessionId: String?, mode: OpenMode) -> Unit,
    val dismiss: () -> Unit,
    /** Blurs (true) or stops blurring the app behind the overlay window. */
    val setBackdropBlur: (Boolean) -> Unit,
) {
    val engine: ChatEngine = container.engine
    val speaker = container.speaker
    val speech = SpeechInput(context)

    var settings by mutableStateOf<AppSettings?>(null)
        private set
    var sessionId by mutableStateOf<String?>(null)
        private set
    var text by mutableStateOf("")
    var screenshot by mutableStateOf<Bitmap?>(null)
    var attachScreenshot by mutableStateOf(false)
    var shown by mutableStateOf(false)
        private set

    private var speakNextReply = false
    private var seenTurnSeq = 0L

    init {
        scope.launch {
            engine.completedTurns.collect { turn ->
                if (turn == null || turn.seq <= seenTurnSeq) return@collect
                seenTurnSeq = turn.seq
                if (shown && speakNextReply && turn.sessionId == sessionId && settings?.speakReplies == true) {
                    speakNextReply = false
                    speaker.speak(turn.messageId, turn.text, settings?.speechLanguage)
                }
            }
        }
        // The agent opened an app or link on the phone: get out of the way so the user sees it.
        scope.launch {
            container.phoneControl.launches.collect { if (shown) dismiss() }
        }
    }

    fun onShown() {
        shown = true
        AppVisibility.overlayShown = true
        container.phoneControl.ensureRunning()
        sessionId = null
        text = ""
        attachScreenshot = false
        speakNextReply = false
        seenTurnSeq = engine.completedTurns.value?.seq ?: 0L
        scope.launch {
            val current = container.settings.current()
            settings = current
            // Load the voice model now, so a spoken reply doesn't wait for it.
            if (current.speakReplies) speaker.warmUp()
            if (current.isConfigured && current.listenOnInvoke && hasMic()) startListening()
        }
    }

    fun onHidden() {
        shown = false
        AppVisibility.overlayShown = false
        speech.cancel()
        speaker.stop()
        screenshot = null
    }

    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startListening() {
        if (!hasMic()) {
            // The overlay can't show a permission prompt; the app can.
            openInApp(null, OpenMode.Voice)
            return
        }
        speaker.stop()
        speech.start(
            settings?.speechLanguage?.ifBlank { null },
            onResult = { heard ->
                if (heard.isNotBlank()) {
                    text = listOf(text.trim(), heard.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                    speakNextReply = true
                    send()
                }
            },
        )
    }

    fun stopListening() = speech.stop()

    fun send() {
        val message = text.trim()
        val shot = screenshot.takeIf { attachScreenshot }
        if (message.isEmpty() && shot == null) return
        val id = sessionId ?: engine.startNew(ModelProfile.Assistant).also { sessionId = it }
        if (engine.conversation(id).value.isBusy) return
        text = ""
        attachScreenshot = false
        speech.cancel()
        if (shot == null) {
            engine.send(id, message, origin = origin())
        } else {
            scope.launch {
                val image = withContext(Dispatchers.Default) { ImageCodec.prepare(shot) }
                engine.send(id, message, listOf(image), origin = origin(screenshot = true))
            }
        }
    }

    /** Sends what an OpenUI button or follow-up asks for; false while a reply is still running. */
    fun sendFromReply(message: String): Boolean {
        val id = sessionId ?: return false
        speech.cancel()
        return engine.send(id, message, origin = origin())
    }

    /** Tells Hermes the question came from the overlay, and whether its answer will be read aloud. */
    private fun origin(screenshot: Boolean = false) =
        TurnOrigin(TurnOrigin.Surface.Assistant, spoken = speakNextReply && settings?.speakReplies == true, screenshot = screenshot)

    fun stop() {
        sessionId?.let(engine::stop)
    }

    fun reviewBackground() {
        sessionId?.let { engine.reviewBackground(it, context.getString(R.string.background_review_prompt)) }
    }

    fun openFullChat() = openInApp(sessionId, OpenMode.Chat)

    fun openLive() = openInApp(sessionId, OpenMode.Live)
}
