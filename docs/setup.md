# Connect dudan to Hermes

[Back to dudan](../README.md) | [Documentation](README.md)

## Hermes side

dudan needs the Hermes API server. In `~/.hermes/.env` on the Hermes host:

```
API_SERVER_ENABLED=true
API_SERVER_KEY=<at least 16 random characters, e.g. openssl rand -hex 32>
API_SERVER_HOST=0.0.0.0      # or the Tailscale IP, so the phone can reach it
```

Restart the gateway (`hermes gateway restart`) and check `curl http://<host>:8642/health`.

Keep the port inside the tailnet. The API server runs agent turns with terminal access, so anyone holding the key can run commands on the host. Plain `http://` is fine over Tailscale because WireGuard encrypts the traffic; the app warns when an `http://` address points outside the tailnet.

For attachments other than photos, also run the upload service on the Hermes host; [Files](#files) explains why and [upload service guide](../tools/hermes-upload/README.md) has the steps. Chat works without it.

## Phone setup

1. Install the APK. On the phone, download it from the [latest release](https://github.com/MajesteitBart/dudan/releases/latest) and open it. For a build of your own, `node tools/serve-apk.mjs <tailscale-ip> 8787` on the build PC serves everything in `artifacts/`, so the phone can open `http://<tailscale-ip>:8787/` and download it. Taildrop works too.
2. Enter the server URL (`http://my-server:8642`), the API key, your name and the assistant's name, then tap Connect.
3. Make dudan the assistant: Settings > Apps > Choose default apps > Digital assistant app > Device assistance app > dudan. The in-app Settings screen has a shortcut and shows whether it worked.
4. On Samsung, set Settings > Advanced features > Side button > Press and hold to "Digital assistant".
5. For "Ask about screen", enable "Use screenshot" in the same Digital assistant settings.
6. On the first Wi-Fi connection the app downloads its three on-device models: Orukeet for speech input (487 MB download, 672 MB unpacked), Kokoro for English replies (350 MB download, 384 MB unpacked) and Supertonic for Dutch replies (401 MB download, 399 MB installed). Settings > Speech input, English voice and Dutch voice show the progress and have a download button for mobile data. Until Orukeet is in, voice input uses the phone's recognizer.

Choosing dudan as assistant also makes its recognition service the system default. That service forwards to the phone's real recognizer, so voice typing in other apps keeps working.

## Phone control

With Settings > Phone control on, Hermes can act on the phone: open apps and links, set timers and alarms, and play, pause, skip or set the volume of what's playing. That works for every Hermes channel, so "open Spotify on my phone" in Telegram works too. The agent sees these tools:

| Tool | What it does |
| --- | --- |
| `open_app` | Opens an app by name ("Spotify", "maps") or package name. When several apps match, it lists them. |
| `list_apps` | Lists the apps with a launcher icon, optionally filtered by name. |
| `open_link` | Opens a URL or deep link: web pages, `geo:` places, `google.navigation:` directions, `mailto:` and `sms:` drafts, `spotify:` or `whatsapp://` links. `tel:` opens the dialer without calling. `intent:`, `file:` and `content:` links are refused. |
| `set_timer`, `set_alarm` | Sets them in the clock app without opening it. |
| `media` | Play, pause, next, previous, and media volume. |
| `phone_status` | Battery, screen and lock state, ringer and Do Not Disturb, media volume, network, and whether apps can be opened right now. |

The phone runs a small MCP server (Streamable HTTP, `POST /mcp`, port 8643) in a foreground service, with a silent notification while it runs. It only answers connections that arrive on the phone's own Tailscale address, and each request needs a bearer token that the app generates and keeps encrypted with an Android Keystore key. Browser requests, recognized by an `Origin` header, are refused. The service starts again after a reboot or an app update.

Hermes doesn't connect to the phone directly. After a few failed reconnects, Hermes parks an HTTP MCP server, drops its tools and probes again only every five minutes. A phone drops off the tailnet often enough that its tools would keep disappearing. So Hermes starts `tools/hermes-phone-bridge/dudan_phone_bridge.py`, a stdio MCP server (Python 3, standard library only) that forwards each call to the phone. While the phone is away, the bridge answers with a tool error the agent can relay ("The phone isn't reachable…"), and it serves the tool list from a cache. Once the phone is back, the next call goes through.

To set it up, turn on Phone control and tap Copy Hermes setup. The copied text has the token and the phone's Tailscale address filled in. On the Hermes host:

1. Copy `tools/hermes-phone-bridge/dudan_phone_bridge.py` to `~/.hermes/`.
2. Make sure Hermes has its MCP client: `hermes pm install --extra mcp`. Hermes installs that don't include the extra report "requires the 'mcp' Python SDK" when they start any MCP server. Check the gateway's own install: the `hermes` on your PATH can run a different Python than the gateway service.
3. Add `DUDAN_PHONE_TOKEN=<token>` to `~/.hermes/.env`.
4. Add the server to `~/.hermes/config.yaml`:
   ```yaml
   mcp_servers:
     phone:
       command: python3
       args: ["${userHome}/.hermes/dudan_phone_bridge.py"]
       env:
         DUDAN_PHONE_URL: http://<phone's Tailscale IP>:8643/mcp
         DUDAN_PHONE_TOKEN: ${DUDAN_PHONE_TOKEN}
   ```
5. Run `hermes gateway restart`, then `hermes mcp test phone`. The first start needs the phone online to fetch the tool list.

Android only lets an app open other apps while one of its windows is on screen, while it's the default digital assistant, or while it has "Display over other apps". As the assistant, dudan can open apps any time. When it's neither the assistant nor allowed to display over other apps, and no dudan window is on screen, the phone shows a notification that opens the app or link when tapped, and the tool tells the agent so. When the agent opens an app from the side-key overlay, the overlay closes. The reply to a turn that opened an app or link doesn't post a notification, since you're looking at what it opened; replies in other chats still do.

New key in the same section replaces the token; copy the setup again afterwards. For testing in the emulator, debug builds also answer on loopback: `adb forward tcp:18643 tcp:8643`, then send requests to `http://127.0.0.1:18643/mcp` with the token.

## Files

Hermes' API server only takes images, and it rejects any request over 10 MB. Its messaging adapters handle files differently: Telegram's adapter saves a document under `~/.hermes/cache/` and adds a note to the user's turn, such as `[The user sent a document: 'offer.pdf'. It is saved at: /home/…/offer.pdf. …]`. The agent then extracts the text, transcribes the audio or runs a video tool itself.

dudan does the same with a service of its own, `tools/hermes-upload/dudan_upload.py` (Python 3, standard library only). It runs on the Hermes host on port 8645 and accepts the Hermes API key, so the app needs no new secret. The app uploads a file in 8 MB chunks as soon as you pick it, two files at a time. After a dropped connection it asks the service how far it got and continues from there, and it checks the SHA-256 when the upload completes. The service writes each chunk to disk before it confirms it, so a crash or power cut on the host loses nothing the app was told had arrived. Files land in `~/.hermes/uploads/dudan/` and are deleted after 30 days; unfinished uploads nobody touched for 24 hours go sooner.

Removing a file from the prompt bar deletes it from the host. So does leaving the draft: starting a new chat or opening another one clears the prompt bar and its files. When the host can't be reached, the app keeps trying to delete for up to an hour, and the service's own cleanup removes whatever is left.

Photos skip the service. The app shrinks them and sends them inside the turn, where Hermes' 10 MB request limit applies, so the photos in one message may take 5 MB together after shrinking. With more, the app asks you to remove some.

The turn itself is plain text: your words, then one note per file in Hermes' own wording. That also lets file turns use the Runs API, so they survive a dropped connection. The chat history turns the notes back into file chips.

Settings > Replies and files has the service's address. Left empty, the app uses port 8645 on the Hermes host, but only when the server URL starts with `http://`. With an `https://` server, enter the address yourself: the service speaks plain HTTP, and guessing would send the API key unencrypted to a host that may be outside the tailnet. Like the server URL, the field warns when an `http://` address points outside the tailnet. Check the upload service tests the address and the key, and says so when something else answers there, such as Hermes itself.

