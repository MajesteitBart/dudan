package nl.bartvandermeeren.dudan.assist

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * Clark in the app list, there once Settings > Assistant turns it on. Opening it, with a tap or with
 * "Hi Bixby, open Clark", opens the overlay over the app underneath, listening. Bixby listens for its
 * own wake word, so this starts a question by voice without dudan keeping the microphone open.
 *
 * It's named after the default wake phrase, "Ok Clark". Android reads an app's name from the manifest,
 * so the name stays when the user changes the wake phrase or the assistant's name.
 */
class ListenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without a window, Android requires finish() before onResume. An exception thrown from here
        // would end the process instead, so a failure to open is only logged.
        try {
            DudanVoiceInteractionService.listen(this)
        } catch (e: Exception) {
            Log.w("DudanWake", "Could not open the assistant from the app list", e)
        }
        finish()
    }

    companion object {
        private fun component(context: Context) = ComponentName(context, ListenActivity::class.java)

        fun isInAppList(context: Context) =
            context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        /** Off by default, so only people who use it get a second icon. Android keeps it across updates and restarts. */
        fun setInAppList(context: Context, on: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
