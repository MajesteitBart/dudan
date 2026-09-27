package nl.bartvandermeeren.aight.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.bartvandermeeren.aight.MainActivity
import nl.bartvandermeeren.aight.R
import nl.bartvandermeeren.aight.chat.ChatEngine
import nl.bartvandermeeren.aight.voice.SpeechText

/** Which aight surfaces the user can currently see; replies they can see need no notification. */
object AppVisibility {
    @Volatile var activityResumed = false
    @Volatile var overlayShown = false
    val anyVisible: Boolean get() = activityResumed || overlayShown
}

/**
 * Agent turns can take minutes, and the user usually switches away meanwhile. When a reply lands
 * while no aight surface is visible, post a notification that opens that chat. [quiet] skips it,
 * such as right after the agent opened an app the user is now looking at.
 */
class ReplyNotifier(
    private val context: Context,
    private val engine: ChatEngine,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
    private val quiet: () -> Boolean = { false },
) {
    private var lastSeq = engine.completedTurns.value?.seq ?: 0L

    init {
        scope.launch {
            engine.completedTurns.collect { turn ->
                if (turn == null || turn.seq <= lastSeq) return@collect
                lastSeq = turn.seq
                if (!AppVisibility.anyVisible && !quiet()) notify(turn)
            }
        }
    }

    private suspend fun notify(turn: ChatEngine.CompletedTurn) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_replies), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SESSION_ID, turn.sessionId)
        val pending = PendingIntent.getActivity(
            context, turn.sessionId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = SpeechText.fromMarkdown(turn.text).replace(Regex("\\s+"), " ").take(400)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(settings.current().assistantName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(turn.sessionId.hashCode(), notification) }
    }

    private companion object {
        const val CHANNEL = "replies"
    }
}
