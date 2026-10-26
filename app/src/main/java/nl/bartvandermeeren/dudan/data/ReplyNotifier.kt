package nl.bartvandermeeren.dudan.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.bartvandermeeren.dudan.MainActivity
import nl.bartvandermeeren.dudan.R
import nl.bartvandermeeren.dudan.chat.BackgroundStatus
import nl.bartvandermeeren.dudan.chat.ChatEngine
import nl.bartvandermeeren.dudan.device.PhoneControl
import nl.bartvandermeeren.dudan.voice.SpeechText

/**
 * Which dudan surfaces the user can currently see; replies they can see need no notification. [openSessionId] is the
 * chat the app shows, or last showed before it went to the background.
 */
object AppVisibility {
    @Volatile var activityResumed = false
    @Volatile var overlayShown = false
    @Volatile var openSessionId: String? = null
    val anyVisible: Boolean get() = activityResumed || overlayShown
}

/**
 * Agent turns can take minutes, and the user usually switches away meanwhile. When a reply lands
 * while no dudan surface is visible, post a notification that opens that chat. A turn that opened an
 * app or link on the phone gets none: the user is looking at what it opened.
 *
 * Background results get one too, once each, as long as the app process runs while they come in.
 * Early warnings that one task of a batch failed don't: the batch's results follow.
 */
class ReplyNotifier(
    private val context: Context,
    private val engine: ChatEngine,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
) {
    private var lastSeq = engine.completedTurns.value?.seq ?: 0L
    private val acks = DeliveryAcks(context)

    init {
        scope.launch {
            engine.completedTurns.collect { turn ->
                if (turn == null || turn.seq <= lastSeq) return@collect
                lastSeq = turn.seq
                if (!AppVisibility.anyVisible && !PhoneControl.openedSomething(turn.tools)) {
                    // An answer with only files names them; file names aren't markdown.
                    val body = if (turn.text.isNotBlank()) SpeechText.fromMarkdown(turn.text)
                    else context.resources.getQuantityString(R.plurals.attachment_notification, turn.files.size, turn.files.size, turn.files.joinToString(", "))
                    post(turn.sessionId, CHANNEL, R.string.notification_channel_replies, settings.current().assistantName, body)
                }
            }
        }
        scope.launch {
            engine.backgroundResults.collect { arrival ->
                if (arrival.result.interim) return@collect
                // Seen in the app counts as told, so it isn't posted later either.
                val visible = AppVisibility.anyVisible
                if (!acks.record(arrival.key) || visible) return@collect
                val result = arrival.result
                val title = when {
                    result.status == BackgroundStatus.Completed -> context.getString(R.string.background_done)
                    result.status == BackgroundStatus.PartlyFailed && result.taskCount != null ->
                        context.getString(R.string.background_partly_failed, result.failedCount, result.taskCount)
                    else -> context.getString(R.string.background_failed)
                }
                post(arrival.sessionId, BACKGROUND_CHANNEL, R.string.notification_channel_background, title, SpeechText.fromMarkdown(result.summary))
            }
        }
    }

    // One notification per chat: a later reply or result replaces the earlier one.
    private fun post(sessionId: String, channel: String, @StringRes channelName: Int, title: String, body: String) =
        postChatNotification(context, sessionId, channel, channelName, title, body)

    private companion object {
        const val CHANNEL = "replies"
        const val BACKGROUND_CHANNEL = "background"
    }
}

/**
 * Posts a notification that opens [sessionId] in the app, when the user allows notifications. [body] is plain text;
 * callers turn markdown into it first. A later notification with the same [tag] and [id] replaces this one.
 */
internal fun postChatNotification(
    context: Context,
    sessionId: String,
    channel: String,
    @StringRes channelName: Int,
    title: String,
    body: String,
    tag: String? = null,
    id: Int = sessionId.hashCode(),
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(NotificationChannel(channel, context.getString(channelName), NotificationManager.IMPORTANCE_DEFAULT))
    val open = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
    val pending = PendingIntent.getActivity(
        context, sessionId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val text = body.replace(Regex("\\s+"), " ").take(400)
    val notification = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(pending)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .build()
    runCatching { NotificationManagerCompat.from(context).notify(tag, id, notification) }
}
