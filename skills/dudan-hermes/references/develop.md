# Edit, build and install dudan

Read `docs/development.md`, `docs/upgrading.md` and `app/build.gradle.kts` in the checkout. Current baseline: JDK 17, Android SDK platform 36, minimum Android API 31 (Android 12). Configure `ANDROID_HOME` or a git-ignored `local.properties` with `sdk.dir`. Use the repository Gradle wrapper. The first build downloads a pinned sherpa-onnx AAR and checks its SHA-256; preserve that check.

## Choose the files

Under `app/src/main/java/nl/bartvandermeeren/dudan/`:

| Change | Area |
| --- | --- |
| API compatibility, SSE, attachments, secrets | `data/HermesApi.kt`, `SseReader.kt`, `UploadClient.kt`, `Attachments.kt`, settings |
| Streaming state, reconnects and history | `chat/` |
| Assistant overlay and system recognition | `assist/` |
| Phone tools and MCP server | `device/`, plus `tools/hermes-phone-bridge/` |
| Speech and model downloads | `voice/` |
| Rich replies and renderer | `openui/`, `ui/openui/` |
| Screens and visual components | `ui/` |

Upload behavior lives in `tools/hermes-upload/`. Preserve its chunk offsets, durable acknowledgements, checksum verification and auth checks. Run its suite for service changes:

```sh
python -m unittest discover -s tools/hermes-upload -p 'test_*.py'
```

## Build and verify

POSIX:

```sh
./gradlew testDebugUnitTest assembleDebug
```

PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Debug output is `app/build/outputs/apk/debug/app-debug.apk`; package `nl.bartvandermeeren.aight.debug`, with arm64 and x86_64 libraries. Use debug on an x86_64 emulator: release is arm64-only and its speech runtime can crash under ARM translation.

For UI verification, use the environment's shared device tools when available, or select the intended target with `adb devices -l`. Install without clearing data:

```sh
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `tools/mock-hermes/server.mjs` for local development. Its arguments select the API and upload ports, for example `8650 8645`. It currently binds both listeners to `0.0.0.0`; restrict the binding to localhost for a local-only workflow before starting it, or follow the environment's permitted private-network arrangement. Use the prescribed persistent-server manager for a server left running for the user.

In the Android emulator connect to `http://10.0.2.2:8650`, key `dev-key-dudan-0000000000`. The upload service uses 8645. Verify HTTP responses after startup. The mock tests scripted protocol behavior; it cannot prove a real Hermes provider or the phone's tailnet connection works. For phone MCP debug, `adb -s <serial> forward tcp:18643 tcp:8643` exposes `http://127.0.0.1:18643/mcp`; it still needs the phone token.

## Release builds and updates

`assembleRelease` produces `app/build/outputs/apk/release/app-release.apk`. Inspect signing first: without `signing.properties`, this repo falls back to the debug key. Keep existing `signing.properties`, keystore alias, passwords and certificate; never generate a replacement key for an update. A signature mismatch needs the correct APK/key, not an automatic uninstall that erases user data.

For the requested release workflow, bump `versionName` and `versionCode` deliberately, then use `node tools/release-apk.mjs` on a clean checkout with production signing configured. It runs tests, builds, rejects debug signing and prepares the APK, `SHA256SUMS` and `notes.md` in `artifacts/android-v<version>/`. Its output contains the publish command. Publish only when requested. Do not stash unrelated user work just to satisfy its clean-tree check.

For a real arm64 phone, install a correctly signed APK with `adb -s <serial> install -r <apk>`, or use the repository's GitHub release and verify its checksum. Taildrop is another transfer option. `tools/serve-apk.mjs <tailscale-ip> 8787` serves `artifacts/` if private HTTP delivery is needed; manage its lifetime with the host's server manager and check HTTP before sharing its address.

Upgrades retain `nl.bartvandermeeren.aight` and legacy assistant entry points. For old aight host integrations, consult `docs/upgrading.md`: preserve credentials and upload paths, migrate service names/drop-ins, and run only one upload service on 8645. Replace the bridge script/path while retaining supported legacy variables and cache where needed.
