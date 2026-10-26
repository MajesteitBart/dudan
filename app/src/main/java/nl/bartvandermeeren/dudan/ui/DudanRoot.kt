package nl.bartvandermeeren.dudan.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.ui.chat.ChatHostActions
import nl.bartvandermeeren.dudan.ui.chat.ChatPane
import nl.bartvandermeeren.dudan.ui.chat.Sidebar
import nl.bartvandermeeren.dudan.ui.chat.SidebarMode
import nl.bartvandermeeren.dudan.ui.components.GlassBackdrop
import nl.bartvandermeeren.dudan.ui.components.LocalHazeState
import nl.bartvandermeeren.dudan.ui.extras.JobsScreen
import nl.bartvandermeeren.dudan.ui.extras.SearchScreen
import nl.bartvandermeeren.dudan.ui.extras.SkillsScreen
import nl.bartvandermeeren.dudan.ui.live.LiveScreen
import nl.bartvandermeeren.dudan.ui.settings.SettingsScreen
import nl.bartvandermeeren.dudan.voice.SpeechInput

/** One dusk sky behind every screen; the glass cards on each of them frost it. */
@Composable
fun DudanRoot(vm: MainViewModel) {
    val settingsState by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val haze = rememberHazeState()
    val emptyChat = vm.screen == Screen.Chat && conversation.showGreeting
    Box(Modifier.fillMaxSize()) {
        GlassBackdrop(
            Modifier.hazeSource(haze),
            glow = emptyChat || settingsState?.isConfigured == false,
            // Replies sit on the sky itself, so it deepens while a conversation is open.
            dim = vm.screen == Screen.Chat && !emptyChat,
        )
        CompositionLocalProvider(LocalHazeState provides haze) {
            DudanScreens(vm)
        }
    }
}

@Composable
private fun DudanScreens(vm: MainViewModel) {
    val settingsState by vm.settings.collectAsStateWithLifecycle()
    val settings = settingsState ?: return
    if (!settings.isConfigured) {
        SettingsScreen(vm, settings, setupMode = true, onBack = null)
        return
    }

    val context = LocalContext.current
    val resources = LocalResources.current
    val speech = remember { SpeechInput(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { speech.cancel() } }
    val phase by speech.phase.collectAsStateWithLifecycle()
    val level by speech.level.collectAsStateWithLifecycle()
    val heard by speech.partial.collectAsStateWithLifecycle()
    val listening = phase != SpeechInput.Phase.Idle
    var sendAfterVoice by remember { mutableStateOf(false) }
    var pendingAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    val speechUnavailable = stringResource(R.string.speech_unavailable)
    val micDenied = stringResource(R.string.mic_permission_needed)

    fun startDictation() {
        vm.speaker.stop()
        sendAfterVoice = false
        speech.start(
            settings.speechLanguage.ifBlank { null },
            onResult = { text ->
                if (text.isNotBlank()) {
                    vm.composerText = listOf(vm.composerText.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                }
                if (sendAfterVoice) vm.send()
                sendAfterVoice = false
            },
            onError = { code ->
                if (sendAfterVoice && vm.composerText.isNotBlank()) vm.send()
                sendAfterVoice = false
                if (code == SpeechInput.ERROR_UNAVAILABLE) Toast.makeText(context, speechUnavailable, Toast.LENGTH_LONG).show()
            },
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAfterPermission
        pendingAfterPermission = null
        if (granted) action?.invoke() else Toast.makeText(context, micDenied, Toast.LENGTH_LONG).show()
    }

    fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingAfterPermission = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Photos go to the model as pictures; videos picked here are uploaded like any other file.
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        vm.addPicked(uris)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addPicked(uris)
    }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) cameraUri?.let { vm.addImage(Uri.parse(it)) }
    }

    // An intent can ask for voice before the activity is in front, for example the wake word bringing the
    // app back from the background. Android may give an app that isn't in front a silent microphone, so the
    // request waits until the activity is resumed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.voiceRequests.collect { live ->
                if (live) {
                    withMic { vm.screen = Screen.Live }
                } else {
                    vm.screen = Screen.Chat
                    withMic { startDictation() }
                }
            }
        }
    }
    LaunchedEffect(Unit) { vm.loadModels() }
    LaunchedEffect(vm, context, resources) {
        vm.notices.collect { Toast.makeText(context, resources.getString(it.text, *it.args.toTypedArray()), Toast.LENGTH_LONG).show() }
    }

    // Replies to long agent runs arrive as notifications when dudan isn't on screen.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val actions = ChatHostActions(
        onMic = { withMic { startDictation() } },
        onStopListening = { speech.stop() },
        onSendWhileListening = {
            sendAfterVoice = true
            speech.stop()
        },
        onLive = {
            speech.cancel()
            withMic { vm.screen = Screen.Live }
        },
        onPickPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
        onTakePhoto = {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            cameraUri = uri.toString()
            takePhoto.launch(uri)
        },
    )

    when (vm.screen) {
        Screen.Settings -> SettingsScreen(vm, settings, setupMode = false, onBack = { vm.screen = Screen.Chat })
        Screen.Search -> SearchScreen(vm, onBack = { vm.screen = Screen.Chat })
        Screen.Skills -> SkillsScreen(vm, onBack = { vm.screen = Screen.Chat })
        Screen.Jobs -> JobsScreen(vm, onBack = { vm.screen = Screen.Chat })
        Screen.Live -> LiveScreen(
            engine = vm.engine,
            speech = speech,
            speaker = vm.speaker,
            sessionId = { vm.currentId.value },
            language = settings.speechLanguage.ifBlank { null },
            assistantName = settings.assistantName,
            onClose = { vm.screen = Screen.Chat },
        )
        Screen.Chat -> ChatLayout(vm, settings, listening, level, heard, actions)
    }
}

