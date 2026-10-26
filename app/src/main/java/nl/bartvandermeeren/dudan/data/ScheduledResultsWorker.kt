package nl.bartvandermeeren.dudan.data

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import nl.bartvandermeeren.dudan.DudanApp
import nl.bartvandermeeren.dudan.R
import okhttp3.OkHttpClient

private val Context.scheduledStore by preferencesDataStore(name = "scheduled_results")

/**
 * Tells the user about scheduled task results that came in while dudan wasn't looking. Clark has no push to the phone,
 * so [ScheduledResultsWorker] asks every 15 minutes: it lists the chats, reads the latest rows of each task chat with
 * activity after its mark, and posts one notification per new result. The decisions are in [ScheduledCatchUp]; this
 * keeps the marks and posts. The chat on screen while the app is in front gets no notification: the drawer and the chat
 * are brought up to date instead. A chat behind another screen, such as Scheduled tasks, still gets one.
 */
class ScheduledResultsCheck(private val context: Context) {
    private val key = stringPreferencesKey("marks")

    private suspend fun read(): SeenMarks? = SeenMarks.decode(context.scheduledStore.data.first()[key])

    /** Changes the marks for [server]; when they belong to another server or there are none yet, [change] gets null. */
    private suspend fun update(server: String, change: (SeenMarks?) -> SeenMarks?) {
        context.scheduledStore.edit { prefs ->
            change(SeenMarks.decode(prefs[key])?.takeIf { it.server == server })?.let { prefs[key] = it.encode() }
        }
    }

    /**
     * The user saw [sessionId] up to [upToSeconds], the server time of its newest result on screen. Its notifications
     * go away. Before the first check there is nothing to mark: that check counts everything as seen.
     */
    suspend fun markSeen(server: String, sessionId: String, upToSeconds: Double) {
        update(server) { it?.seen(sessionId, upToSeconds) }
        cancelNotifications(sessionId)
    }

    /** One round of the check. [app] is the running app, when this process has one. */
    suspend fun check(api: HermesApi, server: String, app: AppContainer?) {
        val listing = api.sessionListing(source = "api_server")
        val sessions = listing.sessions
        // Without a clock or any activity the server has nothing to report yet.
        val now = ScheduledCatchUp.serverNow(sessions, listing.serverSeconds) ?: return
        val stored = read()?.takeIf { it.server == server }
        if (stored == null) {
            // The first round on this server: everything so far counts as seen.
            update(server) { it ?: ScheduledCatchUp.start(server, now) }
            return
        }
        val due = ScheduledCatchUp.due(sessions, stored)
        for (session in due) {
            val rows = try {
                api.sessionTail(session.id, TAIL_ROWS).messages
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            // Read again: the user may have opened the chat meanwhile.
            val mark = read()?.takeIf { it.server == server }?.markFor(session.id) ?: return
            if (!AppVisibility.shows(session.id)) ScheduledCatchUp.fresh(rows, mark).forEach { post(session, it) }
            update(server) { it?.seen(session.id, ScheduledCatchUp.advance(mark, session.lastActive, rows, now)) }
        }
        if (due.isNotEmpty() && AppVisibility.activityResumed && app != null) {
            withContext(Dispatchers.Main) {
                app.engine.refreshSessions()
                AppVisibility.openSessionId?.let { app.engine.load(it) }
            }
        }
    }

    private fun post(session: SessionSummary, row: HermesMessage) {
        val files = row.attachments.map { it.name }
        val body = ScheduledCatchUp.preview(row).ifBlank {
            if (files.isEmpty()) "" else context.resources.getQuantityString(R.plurals.attachment_notification, files.size, files.size, files.joinToString(", "))
        }
        val id = (row.id ?: row.timestamp?.toString() ?: body).hashCode()
        postChatNotification(context, session.id, CHANNEL, R.string.notification_channel_scheduled, ScheduledCatchUp.title(row, session), body, tagFor(session.id), id)
    }

    private fun cancelNotifications(sessionId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val tag = tagFor(sessionId)
        runCatching { manager.activeNotifications.filter { it.tag == tag }.forEach { manager.cancel(it.tag, it.id) } }
    }

    private companion object {
        const val CHANNEL = "scheduled"
        /** Results land at the end of a chat; more than this many since the last check is unusual. */
        const val TAIL_ROWS = 40

        fun tagFor(sessionId: String) = "scheduled:$sessionId"
    }
}

/**
 * Runs [ScheduledResultsCheck] in the background. It uses the running app's client and chats when there is one, and
 * otherwise doesn't start the app's services just for this.
 */
class ScheduledResultsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = (applicationContext as DudanApp).existingContainer
        val settings = app?.settings ?: SettingsRepository(applicationContext)
        val current = settings.current()
        if (!current.isConfigured) return Result.success()
        val api = app?.api ?: HermesApi(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()) { current.server }
        try {
            (app?.scheduledResults ?: ScheduledResultsCheck(applicationContext)).check(api, current.serverUrl, app)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Out of reach or refused: the next round tries again.
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "scheduled-results"

        /** Every 15 minutes, Android's shortest period, while the phone has a network. Keeps a schedule that exists. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduledResultsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
