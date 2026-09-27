package nl.bartvandermeeren.aight.device

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.data.AppSettings
import nl.bartvandermeeren.aight.data.AppVisibility

/**
 * Lets Hermes act on this phone: [PhoneControlService] serves [tools] over MCP while the setting is on,
 * and this class starts the activities those tools ask for.
 */
class PhoneControl(private val context: Context, private val settings: StateFlow<AppSettings>) {
    sealed interface State {
        data object Off : State
        data class Running(val port: Int) : State
        data class Failed(val message: String) : State
    }

    /** How a tool's activity start went. */
    enum class Launch { Opened, OpenedWhileLocked, Notified }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _launches = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits after a tool brought an app to the front, so the assistant overlay can step aside. */
    val launches: SharedFlow<Unit> = _launches.asSharedFlow()

    @Volatile private var lastLaunchAt = 0L

    val tools: List<PhoneTool> by lazy { PhoneTools(context, this).all() }

    /** A reply that lands right after a tool opened an app needs no notification: the user is looking at that app. */
    val launchedRecently: Boolean
        get() = lastLaunchAt != 0L && SystemClock.elapsedRealtime() - lastLaunchAt < QUIET_AFTER_LAUNCH_MS

    internal fun setState(state: State) {
        _state.value = state
    }

    /** Starts or stops the service to match the setting. */
    fun sync(enabled: Boolean) {
        if (enabled) {
            PhoneControlService.start(context)
        } else {
            PhoneControlService.stop(context)
            _state.value = State.Off
        }
    }

    /** Called whenever aight comes on screen: Android only lets a service start from the foreground, so retry then. */
    fun ensureRunning() {
        if (settings.value.phoneControl && _state.value !is State.Running) PhoneControlService.start(context)
    }

    /**
     * Android blocks activity starts from apps in the background, without an error. The exemptions aight
     * can have: an aight window on screen, being the default assistant (the system keeps its service
     * bound with that right), or "Display over other apps".
     */
    fun canStartActivities(): Boolean = AppVisibility.anyVisible || canStartFromBackground()

    fun canStartFromBackground(): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true ||
            Settings.canDrawOverlays(context)

    /**
     * Starts [intent] for a tool. When Android wouldn't allow it, posts a notification that starts it on a
     * tap instead. [reveals] is false for intents that do their work without showing anything, like a
     * timer, so the overlay stays up.
     */
    fun launch(intent: Intent, what: String, reveals: Boolean = true): Launch {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!canStartActivities()) {
            notifyToOpen(intent, what)
            return Launch.Notified
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw ToolFailure("Nothing on the phone can open $what.")
        }
        if (reveals) {
            lastLaunchAt = SystemClock.elapsedRealtime()
            _launches.tryEmit(Unit)
        }
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        return if (locked) Launch.OpenedWhileLocked else Launch.Opened
    }

    private fun notifyToOpen(intent: Intent, what: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            throw ToolFailure(
                "The phone can't open $what while aight is in the background, and aight may not post notifications. " +
                    "Ask the user to make aight the default assistant or allow \"Display over other apps\" for it.",
            )
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ACTIONS_CHANNEL, context.getString(R.string.notification_channel_phone_actions), NotificationManager.IMPORTANCE_HIGH),
        )
        val id = NOTIFY_ID_BASE + (what.hashCode() and 0xffff)
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, ACTIONS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.phone_action_title, settings.value.assistantName))
            .setContentText(context.getString(R.string.phone_action_tap_to_open, what))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /**
     * What to set up on the Hermes host, with this phone's address and token filled in. Hermes reaches the
     * phone through tools/hermes-phone-bridge, which keeps the tools registered while the phone is away.
     */
    fun hermesSetup(address: String?, token: String): String = """
        |# aight phone control, on the Hermes host.
        |# 1. Copy tools/hermes-phone-bridge/aight_phone_bridge.py from the aight repo to ~/.hermes/
        |
        |# 2. Give Hermes its MCP client, if it doesn't have it yet:
        |hermes pm install --extra mcp
        |
        |# 3. Add to ~/.hermes/.env:
        |AIGHT_PHONE_TOKEN=$token
        |
        |# 4. Add to ~/.hermes/config.yaml (merge into an existing mcp_servers: block):
        |mcp_servers:
        |  phone:
        |    command: python3
        |    args: ["${'$'}{userHome}/.hermes/aight_phone_bridge.py"]
        |    env:
        |      AIGHT_PHONE_URL: http://${address ?: "<this phone's Tailscale IP>"}:$PORT/mcp
        |      AIGHT_PHONE_TOKEN: ${'$'}{AIGHT_PHONE_TOKEN}
        |
        |# 5. Restart the gateway and check: hermes gateway restart && hermes mcp test phone
    """.trimMargin()

    companion object {
        /** Next to Hermes' own 8642. */
        const val PORT = 8643
        private const val ACTIONS_CHANNEL = "phone_actions"
        private const val NOTIFY_ID_BASE = 0x5000_0000
        private const val QUIET_AFTER_LAUNCH_MS = 2 * 60_000L
    }
}
