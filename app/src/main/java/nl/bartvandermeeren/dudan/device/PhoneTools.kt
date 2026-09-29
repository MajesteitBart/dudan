package nl.bartvandermeeren.dudan.device

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.view.KeyEvent
import androidx.core.net.toUri
import java.util.Calendar
import nl.bartvandermeeren.dudan.device.PhoneControl.Launch

/** The tools Hermes can call on this phone. Each returns a sentence the agent can pass on. */
internal class PhoneTools(private val context: Context, private val control: PhoneControl) {

    fun all(): List<PhoneTool> = listOf(openApp(), listApps(), openLink(), setTimer(), setAlarm(), media(), status())

    private fun openApp() = PhoneTool(
        name = "open_app",
        description = "Open an app on the user's phone. Pass the name the way the user says it (\"Spotify\", \"maps\") " +
            "or an Android package name. When several apps match, the reply lists them; call again with the package name.",
        inputSchema = objectSchema("name" to stringProperty("App name or package name"), required = listOf("name")),
    ) { args ->
        val query = args.requireString("name")
        val apps = launchableApps()
        when (val match = AppMatcher.find(query, apps)) {
            is AppMatcher.Match.One -> {
                val app = match.app
                // A label that names one launcher entry opens that entry; a package name, or a label several
                // entries share, opens the app the way its main icon does.
                val specific = !query.equals(app.packageName, ignoreCase = true) &&
                    apps.count { it.packageName == app.packageName && it.label == app.label } == 1
                val intent = (if (specific) null else context.packageManager.getLaunchIntentForPackage(app.packageName))
                    ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(app.packageName, app.activity)
                intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                report(control.launch(intent, app.label), "Opened ${app.label}.", app.label)
            }
            is AppMatcher.Match.Several -> throw ToolFailure(
                "Several apps match \"$query\": ${match.apps.joinToString { "${it.label} (${it.packageName})" }}. " +
                    "Call open_app again with the package name.",
            )
            AppMatcher.Match.None -> throw ToolFailure("No app on the phone matches \"$query\". list_apps shows what's installed.")
        }
    }

    private fun listApps() = PhoneTool(
        name = "list_apps",
        description = "List the apps on the user's phone that have a launcher icon, as name and package name.",
        inputSchema = objectSchema("query" to stringProperty("Optional part of the app name to filter on")),
        readOnly = true,
    ) { args ->
        val query = args.optionalString("query")?.let(AppMatcher::normalize)
        val apps = launchableApps()
            .filter { query == null || AppMatcher.normalize(it.label).contains(query) }
            .distinctBy { it.label to it.packageName }
            .sortedBy { it.label.lowercase() }
        if (apps.isEmpty()) "No apps match." else apps.joinToString("\n") { "${it.label}: ${it.packageName}" }
    }

    private fun openLink() = PhoneTool(
        name = "open_link",
        description = "Open a link on the user's phone: a web page, a place (geo:0,0?q=Rijksmuseum), directions " +
            "(google.navigation:q=Utrecht+Centraal), a phone number (tel:, opens the dialer without calling), an email or " +
            "text draft (mailto:, sms:), or an app's deep link (spotify:, whatsapp://send?phone=...&text=...). Calls and " +
            "messages still need the user to press call or send. Pass app to open the link in a specific app.",
        inputSchema = objectSchema(
            "url" to stringProperty("The URL or deep link"),
            "app" to stringProperty("Optional package name of the app that should open the link"),
            required = listOf("url"),
        ),
    ) { args ->
        val url = args.requireString("url")
        val action = LinkRules.actionFor(url) ?: throw ToolFailure("The phone doesn't open this kind of link: $url")
        val intent = Intent(if (action == LinkRules.Action.Dial) Intent.ACTION_DIAL else Intent.ACTION_VIEW, url.toUri())
        args.optionalString("app")?.let(intent::setPackage)
        report(control.launch(intent, url), "Opened $url.", url)
    }

