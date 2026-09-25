# aight

![aight](assets/aight-wordmark.png)

An Android app that looks and works like the Gemini app, with a Hermes Agent as its brain. It runs as a normal chat app and as the phone's digital assistant, so a long press on the side key opens aight's overlay instead of Gemini.

Everything the agent does happens on the Hermes server. The app talks to the Hermes API server over Tailscale, shows the agent's tool steps while it works, and keeps the conversation history in Hermes, so chats also show up in the Hermes dashboard and CLI.

## What it does

- Gemini's layout and styling: sidebar with recent chats, a greeting under the aight orb, pill composer with the navy glow, streaming markdown with code blocks and tables. The Fold 7 inner screen gets a docked sidebar; the cover screen gets a full-screen drawer.
- Agent turns go through Hermes' Runs API. If the phone loses the connection mid-task, the run keeps going on the server and the app polls until the result is in.
- A collapsible "Worked for 1m 10s" panel shows tool calls, commentary and reasoning. Dangerous commands surface as an approval card with Hermes' choices (once, this chat, always, deny).
- Voice input with a live waveform, read-aloud per reply, and a hands-free Live mode. Speech is transcribed on the phone by [Orukeet](https://github.com/Oruk-AI/orukeet), Oruk's multilingual fine-tune of NVIDIA Parakeet TDT 0.6B v3, which handles Dutch and English without a language setting. English replies are read by Kokoro-82M, also on the phone; Dutch replies use the Android voice because Kokoro has no Dutch.
- Assistant overlay on the side key: starts listening right away, can attach the current screen ("Ask about screen"), reads spoken questions' answers aloud, and hands off to the full app.
- Model picker backed by Hermes' model inventory, with Auto, Fast and Extended thinking. There are two defaults: one for chats started in the app, and a quick one for chats started from the assistant overlay or the assist gesture. A chat keeps the model of the place it started, also when you continue it in the app.
- Search, pin, rename and delete chats; browse Hermes skills; run, pause or resume scheduled tasks.
- English and Dutch UI.

## Hermes side

aight needs the Hermes API server. In `~/.hermes/.env` on the Hermes host:

```
API_SERVER_ENABLED=true
API_SERVER_KEY=<at least 16 random characters, e.g. openssl rand -hex 32>
API_SERVER_HOST=0.0.0.0      # or the Tailscale IP, so the phone can reach it
```

Restart the gateway (`hermes gateway restart`) and check `curl http://<host>:8642/health`.

Keep the port inside the tailnet. The API server runs agent turns with terminal access, so anyone holding the key can run commands on the host. Plain `http://` is fine over Tailscale because WireGuard encrypts the traffic; the app warns when an `http://` address points outside the tailnet.

## Phone setup

1. Install the APK. On the build PC, `node tools/serve-apk.mjs <tailscale-ip> 8787` serves everything in `artifacts/`, so the phone can open `http://<tailscale-ip>:8787/` and download it. Taildrop works too.
2. Enter the server URL (`http://clarkbox:8642`), the API key, your name and the assistant's name, then tap Connect.
3. Make aight the assistant: Settings > Apps > Choose default apps > Digital assistant app > Device assistance app > aight. The in-app Settings screen has a shortcut and shows whether it worked.
4. On Samsung, set Settings > Advanced features > Side button > Press and hold to "Digital assistant".
5. For "Ask about screen", enable "Use screenshot" in the same Digital assistant settings.
6. On the first Wi-Fi connection the app downloads its two on-device models: Orukeet for speech input (487 MB download, 672 MB unpacked) and Kokoro for reading aloud (132 MB, 168 MB unpacked). Settings > Speech input and Settings > Voice show the progress and have a download button for mobile data. Until Orukeet is in, voice input uses the phone's recognizer.

Choosing aight as assistant also makes its recognition service the system default. That service forwards to the phone's real recognizer, so voice typing in other apps keeps working.

## Build

Requires JDK 17 and the Android SDK (compileSdk 36). The first build downloads the sherpa-onnx AAR (Kokoro's runtime, 39 MB) from GitHub into `app/libs` and checks its SHA-256.

```
./gradlew testDebugUnitTest      # protocol, reducer and parser tests
./gradlew assembleDebug          # app/build/outputs/apk/debug, package nl.bartvandermeeren.aight.debug, arm64 + x86_64
./gradlew assembleRelease        # app/build/outputs/apk/release, minified and signed, arm64 only
```

Release signing reads `signing.properties` and `signing/aight-release.jks`. Both are git-ignored and only exist on the build machine. Keep a copy of the keystore: Android only accepts updates signed with the same key, so losing it means uninstalling and setting the app up again. Without `signing.properties`, release builds fall back to the debug key.

## Developing without a live agent

`tools/mock-hermes/server.mjs` imitates the Hermes API server (sessions, runs with SSE, approvals, model options, skills and jobs) with scripted replies:

```
node tools/mock-hermes/server.mjs 8650
```

In the emulator, connect to `http://10.0.2.2:8650` with key `dev-key-aight-0000000000`. Messages containing "verwijder" or "delete" trigger an approval request.

Use the debug build on an x86_64 emulator. Release builds only contain arm64 libraries; the emulator runs those through ARM translation, and Kokoro's native library crashes there while loading.

Questions containing "english" get an English reply from the mock, for testing Kokoro.

The emulator's microphone only hears silence. In debug builds, a WAV file at `files/debug-mic.wav` replaces the microphone and plays in real time, followed by silence:

```
adb push question.wav /data/local/tmp/
adb shell run-as nl.bartvandermeeren.aight.debug cp /data/local/tmp/question.wav files/debug-mic.wav
```

To make an emulator treat aight as its assistant without clicking through settings:

```
adb shell cmd role add-role-holder android.app.role.ASSISTANT nl.bartvandermeeren.aight.debug 0
adb shell input keyevent 219   # KEYCODE_ASSIST
```

## Code map

- `data/`: Hermes HTTP client, SSE reader, payload parsing, settings with the API key encrypted by an Android Keystore key
- `chat/`: `ChatEngine` owns every conversation and run for the whole process, shared by the app, the overlay and Live mode; `TurnReducer` folds stream events into a message; `HistoryMapper` turns a Hermes transcript into bubbles
- `assist/`: voice interaction service, the overlay session (Compose inside a `VoiceInteractionSession`) and the proxy recognition service
- `voice/`: `ModelPackage` downloads and verifies the on-device models; `SpeechInput` records with Orukeet (`LocalSpeechSession`, `Endpointer`, `OrukeetEngine`) or falls back to the phone's recognizer; `KokoroVoice` reads aloud through sherpa-onnx, and `Speaker` picks Kokoro or the Android voice per reply
- `ui/`: Compose screens and the Gemini-style components
- `assets/`: the aight icon and wordmark as SVG and PNG. The launcher icon (`res/drawable/ic_launcher_*.xml`, `ic_notification.xml`) and the in-app mark (`AightMark` in `ui/components/Brand.kt`) are redrawn from `aight-icon.svg`, so update them together when the artwork changes

## Known limits

- Hermes' Runs API takes text only, so turns with images use the session chat stream. Those turns stop if the connection drops mid-run.
- Hermes accepts images but no other files, so the attach menu offers photos and the camera only.
- The side-key overlay needs the microphone permission granted once in the app. Without it, the overlay opens the app to ask.
- Kokoro synthesizes a whole sentence before it plays it, so a long first sentence delays the start of speech. On the x86 emulator one sentence of about 4 seconds took 4 to 5 seconds to synthesize; a phone's arm64 cores are faster, but this hasn't been measured on the Fold 7 yet.
- Orukeet transcribes after you stop talking (1.1 s of silence ends a question), with previews of the text so far. Voice typing in other apps still goes to the phone's recognizer.
- Orukeet and Kokoro together take about 1 GB of memory while loaded. Each is released after a few idle minutes.
- If Kokoro's native library ever crashes the app while loading, the next start notices, switches replies to the Android voice and shows a Retry link under Settings > Voice.

## Model credits

The app downloads these models at first use; they are not part of this repository.

- Orukeet v0.1.0 by Oruk AI, weights under CC BY-SA 4.0, adapted from NVIDIA Parakeet TDT 0.6B v3 (CC BY 4.0).
- Kokoro-82M by hexgrad, Apache 2.0, in the sherpa-onnx packaging.
- Silero VAD, MIT.
