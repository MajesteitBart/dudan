package nl.bartvandermeeren.dudan.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.bartvandermeeren.dudan.voice.DesktopSpeaker
import nl.bartvandermeeren.dudan.voice.Kokoro
import nl.bartvandermeeren.dudan.voice.ModelStore
import nl.bartvandermeeren.dudan.voice.Supertonic
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The voices for real: downloads Kokoro and Supertonic into a folder (about 750 MB the first time; a folder
 * that has them already is reused) and reads an English and a Dutch sentence through the PC's sound output.
 * It only runs when given the folder:
 *   ./gradlew :desktop:test --tests '*VoiceLiveTest*' -Pdudan.voices=/tmp/dudan-dev/voices
 */
class VoiceLiveTest {
    private val dir: String? = System.getProperty("dudan.voices")

    @Before
    fun needsFolder() {
        assumeTrue("Pass -Pdudan.voices=<folder> to download and hear the voices", dir != null)
    }

    @Test
    fun downloadsBothVoicesAndReadsAloud() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()
        try {
            val kokoro = ModelStore(File(dir!!), http, scope, Kokoro.MODEL)
            val supertonic = ModelStore(File(dir), http, scope, Supertonic.MODEL)
            listOf(kokoro, supertonic).forEach { model ->
                model.download()
                val until = System.currentTimeMillis() + 30 * 60_000
                while (model.state.value !is ModelStore.State.Ready && model.state.value !is ModelStore.State.Failed && System.currentTimeMillis() < until) Thread.sleep(500)
                assertEquals("${model.spec.name}: ${model.state.value}", ModelStore.State.Ready, model.state.value)
            }

            val speaker = DesktopSpeaker(kokoro, supertonic, { Kokoro.DEFAULT }, { Supertonic.DEFAULT }, windowsLanguage = "nl")
            listOf(
                "english" to "Hi Bart. This is dudan, reading a reply aloud on your PC.",
                "dutch" to "Hoi Bart. Dit is dudan, die om 14:35 een antwoord van 1250 woorden voorleest.",
            ).forEach { (id, text) ->
                val started = System.currentTimeMillis()
                var result: DesktopSpeaker.Result? = null
                SwingUtilities.invokeAndWait { result = speaker.speak(id, text) }
                assertEquals(DesktopSpeaker.Result.Started, result)
                while (speaker.speakingId.value == id && System.currentTimeMillis() - started < 60_000) Thread.sleep(50)
                val took = System.currentTimeMillis() - started
                println("$id: read in $took ms")
                assertEquals("reading $id broke off", 0, speaker.failed.value)
                // Speech takes seconds; ending at once means nothing played.
                assertTrue("$id ended after $took ms", took > 2_000)
            }
        } finally {
            scope.cancel()
        }
    }
}
