package nl.bartvandermeeren.aight.data

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
import nl.bartvandermeeren.aight.chat.ChatEngine
import nl.bartvandermeeren.aight.device.PhoneControl
import nl.bartvandermeeren.aight.voice.KokoroVoice
import nl.bartvandermeeren.aight.voice.ModelPackage
import nl.bartvandermeeren.aight.voice.OrukeetEngine
import nl.bartvandermeeren.aight.voice.Speaker
import nl.bartvandermeeren.aight.voice.SupertonicVoice
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

    val engine = ChatEngine(api, appScope) { settings.current() }

    val kokoroModel = ModelPackage(appContext, http, appScope, ModelPackage.KOKORO)

    val orukeetModel = ModelPackage(appContext, http, appScope, ModelPackage.ORUKEET)

    val supertonicModel = ModelPackage(appContext, http, appScope, ModelPackage.SUPERTONIC)

    /** Speech recognition on the phone, shared by dictation, the overlay and Live. */
    val orukeet: OrukeetEngine by lazy { OrukeetEngine(orukeetModel) }

    val speaker: Speaker by lazy {
        Speaker(appContext, KokoroVoice(appContext, kokoroModel), SupertonicVoice(appContext, supertonicModel)) { settingsSnapshot.value }
    }

    val phoneControl = PhoneControl(appContext, settingsSnapshot)

    init {
        ReplyNotifier(appContext, engine, settings, appScope)
        appScope.launch {
            settings.flow.map { it.phoneControl }.distinctUntilChanged().collect(phoneControl::sync)
        }
        // Kokoro is the default voice: fetch its model on the first unmetered network, also when only the overlay runs.
        appScope.launch {
            settings.flow.map { it.ttsEngine }.distinctUntilChanged().collect { engine ->
                if (engine == TtsEngine.Kokoro) kokoroModel.downloadWhenFree() else kokoroModel.stopWaitingForNetwork()
            }
        }
        appScope.launch {
            settings.flow.map { it.dutchTtsEngine }.distinctUntilChanged().collect { engine ->
                if (engine == DutchTtsEngine.Supertonic) supertonicModel.downloadWhenFree() else supertonicModel.stopWaitingForNetwork()
            }
        }
        appScope.launch {
            settings.flow.map { it.sttEngine }.distinctUntilChanged().collect { engine ->
                if (engine == SttEngine.Orukeet) orukeetModel.downloadWhenFree() else orukeetModel.stopWaitingForNetwork()
            }
        }
    }
}
