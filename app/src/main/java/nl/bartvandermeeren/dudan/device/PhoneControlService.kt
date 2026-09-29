package nl.bartvandermeeren.dudan.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.dudan.BuildConfig
import nl.bartvandermeeren.dudan.MainActivity
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.appContainer

/**
 * Keeps the MCP server for Hermes running while phone control is on. It's a foreground service so
 * Android neither freezes the process nor cuts its network while the phone idles.
 */
class PhoneControlService : Service() {
    private val scope = MainScope()
    private var server: McpHttpServer? = null
    private var starting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Android 14 introduced the specialUse type; before that a foreground service needs none.
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: IllegalStateException) {
            // Android refuses when dudan is in the background; the next time it's on screen retries.
            Log.w(TAG, "Could not start in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null && !starting) {
            starting = true
            scope.launch {
                try {
                    startServer()
                } finally {
                    starting = false
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startServer() {
        val container = appContainer
        val control = container.phoneControl
        val settings = container.settings.current()
        if (!settings.phoneControl) {
            stopSelf()
            return
        }
        // The token reads as blank when its Keystore key is gone; a new one beats "Starting…" forever.
        if (settings.phoneToken.isBlank()) container.settings.renewPhoneToken()
        val handler = McpHandler(control.tools, BuildConfig.VERSION_NAME)
        // No token while the setting is off, so a connection still open from before turns useless.
        val token = { container.settingsSnapshot.value.let { if (it.phoneControl) it.phoneToken else "" } }
        // Debug builds also answer on loopback, for `adb forward` during development.
        val candidate = McpHttpServer(PhoneControl.PORT, token, handler, acceptsLocal = { local ->
            Tailnet.isOwnAddress(local) || (BuildConfig.DEBUG && local.isLoopbackAddress)
        })
        try {
            withContext(Dispatchers.IO) { candidate.start() }
            server = candidate
            control.setState(PhoneControl.State.Running(candidate.localPort))
        } catch (e: IOException) {
            Log.w(TAG, "Could not listen on port ${PhoneControl.PORT}", e)
            control.setState(PhoneControl.State.Failed(e.message ?: e.javaClass.simpleName))
            stopSelf()
        } catch (e: CancellationException) {
            // Turned off while the socket was opening: onDestroy has run and won't see this server.
            candidate.stop()
            throw e
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        appContainer.phoneControl.let { if (it.state.value is PhoneControl.State.Running) it.setState(PhoneControl.State.Off) }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel_phone_control), NotificationManager.IMPORTANCE_MIN),
        )
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.phone_control_notification_title))
            .setContentText(getString(R.string.phone_control_notification_text))
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "PhoneControl"
        private const val CHANNEL = "phone_control"
        private const val NOTIFICATION_ID = 0x4d43

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PhoneControlService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Not allowed to start from the background", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhoneControlService::class.java))
        }
    }
}

/** Brings phone control back after a reboot or an app update, when it was on. */
class PhoneControlStarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (context.appContainer.settings.current().phoneControl) PhoneControlService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