@Composable
private fun ChatLayout(
    vm: MainViewModel,
    settings: nl.bartvandermeeren.dudan.data.AppSettings,
    listening: Boolean,
    level: Float,
    heard: String,
    actions: ChatHostActions,
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    var dockedOpen by rememberSaveable { mutableStateOf(true) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp

        @Composable
        fun SidebarContent(mode: SidebarMode, modifier: Modifier, close: () -> Unit) {
            val afterPick: () -> Unit = { if (mode == SidebarMode.Overlay) close() }
            Sidebar(
                mode = mode,
                assistantName = settings.assistantName,
                userName = settings.userName,
                sessions = sessions,
                currentSessionId = currentId,
                onClose = close,
                onSearch = { vm.screen = Screen.Search; afterPick() },
                onSkills = { vm.screen = Screen.Skills; afterPick() },
                onJobs = { vm.screen = Screen.Jobs; afterPick() },
                onOpenSession = { vm.openSession(it); afterPick() },
                onRename = vm::renameSession,
                onDelete = vm::deleteSession,
                onTogglePin = { vm.setPinned(it.id, !it.pinned) },
                onSettings = { vm.screen = Screen.Settings; afterPick() },
                modifier = modifier,
            )
        }

        if (wide) {
            // A swipe to the right opens the docked chat list and one to the left closes it. Short, slow
            // drags do nothing, so a hesitant touch on the chat doesn't move the layout.
            var swipe by remember { mutableFloatStateOf(0f) }
            val swipeState = rememberDraggableState { swipe += it }
            val density = LocalDensity.current
            Row(
                Modifier
                    .fillMaxSize()
                    .draggable(
                        swipeState,
                        Orientation.Horizontal,
                        onDragStarted = { swipe = 0f },
                        onDragStopped = { velocity ->
                            val distance = with(density) { DOCK_SWIPE_DISTANCE.toPx() }
                            val speed = with(density) { DOCK_SWIPE_VELOCITY.toPx() }
                            when {
                                !dockedOpen && swipe > 0f && (swipe > distance || velocity > speed) -> {
                                    dockedOpen = true
                                    vm.refreshSessions()
                                }
                                dockedOpen && swipe < 0f && (swipe < -distance || velocity < -speed) -> dockedOpen = false
                            }
                        },
                    ),
            ) {
                AnimatedVisibility(dockedOpen, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                    SidebarContent(SidebarMode.Docked, Modifier.width(320.dp), close = { dockedOpen = false })
                }
                ChatPane(
                    vm = vm,
                    settings = settings,
                    showMenuButton = !dockedOpen,
                    onMenu = { dockedOpen = true; vm.refreshSessions() },
                    listening = listening,
                    voiceLevel = level,
                    voiceText = heard,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // The chat list is a drawer the width of the screen, as in Gemini: a swipe to the right anywhere on
            // the chat pulls it in and pushes the chat out, following the finger, and a swipe to the left
            // closes it again. Lists and charts that scroll sideways keep their own swipes.
            val width = constraints.maxWidth.toFloat()
            val anchors = remember(width) {
                DraggableAnchors {
                    DrawerValue.Closed at 0f
                    DrawerValue.Open at width
                }
            }
            val drawer = remember { AnchoredDraggableState(if (drawerOpen) DrawerValue.Open else DrawerValue.Closed, anchors) }
            LaunchedEffect(anchors) { drawer.updateAnchors(anchors, drawer.targetValue) }
            val focusManager = LocalFocusManager.current
            LaunchedEffect(drawer) {
                snapshotFlow { drawer.offsetPx() > 0f }.collect { moving ->
                    // The list starts to slide in: put the keyboard away and bring the list up to date.
                    if (moving) {
                        focusManager.clearFocus()
                        vm.refreshSessions()
                    }
                }
            }
            LaunchedEffect(drawer) {
                snapshotFlow { drawer.settledValue }.collect { drawerOpen = it == DrawerValue.Open }
            }
            val drawerShown by remember { derivedStateOf { drawer.offsetPx() > 0f } }
            val moveDrawer: (DrawerValue) -> Unit = { target -> scope.launch { drawer.animateTo(target) } }

            Box(
                Modifier
                    .fillMaxSize()
                    .anchoredDraggable(
                        state = drawer,
                        orientation = Orientation.Horizontal,
                        flingBehavior = AnchoredDraggableDefaults.flingBehavior(drawer),
                    ),
            ) {
                ChatPane(
                    vm = vm,
                    settings = settings,
                    showMenuButton = true,
                    onMenu = { moveDrawer(DrawerValue.Open) },
                    listening = listening,
                    voiceLevel = level,
                    voiceText = heard,
                    actions = actions,
                    modifier = Modifier.offset { IntOffset(drawer.offsetPx().roundToInt(), 0) },
                )
                if (drawerShown) {
                    SidebarContent(
                        SidebarMode.Overlay,
                        Modifier.fillMaxSize().offset { IntOffset((drawer.offsetPx() - width).roundToInt(), 0) },
                        close = { moveDrawer(DrawerValue.Closed) },
                    )
                }
            }
            // The back gesture draws the list back a little as it goes and closes it when it completes; let go
            // of it early and the list settles open again. The back button closes it at once. The swipe that
            // starts a back gesture also reaches the chat as a drag until the system takes it over, so the
            // gesture keeps the drawer to itself until it's done.
            PredictiveBackHandler(enabled = drawer.targetValue == DrawerValue.Open) { progress ->
                drawer.anchoredDrag(MutatePriority.PreventUserInput) { _ ->
                    try {
                        progress.collect { dragTo(width * (1f - BACK_PEEK * it.progress)) }
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { animate(drawer.offsetPx(), width) { value, _ -> dragTo(value) } }
                        throw e
                    }
                    animate(drawer.offsetPx(), 0f) { value, _ -> dragTo(value) }
                }
            }
        }
    }
}

/** Where the chat list stands on a narrow screen. */
private enum class DrawerValue { Closed, Open }

/** How much of the drawer's width a full back gesture draws it back before it closes. */
private const val BACK_PEEK = 0.12f

/** How far the drawer is open in pixels; 0 before its anchors are known. */
private fun AnchoredDraggableState<DrawerValue>.offsetPx(): Float = offset.takeUnless { it.isNaN() } ?: 0f

/** How far, or how fast, a swipe on the inner screen has to go to open or close the docked chat list. */
private val DOCK_SWIPE_DISTANCE = 64.dp
private val DOCK_SWIPE_VELOCITY = 400.dp