    private fun setTimer() = PhoneTool(
        name = "set_timer",
        description = "Start a countdown timer in the phone's clock app.",
        inputSchema = objectSchema(
            "seconds" to intProperty("Length of the timer in seconds", 1, MAX_TIMER_SECONDS),
            "label" to stringProperty("Optional name shown with the timer"),
            required = listOf("seconds"),
        ),
    ) { args ->
        val seconds = args.requireInt("seconds", 1, MAX_TIMER_SECONDS)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optionalString("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        report(control.launch(intent, "the clock app", reveals = false), "Started a ${duration(seconds)} timer.", "the timer")
    }

    private fun setAlarm() = PhoneTool(
        name = "set_alarm",
        description = "Set an alarm in the phone's clock app, once or repeating on weekdays.",
        inputSchema = objectSchema(
            "hour" to intProperty("Hour, 0 to 23", 0, 23),
            "minute" to intProperty("Minute, 0 to 59", 0, 59),
            "label" to stringProperty("Optional name shown with the alarm"),
            "days" to stringArrayProperty("Days to repeat on; leave out for a one-time alarm", DAYS.keys.toList()),
            required = listOf("hour", "minute"),
        ),
    ) { args ->
        val hour = args.requireInt("hour", 0, 23)
        val minute = args.requireInt("minute", 0, 59)
        val days = args.stringList("days").map { DAYS[it] ?: throw ToolFailure("Unknown day: $it") }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optionalString("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        val time = "%02d:%02d".format(hour, minute)
        val repeat = if (days.isEmpty()) "" else " on ${args.stringList("days").joinToString()}"
        report(control.launch(intent, "the clock app", reveals = false), "Set an alarm for $time$repeat.", "the alarm")
    }

    private fun media() = PhoneTool(
        name = "media",
        description = "Control what's playing on the phone (music, podcasts, video): play, pause, skip, or set the media volume.",
        inputSchema = objectSchema(
            "action" to stringProperty("What to do", MEDIA_KEYS.keys.toList() + "set_volume"),
            "volume_percent" to intProperty("Media volume for set_volume", 0, 100),
            required = listOf("action"),
        ),
    ) { args ->
        val audio = context.getSystemService(AudioManager::class.java)
        when (val action = args.requireString("action")) {
            "set_volume" -> {
                val percent = args.optionalInt("volume_percent", 0, 100) ?: throw ToolFailure("set_volume needs volume_percent")
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max + 50) / 100, 0)
                "Set the media volume to $percent%."
            }
            else -> {
                val code = MEDIA_KEYS[action] ?: throw ToolFailure("Unknown action: $action")
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                "Sent $action to the phone's media player."
            }
        }
    }

    private fun status() = PhoneTool(
        name = "phone_status",
        description = "Check the user's phone: battery, screen and lock, ringer and Do Not Disturb, media volume, " +
            "network, and whether apps can be opened on it right now.",
        inputSchema = objectSchema(),
        readOnly = true,
    ) {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val volume = 100 * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        val dnd = notifications.currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
        listOf(
            "Battery: $level%${if (battery.isCharging) ", charging" else ""}",
            "Screen: ${if (power.isInteractive) "on" else "off"}, ${if (keyguard.isKeyguardLocked) "locked" else "unlocked"}",
            "Ringer: $ringer; Do Not Disturb ${if (dnd) "on" else "off"}",
            "Media: volume $volume%, ${if (audio.isMusicActive) "playing" else "nothing playing"}",
            "Network: ${network()}",
            "Opening apps: " + if (control.canStartActivities()) "works now" else
                "shows a notification the user taps, because dudan isn't on screen, isn't the default assistant " +
                "and lacks \"Display over other apps\"",
        ).joinToString("\n")
    }

    private fun network(): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "connected"
        }
    }

    private fun launchableApps(): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return found.map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
    }

    private fun report(outcome: Launch, done: String, what: String) = when (outcome) {
        Launch.Opened -> done
        Launch.OpenedWhileLocked -> "$done The phone is locked, so the user sees it after unlocking."
        Launch.Notified -> "Android doesn't let dudan open $what while it's in the background, so the phone shows a " +
            "notification that does it when the user taps it."
    }

    private fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            "$h hour${if (h == 1) "" else "s"}".takeIf { h > 0 },
            "$m minute${if (m == 1) "" else "s"}".takeIf { m > 0 },
            "$s second${if (s == 1) "" else "s"}".takeIf { s > 0 },
        ).joinToString(" ")
    }

    private companion object {
        const val MAX_TIMER_SECONDS = 24 * 3600

        val DAYS = linkedMapOf(
            "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY,
            "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY,
        )

        val MEDIA_KEYS = linkedMapOf(
            "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
            "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
            "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
    }
}
