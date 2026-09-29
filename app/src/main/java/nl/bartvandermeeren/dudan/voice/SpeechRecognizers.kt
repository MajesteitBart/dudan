package nl.bartvandermeeren.dudan.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Picks a real speech recognizer. When dudan is the default assistant, Android makes our own
 * recognition service the system default, so we must always bind to someone else's service.
 */
object SpeechRecognizers {
    private val preferredPackages = listOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.as",
        "com.google.android.tts",
        "com.samsung.android.bixby.agent",
    )

    /** Installed recognition services other than ours, best first. */
    fun candidates(context: Context): List<ComponentName> {
        val services = context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
        return services.sortedBy { service ->
            preferredPackages.indexOf(service.packageName).let { if (it < 0) preferredPackages.size else it }
        }
    }

    fun create(context: Context, component: ComponentName? = candidates(context).firstOrNull()): SpeechRecognizer? {
        if (component != null) return SpeechRecognizer.createSpeechRecognizer(context, component)
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        return null
    }
}
