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
import kotlinx.coroutines.delay
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
import nl.bartvandermeeren.dudan.voice.ListeningTone
import nl.bartvandermeeren.dudan.voice.SpeechInput
import nl.bartvandermeeren.dudan.voice.WakePhrase

enum class OpenMode { Chat, Voice, Live }

/** What opened the overlay. */
enum class Opener {
    /** The side button, the assist gesture or another app asking for the assistant. */
    Assist,

    /** The wake word. The phrase may lead the transcript. */
    WakeWord,

    /** Clark in the app list, tapped or opened by Bixby. */
    ListenEntry;

    /** Whether the overlay listens as it opens: always for the wake word and Clark, else with Listen right away on. */
    fun listens(listenOnInvoke: Boolean) = this != Assist || listenOnInvoke

    /** Whether opening it again while the overlay is open listens again in the same chat. */
    val listensAgain get() = this != Assist

    companion object {
        /** The opener named in the session's show arguments; the side button names none. */
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: Assist
    }
}

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
    val skillCatalog = container.skillCatalog
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

    /**
     * The overlay opened. When the wake word or Clark in the app list opened it, it always listens,
     * also with Listen right away off, and either one while it's already open listens again in the same chat.
     */
    fun onShown(opener: Opener = Opener.Assist) {
        if (opener.listensAgain && shown) {
            startListening(wakeWord = opener == Opener.WakeWord)
            return
        }
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
            // Listening comes first: the user may already be talking.
            if (current.isConfigured && opener.listens(current.listenOnInvoke) && hasMic()) startListening(wakeWord = opener == Opener.WakeWord)
            // Load the voice model now, so a spoken reply doesn't wait for it.
            if (current.speakReplies) speaker.warmUp()
            // The overlay can open without the app ever running, so load the skills for the $ list here too.
            if (current.isConfigured) skillCatalog.ensureLoaded()
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

    /** Listens for a question. [wakeWord] says the wake word asked for it, so nobody may be looking. */
    fun startListening(wakeWord: Boolean = false) {
        heardWakeWord = wakeWord
        handsFree = wakeWord
        retries = 0
        listen()
    }

    /** The wake word opened this recording, so its last word may lead the transcript. */
    private var heardWakeWord = false

    /**
     * The wake word started this listening and nobody stopped it by hand. The phone's recognizer then
     * plays a tone once it listens, since it doesn't get what was said right after the phrase.
     */
    private var handsFree = false
    private var retries = 0

    private fun listen() {
        if (!hasMic()) {
            // The overlay can't show a permission prompt; the app can.
            openInApp(null, OpenMode.Voice)
            return
        }
        speaker.stop()
        speech.start(
            settings?.speechLanguage,
            onListening = {
                if (handsFree && speech.usesPhoneRecognizer) ListeningTone.play()
            },
            onResult = { result ->
                val wake = heardWakeWord
                val heard = if (wake) WakePhrase.stripLeading(result, settings?.wakePhrase.orEmpty()) else result
                heardWakeWord = false
                if (wake && heard.isBlank() && shown) {
                    // Only the wake phrase, then a pause long enough to end the recording: now the question.
                    listen()
                    return@start
                }
                if (heard.isNotBlank()) {
                    text = listOf(text.trim(), heard.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                    send(spoken = true)
                }
            },
            onError = { code ->
                val next = if (shown && text.isBlank()) {
                    afterError(code, retries, wakeWord = heardWakeWord && handsFree, phoneRecognizer = speech.usesPhoneRecognizer)
                } else {
                    AfterError.End
                }
                when (next) {
                    AfterError.End -> heardWakeWord = false
                    AfterError.ListenAgain -> {
                        // Only once: the next silence ends listening.
                        heardWakeWord = false
                        listen()
                    }
                    AfterError.Retry -> {
                        retries++
                        scope.launch {
                            delay(RETRY_DELAY_MS * retries)
                            if (shown && !speech.isActive && text.isBlank()) listen()
                        }
                    }
                }
            },
        )
    }

    internal enum class AfterError {
        /** Listening ends; the keyboard and the mic button are there. */
        End,

        /** Listens again after a short wait. */
        Retry,

        /** Listens again right away, for a question the phone's recognizer missed after the wake word. */
        ListenAgain,
    }

    internal companion object {
        const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 300L

        /**
         * What follows when voice input in the overlay ended with [error] and nothing was typed. Anything
         * but silence (microphone busy, recognizer not ready) gets [MAX_RETRIES] more tries, so the overlay
         * doesn't drop into typing on a hiccup. Silence ends listening, except right after the [wakeWord]
         * on the [phoneRecognizer]: that recognizer doesn't get the audio after the phrase and starts a
         * moment later, so a question asked straight away can be over before it listens. It listens once
         * more then, with its tone.
         */
        fun afterError(error: Int, retries: Int, wakeWord: Boolean, phoneRecognizer: Boolean): AfterError = when {
            SpeechInput.isSilence(error) -> if (wakeWord && phoneRecognizer) AfterError.ListenAgain else AfterError.End
            retries < MAX_RETRIES -> AfterError.Retry
            else -> AfterError.End
        }
    }

    fun stopListening() {
        // Someone touched the phone: silence after this means there's nothing to ask.
        handsFree = false
        speech.stop()
    }

    /**
     * Sends the question. [spoken] says it was asked out loud, so its reply is read aloud. Each send
     * sets that afresh: a spoken question that failed or was stopped doesn't make the next typed one spoken.
     */
    fun send(spoken: Boolean = false) {
        val message = text.trim()
        val shot = screenshot.takeIf { attachScreenshot }
        if (message.isEmpty() && shot == null) return
        val id = sessionId ?: engine.startNew(ModelProfile.Assistant).also { sessionId = it }
        if (engine.conversation(id).value.isBusy) return
        text = ""
        attachScreenshot = false
        speech.cancel()
        speakNextReply = spoken
        if (shot == null) {
            engine.send(id, message, origin = origin())
        } else {
            scope.launch {
                val image = withContext(Dispatchers.Default) { ImageCodec.prepare(shot) }
                engine.send(id, message, listOf(image), origin = origin(screenshot = true))
            }
        }
    }

    /** Sends what an OpenUI button or follow-up asks for; false while a reply is still running. Its reply is shown, not read aloud. */
    fun sendFromReply(message: String): Boolean {
        val id = sessionId ?: return false
        speech.cancel()
        if (engine.conversation(id).value.isBusy) return false
        speakNextReply = false
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
