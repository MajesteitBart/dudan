package nl.bartvandermeeren.dudan.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.chat.ChatEngine
import nl.bartvandermeeren.dudan.chat.MessageState
import nl.bartvandermeeren.dudan.chat.Role
import nl.bartvandermeeren.dudan.chat.TurnOrigin
import nl.bartvandermeeren.dudan.ui.components.DudanMark
import nl.bartvandermeeren.dudan.ui.components.glass
import nl.bartvandermeeren.dudan.ui.theme.Palette
import nl.bartvandermeeren.dudan.voice.Speaker
import nl.bartvandermeeren.dudan.voice.SpeechInput

enum class LivePhase { Listening, Thinking, Speaking, Paused, Error }

/** Hands-free loop: listen, send to Hermes, read the reply, listen again. */
class LiveController(
    private val scope: CoroutineScope,
    private val engine: ChatEngine,
    private val speech: SpeechInput,
    private val speaker: Speaker,
    private val sessionId: () -> String,
    private val language: String?,
) {
    var phase by mutableStateOf(LivePhase.Listening)
        private set
    var heard by mutableStateOf("")
        private set
    var reply by mutableStateOf("")
        private set
    var errorText by mutableStateOf<String?>(null)
        private set

    private var active = true
    private var silentRounds = 0
    private var turn: Job? = null

    fun start() = listen()

    fun listen() {
        if (!active || phase == LivePhase.Paused) return
        speaker.stop()
        phase = LivePhase.Listening
        speech.start(
            language,
            onResult = { text ->
                if (text.isBlank()) onSilence() else {
                    silentRounds = 0
                    heard = text
                    send(text)
                }
            },
            onError = { code ->
                if (SpeechInput.isSilence(code)) onSilence()
                else {
                    errorText = if (code == SpeechInput.ERROR_UNAVAILABLE) "unavailable" else "error $code"
                    phase = LivePhase.Error
                }
            },
        )
    }

    private fun onSilence() {
        silentRounds++
        if (silentRounds >= 3) pause() else listen()
    }

    private fun send(text: String) {
        phase = LivePhase.Thinking
        reply = ""
        val id = sessionId()
        val before = engine.conversation(id).value.messages.size
        engine.send(id, text, origin = TurnOrigin(TurnOrigin.Surface.Live, spoken = true))
        turn = scope.launch {
            val done = engine.conversation(id).first { c ->
                val last = c.messages.lastOrNull()
                c.messages.size > before && last?.role == Role.Assistant && !last.isStreaming
            }
            val last = done.messages.last()
            if (!active) return@launch
            if (phase == LivePhase.Paused) {
                // Paused while Hermes was thinking: keep the reply on screen, don't talk or listen.
                reply = last.text
                return@launch
            }
            if (last.state == MessageState.Done && last.text.isNotBlank()) {
                reply = last.text
                phase = LivePhase.Speaking
                speaker.speak(last.id, last.text, language) { if (active && phase == LivePhase.Speaking) listen() }
            } else {
                errorText = last.error
                listen()
            }
        }
    }

    /** Tap while the agent talks: cut it off and listen. */
    fun interrupt() {
        if (phase == LivePhase.Speaking) {
            phase = LivePhase.Listening
            listen()
        }
    }

    fun pause() {
        speech.cancel()
        speaker.stop()
        phase = LivePhase.Paused
    }

    fun resume() {
        silentRounds = 0
        errorText = null
        phase = LivePhase.Listening
        listen()
    }

    fun close() {
        active = false
        turn?.cancel()
        speech.cancel()
        speaker.stop()
    }
}

@Composable
fun LiveScreen(
    engine: ChatEngine,
    speech: SpeechInput,
    speaker: Speaker,
    sessionId: () -> String,
    language: String?,
    assistantName: String,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember { LiveController(scope, engine, speech, speaker, sessionId, language) }
    val level by speech.level.collectAsStateWithLifecycle()
    val partial by speech.partial.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        speaker.warmUp()
        controller.start()
    }
    DisposableEffect(Unit) { onDispose { controller.close() } }
    BackHandler { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.SkyDeep.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controller.interrupt() },
    ) {
        LiveGlow(controller.phase, level)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                DudanMark(size = 22.dp, working = controller.phase == LivePhase.Thinking)
                Spacer(Modifier.size(10.dp))
                Text("Live", style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary)
            }
            Spacer(Modifier.weight(0.6f))
            val status = when (controller.phase) {
                LivePhase.Listening -> stringResource(R.string.live_listening)
                LivePhase.Thinking -> stringResource(R.string.live_thinking)
                LivePhase.Speaking -> stringResource(R.string.live_speaking, assistantName)
                LivePhase.Paused -> stringResource(R.string.live_paused)
                LivePhase.Error -> stringResource(R.string.live_error)
            }
            Text(status, style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary)
            Spacer(Modifier.height(16.dp))
            val shown = when (controller.phase) {
                LivePhase.Listening -> partial.ifBlank { controller.heard }
                LivePhase.Speaking -> controller.reply
                else -> controller.heard
            }
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .heightIn(max = 280.dp)
                    .padding(horizontal = 32.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    nl.bartvandermeeren.dudan.voice.SpeechText.fromMarkdown(shown),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Palette.TextPrimary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.padding(bottom = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val paused = controller.phase == LivePhase.Paused || controller.phase == LivePhase.Error
                RoundButton(
                    if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    stringResource(if (paused) R.string.live_resume else R.string.live_pause),
                    Palette.Card,
                ) { if (paused) controller.resume() else controller.pause() }
                RoundButton(Icons.Rounded.Close, stringResource(R.string.live_end), EndGlass) { onClose() }
            }
        }
    }
}

/** Red glass for ending Live, so it reads as the way out without shouting over the glow. */
private val EndGlass = Color(0x99E5484D)

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(68.dp).glass(CircleShape, color, blur = false, solid = color.compositeOver(Palette.Background)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
    }
}

@Composable
private fun LiveGlow(phase: LivePhase, level: Float) {
    val transition = rememberInfiniteTransition(label = "live")
    val breathe by transition.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val drift by transition.animateFloat(-0.08f, 0.08f, infiniteRepeatable(tween(4200), RepeatMode.Reverse), label = "drift")
    val energy by animateFloatAsState(
        when (phase) {
            LivePhase.Listening -> 0.7f + level * 0.3f
            LivePhase.Speaking -> 0.8f
            LivePhase.Thinking -> 0.6f
            else -> 0.35f
        },
        tween(220),
        label = "energy",
    )
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val radius = w * (0.75f + 0.35f * energy) * breathe
        drawRect(
            Brush.radialGradient(
                listOf(Palette.SparkBlue.copy(alpha = 0.55f * energy), Palette.GlowViolet.copy(alpha = 0.35f * energy), Color.Transparent),
                center = Offset(w * (0.5f + drift), h * 1.08f),
                radius = radius,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.SparkViolet.copy(alpha = 0.35f * energy), Color.Transparent),
                center = Offset(w * (0.15f - drift), h * 1.0f),
                radius = radius * 0.7f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.SparkAmber.copy(alpha = 0.22f * energy), Color.Transparent),
                center = Offset(w * (0.9f + drift), h * 0.98f),
                radius = radius * 0.55f,
            ),
        )
    }
}
