# dudan for Windows

[Back to dudan](../README.md) | [Documentation](README.md)

dudan for Windows brings the same Hermes chats to a PC. It talks to Hermes the way the phone does, so a chat started on one shows up on the other. Where the phone has the side button, the PC has a shortcut: Ctrl+Alt+Space, or keys of your own, opens a quick question over whatever you're doing.

## What it does

- The chat list sits beside the chat and works like T3 Code's sidebar. Search, sort (last activity, date started or title) and New chat are at the top. Open chats come first, and three groups fold along the bottom: Working holds chats with a turn or background work running in this app, Snoozed holds chats put away until a time, and Settled holds chats you're done with. A settled chat comes back when something new arrives in it, and a snoozed one comes back unread when its time is up. Chats with something new since you last looked are unread: a dot and a heavier title. Each row shows how long ago the chat moved ("5m", "4h", "3d") and its first question.
- A chat's menu, from a right click on its row or from the chat's ⋯, pins, marks read or unread, snoozes (an hour, 3 hours, tomorrow or next week, at 9:00), settles or reopens, renames and deletes. Snoozed, settled and read state stay on this PC, in `chats.json` beside the settings; pins and titles live on Hermes, so the phone shows those.
- Replies stream in with the agent's steps, and approval requests show Hermes' choices, the same cards as on the phone.
- Rich replies: tables, charts, steps, tabs, forms and follow-ups are drawn as on the phone, from the same [OpenUI](reference.md#rich-replies) blocks. Buttons, follow-ups and form submits send the next message, and links open in Windows' own apps. Settings > Replies turns them off.
- With a Clark server that has an [Agent screen](agent-screen.md), the chat shows the task's desktop as a card, live. Open shows it in a window of its own, beside the chat. Take control pauses the agent: click on the screen and your keyboard, mouse and wheel work on the agent's desktop, and a field takes longer text to paste. Return control hands it back. The card stops polling while dudan sits in the tray. An open Agent screen window keeps watching, also with the main window in the tray, until it's minimized: then it lets go of control, as a phone does when the viewer leaves the screen.
- The model picker in the prompt bar has the same two defaults: one for chats started in the window, and a quick one for quick questions.
- `$` lists Hermes' skills, as on the phone.
- Files the agent returns show under its answer, as on the phone. Save file asks where in Windows' own dialog, checks the file's size and SHA-256, and Show in folder points Explorer at it; nothing the agent made is opened or run.
- Pictures go to the model as images. Attach them with +, drag them onto the window, or paste one with Ctrl+V, a screenshot from Win+Shift+S included. + > Screenshot hides dudan for a moment and attaches the screen behind it.
- Ctrl+Alt+Space opens a quick question at the top of the screen: a small card that starts a new chat on the quick model each time. Ask about screen attaches the screen as it was when the card opened. The capture stays in memory and is dropped when the card closes. Esc or a click elsewhere puts the card away; the answer goes on, and Open in dudan continues the chat in the main window.
- The first start asks for the server and key on one card, with a link to the Hermes side of the setup.
- Closing the window keeps dudan in the tray, where the shortcut still works. When a reply arrives while you're elsewhere, a notification says so, and clicking it opens that chat. Quit is in the tray icon's menu.
- Read aloud uses the phone's voices, running on the PC: Kokoro for English and Supertonic 3 for Dutch and 30 more languages, each a one-time download (350 and 401 MB) from Settings > Read aloud. dudan guesses each reply's language and picks the voice; Windows' language settles a reply that doesn't tell. The speaker under a reply reads it, Read new replies aloud reads every answer as it comes in, and Esc stops reading once nothing else is open. Clicking the speaker before the voice is in offers the download and reads the reply when it's done.
- Keyboard shortcuts are listed in Settings, and a click on one records new keys. The defaults: Ctrl+Alt+Space a quick question (everywhere in Windows), Ctrl+N a new chat, Ctrl+K search, Ctrl+B the chat list, Alt+Up and Alt+Down the previous and next chat, Ctrl+E settle or reopen, Ctrl+Shift+U read the latest reply or stop, Ctrl+= and Ctrl+- the text size and Ctrl+0 back to 100%, Ctrl+, Settings. Keys a text field needs (Ctrl+C, Ctrl+V, Ctrl+Z and the like) are refused, and so are letters without Ctrl or Alt.
- Text size runs from 85% to 150%, for every dudan window. Six skies, eight accent colors and Reduce transparency, which also switches on with a Windows contrast theme. The title bar takes the sky's color on Windows 11.
- English and Dutch, following Windows' language.

## Install and connect

The installer is `dudan-<version>.msi` (see [Build](#build)). It installs for the current user only, so it needs no administrator rights, and adds dudan to the Start menu. The build isn't code-signed yet, so Windows SmartScreen asks before the first start.

The PC needs to reach Hermes the way the phone does: on the tailnet, with the server's MagicDNS name or Tailscale IP. Enter the server URL, the API key, your name and the assistant's name, then Connect. [Connect dudan to Hermes](setup.md#hermes-side) has the Hermes side.

Settings live in `%APPDATA%\dudan\settings.json`. The API key in it is encrypted with Windows' DPAPI, so only your Windows account can read it back. Set `DUDAN_HOME` to another folder to keep a second profile, for instance for testing.

When another app already holds the quick question's keys, Settings > Keyboard shortcuts says so; pick other keys there, or ask from the tray icon's menu. Backspace while recording removes a shortcut. The voices go to `%LOCALAPPDATA%\dudan-voices`, outside the installed app and the roaming profile.

## Not on Windows yet

- Voice input: no dictation, Live mode or wake word. Replies are read aloud, see above.
- Sending files other than pictures, and previews of pictures the agent returns.
- The Search, Skills and Scheduled tasks screens.
- Phone control has no counterpart: turns from the PC never mention phone tools.

## Build

dudan for Windows is the `:desktop` Gradle module, a Compose Multiplatform app on the JVM. It shares `:core` with the Android app: the Hermes client, the chat engine, OpenUI parsing and the schedule logic. Its screens are the Android app's, ported, and its strings and fonts are the Android app's files, copied in at build time.

```
./gradlew :desktop:run                       # run from source
./gradlew :desktop:packageMsi                # desktop/build/compose/binaries/main/msi/dudan-<version>.msi
./gradlew :desktop:test                      # unit tests
```

`packageMsi` bundles a Java runtime and downloads the WiX toolset the first time. The build fetches sherpa-onnx's JVM and Windows jars into `desktop/libs` once, checked against their SHA-256. The version is `packageVersion` in `desktop/build.gradle.kts`.

### Publish a release

Windows releases live on GitHub beside the phone's, with the tag `windows-v<version>`, the title `dudan for Windows v<version>`, and two files: `dudan-<version>.msi` and `SHA256SUMS`. Raise `packageVersion` and merge that to `main`; Windows Installer only replaces an installed dudan with a higher version. Then, on `main`, run the tests, `./gradlew :desktop:packageMsi`, and publish the MSI with its checksum as a pre-release.

To develop without a live agent, run the mock server in English, with its Agent screen, and point a separate profile at it. A `DUDAN_HOME` profile runs beside an installed dudan instead of handing over to it, but another dudan holding Ctrl+Alt+Space keeps that shortcut, so Settings says it's taken:

```
MOCK_LANG=en MOCK_DESKTOP=1 node tools/mock-hermes/server.mjs 8650
DUDAN_HOME=/tmp/dudan-dev ./gradlew :desktop:run       # connect to http://127.0.0.1:8650, key dev-key-dudan-0000000000
```

`DesktopJourneyTest` walks through a first session against that mock: setup, a question, an approval, the model picker, an older chat, the accent, a rich reply and its follow-up, the Agent screen card, taking and returning control in the Agent screen, and a quick question. It draws the real screens off screen and saves a picture of each step in `desktop/build/ui-shots`. It runs only when given the mock's address:

```
./gradlew :desktop:test -Pdudan.mock=http://127.0.0.1:8650
```

`chatListShortcutsAndTextSize` walks through the chat list (settle and snooze from a chat's menu, the groups, search), records a shortcut and has one refused, and changes the text size from Settings and from the keyboard, through the main window's own key handler.

It can't reach what Windows itself does: the tray, the global shortcut, the quick question's and the Agent screen's own windows, keys and the wheel on the agent's desktop, drag and drop, the file dialog and the sound output. Check those by hand after changing them.

`VoiceLiveTest` downloads both voices into a folder (about 750 MB the first time; a folder that has them is reused) and reads an English and a Dutch sentence through the PC's sound output:

```
./gradlew :desktop:test --tests '*VoiceLiveTest*' -Pdudan.voices=/tmp/dudan-dev/voices
```

`LiveServerTest` checks a real Hermes or Clark the same way, through a profile folder that is already set up: it loads the chat list, asks one short question on the quick model, and deletes that chat again. The key stays encrypted in the profile. It runs a real agent turn, so it only runs when given the folder:

```
./gradlew :desktop:test --tests '*LiveServerTest*' -Pdudan.live=%APPDATA%\dudan
```

## Code map

- `core/`: the Android-free code both apps use. `chat/`, `openui/`, most of `data/` and the voice helpers that don't touch audio (`SpeechLanguage`, `SpeechText`, `WakePhrase`, `Endpointer`, `LanguageGuess`, `DutchText`, `SupertonicFiles`, `AudioQueue`, `VoiceSamples`). `VoiceModels` has the Kokoro and Supertonic downloads, voices and settings, so both apps fetch the same files and sound the same. `ChatEngine` takes the app's `TurnOrigin.Device`, so each turn tells Hermes whether the user is on their phone or their PC, and its `activity` says which chats it's working in.
- `desktop/.../desktop/`: what's Windows-specific. `Main` sets up the windows, the global shortcut and the window's keys. `DesktopTray` puts dudan in the notification area and draws its menu itself, since Windows draws an AWT tray menu in the style of Windows 95. `GlobalShortcut` registers the quick question's keys with Win32 `RegisterHotKey`, and `QuickAsk` is the quick question. `Shortcuts` has the actions, their default keys and what may not be a shortcut. `DesktopSettings` keeps the settings file, `ChatMarks` the chat list's read, snoozed and settled state, and `ChatGroups` sorts the list into its groups. `DesktopIo` has the screen capture, the image file dialog, clipboard pictures and the single-instance guard, and `WindowChrome` colors the title bar.
- `desktop/.../voice/`: reading aloud. `ModelStore` downloads and checks a voice, `SherpaSpeech` runs Kokoro or Supertonic through sherpa-onnx and plays it with Java Sound while it's being made, and `DesktopSpeaker` picks the voice per reply.
- `desktop/.../ui/`: the Android screens, ported, apart from the first start (`SetupScreen` in `settings/Connection`), which is made for a window. `MainViewModel` mirrors the phone's, so the ported screens read the same. `components/Overlay` shows sheets and dialogs as layers inside the window, where the phone uses windows of their own. `openui/` draws rich replies, `chat/AgentScreen` and `DesktopController` the Agent screen, and `data/DesktopKeys` turns the PC's keys into Agent screen input. `LocalWindowShown` stands in for the phone's lifecycle: what only matters while someone looks waits for it.
