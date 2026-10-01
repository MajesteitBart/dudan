# Build and develop dudan

[Back to dudan](../README.md) | [Documentation](README.md)

## Build

Requires JDK 17 and the Android SDK (compileSdk 36). The first build downloads the sherpa-onnx AAR (Kokoro's runtime, 39 MB) from GitHub into `app/libs` and checks its SHA-256.

```
./gradlew testDebugUnitTest      # protocol, reducer and parser tests
./gradlew assembleDebug          # app/build/outputs/apk/debug, package nl.bartvandermeeren.aight.debug, arm64 + x86_64
./gradlew assembleRelease        # app/build/outputs/apk/release, minified and signed, arm64 only
```

Release signing reads `signing.properties` and `signing/dudan-release.jks`. Both are git-ignored and only exist on the build machine. Keep a copy of the keystore: Android only accepts updates signed with the same key, so losing it means uninstalling and setting the app up again. Without `signing.properties`, release builds fall back to the debug key.

## Publish a release

Releases live on GitHub with the tag `android-v<version>`, the title `dudan v<version>`, and two files: `dudan-release-arm64-v8a-v<version>-<commit>.apk` and `SHA256SUMS`. Raise `versionName` and `versionCode` in `app/build.gradle.kts`, merge that to `main`, then on the build machine with the keystore:

```
git switch main && git pull
node tools/release-apk.mjs
```

The script refuses to run with uncommitted changes or without `signing.properties`. It runs the unit tests, builds the release APK, checks that it isn't signed with the debug key, and writes the APK, `SHA256SUMS` and `notes.md` to `artifacts/android-v<version>/`. The notes list the package, version code, commit and signing certificate. Fill in what's new, then publish with the `gh release create` command the script prints.

## Developing without a live agent

`tools/mock-hermes/server.mjs` imitates the Hermes API server (sessions, runs with SSE, approvals, model options, skills and jobs) with scripted replies:

```
node tools/mock-hermes/server.mjs 8650
```

In the emulator, connect to `http://10.0.2.2:8650` with key `dev-key-dudan-0000000000`. Messages containing "verwijder" or "delete" trigger an approval request. The mock answers in Dutch; start it with `MOCK_LANG=en` for English chats and replies, as in the README screenshots.

The mock also runs the upload service's protocol on port 8645 (a second argument changes it), so attachments work in the emulator and the reply names the files it got. With Rich replies on, messages containing "vergelijk", "formulier", "grafiek" or "stappen" get scripted OpenUI replies, and "supermarkt", "backup" or "energieverbruik" get replies a model wrote from dudan's OpenUI instructions (`tools/mock-hermes/samples/`). "offerte" gets a real Hermes reply to an uploaded PDF quote: a table, a name/value list and a note. A unit test parses those samples too. Messages containing "op de achtergrond" or "in the background" send a subagent off the way Hermes' `delegate_task` does: the turn ends at once, and 20 seconds later the result lands in the chat without a new turn. `MOCK_BACKGROUND_MS` changes the delay.

Use the debug build on an x86_64 emulator. Release builds only contain arm64 libraries; the emulator runs those through ARM translation, and Kokoro's native library crashes there while loading.

Questions containing "english" get an English reply from the mock, for testing Kokoro.

The emulator's microphone only hears silence. In debug builds, a WAV file at `files/debug-mic.wav` replaces the microphone and plays in real time, followed by silence:

```
adb push question.wav /data/local/tmp/
adb shell run-as nl.bartvandermeeren.aight.debug cp /data/local/tmp/question.wav files/debug-mic.wav
```

To make an emulator treat dudan as its assistant without clicking through settings:

```
adb shell cmd role add-role-holder android.app.role.ASSISTANT nl.bartvandermeeren.aight.debug 0
adb shell input keyevent 219   # KEYCODE_ASSIST
```

## Code map

- `data/`: Hermes HTTP client, SSE reader, payload parsing, settings with the API key encrypted by an Android Keystore key. `UploadClient` talks to the upload service; `AttachmentNotes` writes and reads Hermes' file notes
- `openui/`: OpenUI Lang parser and evaluator (`OpenUiParser`, `OpenUiRuntime`), the instructions the agent gets (`OpenUiPrompt`) and the markdown export for copy and speech (`OpenUiText`). `ui/openui/` renders the components
- `chat/`: `ChatEngine` owns every conversation and run for the whole process, shared by the app, the overlay and Live mode; `TurnReducer` folds stream events into a message; `HistoryMapper` turns a Hermes transcript into bubbles; `BackgroundWork` reads which subagents the agent sent off and what they delivered
- `assist/`: voice interaction service, the overlay session (Compose inside a `VoiceInteractionSession`) and the proxy recognition service
- `device/`: phone control. `PhoneTools` implements the tools, `McpHandler` speaks MCP's JSON-RPC, `McpHttpServer` serves it on the Tailscale address (`Tailnet`), `PhoneControlService` keeps it running, and `PhoneControl` starts activities or falls back to a notification. `tools/hermes-phone-bridge/` is the Hermes side
- `voice/`: `ModelPackage` downloads and verifies the on-device models; `SpeechInput` records with Orukeet (`LocalSpeechSession`, `Endpointer`, `OrukeetEngine`) or falls back to the phone's recognizer; `SherpaVoice` plays speech from sherpa-onnx while it's synthesized, with `KokoroVoice` for English and `SupertonicVoice` for Dutch (`DutchText` writes out numbers first, `SupertonicFiles` converts the downloaded voice files); `Speaker` picks one of them or the Android voice per reply
- `ui/`: Compose screens and components. `ui/components/Glass.kt` has the sky, the glass card (`glass`), the outlined and flat surfaces used inside cards (`outlined`, `pane`), window glass with system blur for dialogs, sheets and the overlay, and the shared pieces: segmented control, menu rows and the primary button. The in-app blur comes from the [Haze](https://github.com/chrisbanes/haze) library. `Theme.kt` decides when Reduce transparency applies, `Sky.kt` holds the backgrounds and `Accent.kt` the accent colors.
- `assets/`: the supplied icon, wordmark, app icon and animated reveal as SVG, plus PNG exports of the three static designs. Android uses transparent renders of the mark for the in-app logo and adaptive launcher foreground. Notification and themed launcher icons use a monochrome silhouette. See [Brand assets](branding.md) for regeneration instructions.
