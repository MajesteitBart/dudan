package nl.bartvandermeeren.aight.assist

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import nl.bartvandermeeren.aight.MainActivity
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.appContainer
import nl.bartvandermeeren.aight.ui.theme.AightTheme

/**
 * The overlay Android shows when the user invokes the assistant (long-press power or home, corner
 * swipe). Hosts a Compose UI, so it provides the lifecycle owners Compose normally gets from an activity.
 */
class AightSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val scope = MainScope()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private val state = AssistState(
        context = context,
        container = context.appContainer,
        scope = scope,
        openInApp = ::openInApp,
        dismiss = { hide() },
        setBackdropBlur = ::setBackdropBlur,
    )

    init {
        setTheme(R.style.Theme_Aight_Assist)
        savedStateController.performAttach()
        savedStateController.performRestore(null)
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        window.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.isNavigationBarContrastEnforced = false
            w.decorView.let { decor ->
                decor.setViewTreeLifecycleOwner(this)
                decor.setViewTreeSavedStateRegistryOwner(this)
                decor.setViewTreeViewModelStoreOwner(this)
            }
        }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@AightSession)
        setViewTreeSavedStateRegistryOwner(this@AightSession)
        setViewTreeViewModelStoreOwner(this@AightSession)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@AightSession))
        setContent {
            AightTheme(reduceTransparency = state.settings?.reduceTransparency == true) {
                AssistOverlay(state)
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        state.onShown()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        state.screenshot = screenshot
    }

    override fun onHide() {
        state.onHidden()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        super.onHide()
    }

    override fun onDestroy() {
        state.speech.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Frosts the app underneath so the overlay's panels float over it, or turns that off for Reduce
     * transparency. Phones without cross-window blur ignore the flag; the overlay then draws its
     * panels solid (see windowGlass).
     */
    private fun setBackdropBlur(on: Boolean) {
        val w = window.window ?: return
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND) else w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        w.attributes = w.attributes.apply {
            blurBehindRadius = if (on) (OVERLAY_BLUR_DP * context.resources.displayMetrics.density).toInt() else 0
        }
    }

    private companion object {
        /**
         * Any app can sit behind the overlay: photos, video, dense text. NN/g's glassmorphism guidance
         * asks for enough blur that the background stays visible but not identifiable.
         */
        const val OVERLAY_BLUR_DP = 28
    }

    private fun openInApp(sessionId: String?, mode: OpenMode) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        sessionId?.let { intent.putExtra(MainActivity.EXTRA_SESSION_ID, it) }
        intent.putExtra(MainActivity.EXTRA_FROM_ASSISTANT, true)
        when (mode) {
            OpenMode.Voice -> intent.putExtra(MainActivity.EXTRA_START_VOICE, true)
            OpenMode.Live -> intent.putExtra(MainActivity.EXTRA_START_LIVE, true)
            OpenMode.Chat -> Unit
        }
        try {
            // The session window is visible, so this launch is allowed from here.
            context.startActivity(intent)
        } catch (_: Exception) {
            startAssistantActivity(intent)
        }
        hide()
    }
}
