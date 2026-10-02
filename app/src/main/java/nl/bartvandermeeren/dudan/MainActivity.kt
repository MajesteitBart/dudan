package nl.bartvandermeeren.dudan

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.bartvandermeeren.dudan.data.AppVisibility
import nl.bartvandermeeren.dudan.data.ModelProfile
import nl.bartvandermeeren.dudan.ui.DudanRoot
import nl.bartvandermeeren.dudan.ui.MainViewModel
import nl.bartvandermeeren.dudan.ui.theme.Accent
import nl.bartvandermeeren.dudan.ui.theme.DudanTheme
import nl.bartvandermeeren.dudan.ui.theme.Sky

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Reopening from Recents after Back replays the intent that started the task. For an assistant
        // hand-off that would bring back its chat, or start Live again; open like the launcher does.
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromRecents) handleIntent(intent)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            DudanTheme(
                reduceTransparency = settings?.reduceTransparency == true,
                accent = Accent.from(settings?.accent),
                sky = Sky.from(settings?.sky),
            ) {
                DudanRoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.activityResumed = true
        appContainer.phoneControl.ensureRunning()
        vm.onAppResumed()
    }

    override fun onPause() {
        AppVisibility.activityResumed = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        sessionId?.let { vm.openSession(it) }
        val startVoice = intent.getBooleanExtra(EXTRA_START_VOICE, false)
        val startLive = intent.getBooleanExtra(EXTRA_START_LIVE, false)
        // The overlay hands over before its first message too; what follows is still an assistant chat.
        if (sessionId == null && intent.getBooleanExtra(EXTRA_FROM_ASSISTANT, false)) vm.newChat(ModelProfile.Assistant)
        when (intent.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> {
                vm.newChat(ModelProfile.Assistant)
                vm.requestVoice()
            }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (stream != null) vm.acceptShared(listOf(stream), text, intent.type) else text?.let(vm::acceptSharedText)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                if (streams.isNotEmpty()) vm.acceptShared(streams, intent.getStringExtra(Intent.EXTRA_TEXT), intent.type)
            }
        }
        if (startVoice) vm.requestVoice()
        if (startLive) vm.requestVoice(live = true)
    }

    companion object {
        // Stable intent keys for callers created before the rename.
        const val EXTRA_SESSION_ID = "nl.bartvandermeeren.aight.SESSION_ID"
        const val EXTRA_START_VOICE = "nl.bartvandermeeren.aight.START_VOICE"
        const val EXTRA_START_LIVE = "nl.bartvandermeeren.aight.START_LIVE"
        const val EXTRA_FROM_ASSISTANT = "nl.bartvandermeeren.aight.FROM_ASSISTANT"
    }
}
