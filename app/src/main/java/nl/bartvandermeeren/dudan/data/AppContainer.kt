package nl.bartvandermeeren.dudan.data

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.bartvandermeeren.dudan.assist.WakeWordService
import nl.bartvandermeeren.dudan.chat.ChatEngine
import nl.bartvandermeeren.dudan.device.PhoneControl
import nl.bartvandermeeren.dudan.voice.KokoroVoice
import nl.bartvandermeeren.dudan.voice.ModelPackage
import nl.bartvandermeeren.dudan.voice.OrukeetEngine
import nl.bartvandermeeren.dudan.voice.Speaker
import nl.bartvandermeeren.dudan.voice.SupertonicVoice
import nl.bartvandermeeren.dudan.voice.VoiceLanguages
import okhttp3.OkHttpClient

/** Process-wide singletons. The chat engine lives here so the app and the assistant overlay share runs. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsRepository(appContext)

    /** Latest settings for code that can't suspend, such as picking a voice when a reply lands. */
    val settingsSnapshot: StateFlow<AppSettings> = settings.flow.stateIn(appScope, SharingStarted.Eagerly, AppSettings())

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Hermes sends an SSE keepalive every 10 s, so a long read timeout only trips on a dead link.
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val api = HermesApi(http) { settings.current().server }

    /** A client bound to unsaved credentials, for the connection test in setup. */
    fun apiFor(config: HermesApi.ServerConfig) = HermesApi(http) { config }

    // An 8 MB chunk over a weak mobile connection can take a minute on its own.
    private val uploadHttp = http.newBuilder()
        .writeTimeout(3, TimeUnit.MINUTES)
        .readTimeout(3, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false)
        .build()

    /** Attachments go to the upload service next to Hermes; see tools/hermes-upload. */
    val uploads = UploadClient(uploadHttp) { settings.current().uploads }

    val skillCatalog = SkillCatalog(api, appScope)

    val engine = ChatEngine(api, appScope, skills = skillCatalog::skills) { settings.current() }

    val kokoroModel = ModelPackage(appContext, http, appScope, ModelPackage.KOKORO)

    val orukeetModel = ModelPackage(appContext, http, appScope, ModelPackage.ORUKEET)

    val supertonicModel = ModelPackage(appContext, http, appScope, ModelPackage.SUPERTONIC)

    /** The wake word spotter; 18 MB, so it downloads as soon as the wake word is switched on. */
    val wakeWordModel = ModelPackage(appContext, http, appScope, ModelPackage.WAKE_WORD)

    /** Speech recognition on the phone, shared by dictation, the overlay and Live. */
    val orukeet: OrukeetEngine by lazy { OrukeetEngine(orukeetModel, appContext.assets) }

    val speaker: Speaker by lazy {
        Speaker(appContext, KokoroVoice(appContext, kokoroModel), SupertonicVoice(appContext, supertonicModel)) { settingsSnapshot.value }
    }

    val phoneControl = PhoneControl(appContext, settingsSnapshot)

    /**
     * Starts the wake word listener when it's on. Call when dudan comes on screen or the assistant service
     * binds: those are the moments Android lets a microphone service start.
     */
    fun ensureWakeWord() {
        if (WakeWordService.listens(settingsSnapshot.value)) WakeWordService.start(appContext)
    }

    init {
        ReplyNotifier(appContext, engine, settings, appScope)
        appScope.launch {
            settings.flow.map { WakeWordService.listens(it) }.distinctUntilChanged().collect { on ->
                if (on) {
                    wakeWordModel.download()
                    WakeWordService.start(appContext)
                } else {
                    WakeWordService.stop(appContext)
                }
            }
        }
        appScope.launch {
            settings.flow.map { it.phoneControl }.distinctUntilChanged().collect(phoneControl::sync)
        }
        // Fetch the voice models the phone's languages and the speech language use on the first unmetered
        // network, also when only the overlay runs. An English phone gets Kokoro; a Dutch or German one, or
        // speech input in Dutch or German, Supertonic as well.
        appScope.launch {
            settings.flow.map { VoiceLanguages.needed(VoiceLanguages.rows(VoiceLanguages.languages(it.speechLanguage), it.voices), it.voices) }
                .distinctUntilChanged()
                .collect { needed ->
                    if (VoiceEngine.Kokoro in needed) kokoroModel.downloadWhenFree() else kokoroModel.stopWaitingForNetwork()
                    if (VoiceEngine.Supertonic in needed) supertonicModel.downloadWhenFree() else supertonicModel.stopWaitingForNetwork()
                }
        }
        appScope.launch {
            settings.flow.map { it.sttEngine }.distinctUntilChanged().collect { engine ->
                if (engine == SttEngine.Orukeet) orukeetModel.downloadWhenFree() else orukeetModel.stopWaitingForNetwork()
            }
        }
    }
}
