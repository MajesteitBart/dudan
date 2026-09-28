package nl.bartvandermeeren.aight

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
import nl.bartvandermeeren.aight.data.AppVisibility
import nl.bartvandermeeren.aight.data.ModelProfile
import nl.bartvandermeeren.aight.ui.AightRoot
import nl.bartvandermeeren.aight.ui.MainViewModel
import nl.bartvandermeeren.aight.ui.theme.Accent
import nl.bartvandermeeren.aight.ui.theme.AightTheme
import nl.bartvandermeeren.aight.ui.theme.Sky

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            AightTheme(
                reduceTransparency = settings?.reduceTransparency == true,
                accent = Accent.from(settings?.accent),
                sky = Sky.from(settings?.sky),
            ) {
                AightRoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.activityResumed = true
        appContainer.phoneControl.ensureRunning()
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
        const val EXTRA_SESSION_ID = "nl.bartvandermeeren.aight.SESSION_ID"
        const val EXTRA_START_VOICE = "nl.bartvandermeeren.aight.START_VOICE"
        const val EXTRA_START_LIVE = "nl.bartvandermeeren.aight.START_LIVE"
        const val EXTRA_FROM_ASSISTANT = "nl.bartvandermeeren.aight.FROM_ASSISTANT"
    }
}
