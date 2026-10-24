package nl.bartvandermeeren.dudan.assist

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.bartvandermeeren.dudan.MainActivity
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.appContainer
import nl.bartvandermeeren.dudan.data.AppSettings
import nl.bartvandermeeren.dudan.voice.WakeWordListener

/**
 * Runs the wake word listener while the wake word is on. Android only lets an app use the microphone
 * from the background inside a foreground service of the microphone type; being the default assistant
 * lets dudan start one from the background too, so it comes back after a reboot.
 */
class WakeWordService : Service() {
    private val scope = MainScope()
    private var listener: WakeWordListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        val container = appContainer
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(container.settingsSnapshot.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Refused from the background when dudan isn't the default assistant; the app retries on screen.
            Log.w(TAG, "Could not start in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (listener == null) {
            val created = WakeWordListener(
                context = applicationContext,
                model = container.wakeWordModel,
                phrase = { container.settingsSnapshot.value.let { if (listens(it)) it.wakePhrase else null } },
                onWake = { DudanVoiceInteractionService.wake(applicationContext) },
            )
            listener = created
            created.start()
            scope.launch {
                container.settings.flow.collect { settings ->
                    if (!listens(settings)) stopSelf()
                }
            }
            scope.launch {
                container.settings.flow.map { it.wakePhrase }.distinctUntilChanged().collect { _ ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(container.settings.current()))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        listener?.stop()
        listener = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(settings: AppSettings): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel_wake_word), NotificationManager.IMPORTANCE_MIN),
        )
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.wake_word_notification_title, settings.wakePhrase))
            .setContentText(getString(R.string.wake_word_notification_text))
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "DudanWake"
        private const val CHANNEL = "wake_word"
        private const val NOTIFICATION_ID = 0x5757

        fun listens(settings: AppSettings) = settings.wakeWord && settings.isConfigured

        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Not allowed to start from the background", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
