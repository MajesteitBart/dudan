# Agent screen protocol

Agent screen lets you watch a dedicated desktop in the conversation that owns it. Open expands the preview. Take control pauses agent input and grants this viewer a short control lease. Return control explicitly hands the desktop back after the server captures its current state. Collapsing the viewer keeps the task running; if you held control, the agent stays paused until you return it.

This requires a server that advertises desktop protocol version 1 and `png-poll`. Clark implements it through an optional Cua adapter. Existing Hermes servers continue to work with desktop controls hidden. No Hermes backend is added by this feature. Recording and teaching are not offered.

## Discovery and trusted data

`GET /v1/capabilities` advertises:

```json
{"features":{"desktop":{"version":1,"supported":true,"transports":["png-poll"],"control":true,"recording":false,"teaching":false}}}
```

Absence, `supported:false`, an unknown version or an unsupported transport hides the feature. The native app reads session metadata from the authenticated server. It never creates a desktop card from assistant text, OpenUI or an arbitrary media URL. All requests use fixed paths on the configured server and its existing bearer credential. Media requests disable redirects; input requests also disable connection retries.

The shared fixture is [`app/src/test/resources/desktop-v1.json`](../app/src/test/resources/desktop-v1.json), byte-identical to Clark's `fixtures/desktop/v1.json`. Both repositories test it. Times are Unix milliseconds. IDs bind the desktop to its conversation, task, initial run and resource. Epochs identify control generations.

## Routes

All routes below are under `/api/sessions/{session_id}/desktops`. The server must check the authenticated owner and conversation on every route, including frames and reconnects. Browser Origin requests are refused by Clark. Responses use `Cache-Control: no-store`.

| Method and suffix | Request | Response |
| --- | --- | --- |
| `GET /` | None | `{version:1,data:[session]}` |
| `GET /{id}` | None | Session object from the fixture |
| `POST /{id}/grant` | `{}`, `X-Desktop-Viewer` | `{version:1,grant,expires_at,transport:"png-poll",interval_ms:1000}` |
| `GET /{id}/frame` | `X-Desktop-Viewer`, `X-Desktop-Grant` | PNG bytes, `X-Desktop-Frame-At` |
| `POST /{id}/acquire` | `{}`, viewer header | `{version:1,lease,expires_at,epoch,next_sequence:1}` |
| `POST /{id}/renew` | `{lease}`, viewer header | `{expires_at}` |
| `POST /{id}/input` | `{lease,epoch,sequence,input}`, viewer header | `{version:1,accepted:true,epoch}` |
| `POST /{id}/release` | `{lease}`, viewer header | Session; agent remains paused |
| `POST /{id}/handback` | `{epoch,lease?}`, viewer header | Session after reconciliation |
| `POST /{id}/stop` | `{}`, viewer header | Stopped session |

The viewer ID is random for each viewer and lasts only in memory. It has 16–100 letters, digits, underscores or hyphens. It cannot replace authentication. Lease and grant secrets remain in memory and are never placed in URLs, app preferences, chat messages, logs or exports. Frames are decoded in memory and never written to the app's disk cache.

Input is one complete operation: `tap {x,y}`, `drag {x,y,toX,toY}`, `scroll {dx,dy}`, `text {text}` or `key {keys}`. Coordinates are integer desktop pixels. Scroll deltas are integer lines with magnitude at most 20. Text is limited to 4,096 characters and chords to four keys. Sequences start at 1 and must increase by exactly one. A failed input is not automatically retried because its effect may already have happened.

Errors follow the existing `{error:{code,message,type}}` envelope. A 401 rejects the API credential. `desktop_grant_invalid` and `desktop_lease_invalid` are 403. Ownership mismatches return `desktop_not_found` (404). Stale input/epoch, busy control and agent fencing return 409. `desktop_input_uncertain` (503) requires explicit reconciliation; it must never be treated as a successful input or retried blindly.

## Display and lifecycle

The UI represents unavailable, connecting, live/view-only, human-controlled, reconnecting, stopped and error. `agent_paused` is separate from connection state. A retained image is marked as the last frame during disconnect and includes its local reception time. Unknown server states map to error.

A desktop shows in its conversation only while it works; a stopped one leaves it. Its card is one row by default: a small thumbnail once a frame has arrived, Agent screen, the state, and Open. Tapping the row shows the preview in place, at the desktop's aspect ratio, at most 600 dp wide and 360 dp tall, or 120 dp on a phone in landscape; tapping again folds it back. Open goes full screen, where the desktop is centred at the same ratio. In the moments before the session list catches up with a stop, the row reads Stopped with the time of its last frame.

The expanded viewer polls frames at one-second intervals after each response. Compact visible cards poll every ten seconds. Metadata discovery uses five seconds while the conversation is foregrounded. Offscreen cards stop polling. Backgrounding, leaving the screen or collapsing cancels the viewer loop and attempts release. If release cannot reach the server, its lease expires; the agent must still remain paused. Reopening starts view-only. The controller renews a human lease only while its foreground viewer is active.

Images use aspect-fit with letterboxing. Touches in the letterbox are ignored. Taps and drag endpoints map from the rendered surface to the full desktop dimensions. The viewer provides text input, hardware keyboard input, key buttons and scroll buttons. Buttons have a minimum 48dp target. Back dismisses the expanded dialog; it does not stop the task. Rotation/folding can recreate the viewer; control is released and must be acquired again. The landscape control panel scrolls so it does not consume the whole desktop area.

## Build and validation

Run `gradlew testDebugUnitTest` and `gradlew assembleDebug` for protocol and coordinate tests and an emulator APK. Build the signed candidate with `gradlew assembleRelease` using the existing ignored signing configuration. Do not run the release-publishing script against a dirty checkout: its commit-based filename would be misleading. Record the candidate's diff, APK hash and signing certificate separately, and compare the certificate to an existing release APK. Never change keys or install on a physical phone as part of this build.

Real acceptance uses an actual Cua desktop and the app, with changing frames, input on the same desktop, agent-input fencing, hand-back, reconnect and unauthorized-viewer refusal. A scripted model demonstration must be labeled as such; it is not proof of a real model provider. Physical Fold7 layout and input remain a separate device check when only an emulator is available.

## Design and dependencies

The compact rounded preview, visible Open action, expanded title bar and Collapse interaction follow component 21 on [Beautiful UI](https://www.beautifului.dev/#agent-screen). The site has an [MIT license](https://www.beautifului.dev/license), copyright 2026 Shane Levine. This is an independent Kotlin/Compose implementation using dudan's existing theme. No React source, demo desktop, decorative cursor or site asset is bundled.

Cua remains an external server-side dependency. Its MIT SDK/CLI/driver and FSL-1.1-MIT Spaces apps/services have different terms; see [Cua's commercial licensing](https://github.com/trycua/cua/blob/main/legal/COMMERCIAL.md). The app contains no Spaces code or viewer. It decodes authenticated PNGs with Android's existing image APIs.
