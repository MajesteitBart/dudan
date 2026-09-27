package nl.bartvandermeeren.aight.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.data.HermesApi
import nl.bartvandermeeren.aight.ui.chat.ChatHostActions
import nl.bartvandermeeren.aight.ui.chat.ChatPane
import nl.bartvandermeeren.aight.ui.chat.Sidebar
import nl.bartvandermeeren.aight.ui.chat.SidebarMode
import nl.bartvandermeeren.aight.ui.components.GlassBackdrop
import nl.bartvandermeeren.aight.ui.components.LocalHazeState
import nl.bartvandermeeren.aight.ui.extras.JobsScreen
import nl.bartvandermeeren.aight.ui.extras.SearchScreen
import nl.bartvandermeeren.aight.ui.extras.SkillsScreen
import nl.bartvandermeeren.aight.ui.live.LiveScreen
import nl.bartvandermeeren.aight.ui.settings.SettingsScreen
import nl.bartvandermeeren.aight.voice.SpeechInput

/** One dusk sky behind every screen; the glass cards on each of them frost it. */
@Composable
fun AightRoot(vm: MainViewModel) {
    val settingsState by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val haze = rememberHazeState()
    val emptyChat = vm.screen == Screen.Chat && conversation.messages.isEmpty() && !conversation.loading
    Box(Modifier.fillMaxSize()) {
        GlassBackdrop(
            Modifier.hazeSource(haze),
            glow = emptyChat || settingsState?.isConfigured == false,
            // Replies sit on the sky itself, so it deepens while a conversation is open.
            dim = vm.screen == Screen.Chat && !emptyChat,
        )
        CompositionLocalProvider(LocalHazeState provides haze) {
            AightScreens(vm)
        }
    }
}

@Composable
private fun AightScreens(vm: MainViewModel) {
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

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        uris.forEach(vm::addImage)
    }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) cameraUri?.let { vm.addImage(Uri.parse(it)) }
    }

    LaunchedEffect(Unit) {
        vm.voiceRequests.collect { live ->
            if (live) {
                withMic { vm.screen = Screen.Live }
            } else {
                vm.screen = Screen.Chat
                withMic { startDictation() }
            }
        }
    }
    LaunchedEffect(Unit) { vm.loadModels() }
    LaunchedEffect(vm, context, resources) {
        vm.notices.collect { Toast.makeText(context, resources.getString(it), Toast.LENGTH_LONG).show() }
    }

    // Replies to long agent runs arrive as notifications when aight isn't on screen.
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
        onPickPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
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
    settings: nl.bartvandermeeren.aight.data.AppSettings,
    listening: Boolean,
    level: Float,
    heard: String,
    actions: ChatHostActions,
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    var dockedOpen by rememberSaveable { mutableStateOf(true) }
    var overlayOpen by rememberSaveable { mutableStateOf(false) }
    val serverLabel = remember(settings.serverUrl) {
        HermesApi.normalizeBaseUrl(settings.serverUrl).substringAfter("://").substringBefore('/').substringBefore(':')
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp

        @Composable
        fun SidebarContent(mode: SidebarMode, modifier: Modifier) {
            val afterPick: () -> Unit = { if (mode == SidebarMode.Overlay) overlayOpen = false }
            Sidebar(
                mode = mode,
                assistantName = settings.assistantName,
                userName = settings.userName,
                serverLabel = serverLabel,
                sessions = sessions,
                currentSessionId = currentId,
                onNewChatSelected = conversation.isNew && conversation.messages.isEmpty(),
                onClose = { if (mode == SidebarMode.Docked) dockedOpen = false else overlayOpen = false },
                onNewChat = { vm.newChat(); afterPick() },
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
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(dockedOpen, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                    SidebarContent(SidebarMode.Docked, Modifier.width(320.dp))
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
            ChatPane(
                vm = vm,
                settings = settings,
                showMenuButton = true,
                onMenu = { overlayOpen = true; vm.refreshSessions() },
                listening = listening,
                voiceLevel = level,
                voiceText = heard,
                actions = actions,
            )
            AnimatedVisibility(
                overlayOpen,
                enter = slideInHorizontally { -it / 3 } + fadeIn(),
                exit = slideOutHorizontally { -it / 3 } + fadeOut(),
            ) {
                SidebarContent(SidebarMode.Overlay, Modifier.fillMaxSize())
            }
            BackHandler(enabled = overlayOpen) { overlayOpen = false }
        }
    }
}
