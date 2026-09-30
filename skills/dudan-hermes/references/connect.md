# Connect dudan

Use the checkout's `docs/setup.md` and `tools/hermes-upload/README.md` for current details. Run host commands as the user running Hermes. Examples below assume its data root is `~/.hermes`.

## Chat

Connect the phone and host to Tailscale. Merge these settings into the active Hermes `.env`:

```dotenv
API_SERVER_ENABLED=true
API_SERVER_KEY=<existing key or at least 16 random characters>
API_SERVER_HOST=<Hermes host's Tailscale IP>
```

For a new key, `openssl rand -hex 32` is one option; write it privately into the env file. Prefer binding to the host's Tailscale IP. The repo also documents `0.0.0.0`, which requires network controls to keep the API private. Keep the phone's API URL reachable through the tailnet; `localhost` on the phone means the phone itself.

Restart the existing gateway with `hermes gateway restart`. Check `http://<host>:8642/health`, then enter `http://<host>:8642`, the API key and the chosen names in dudan and tap Connect. A health response establishes reachability, not working provider authentication; send a short message and receive a reply. HTTP inside Tailscale is encrypted by WireGuard.

## Files other than photos

The upload service needs Python 3.10+ and no Python packages. Copy `tools/hermes-upload/dudan_upload.py` and `dudan-upload.service` from the checkout to the Hermes host. On a Linux host with user systemd, after staging them in the user's home:

```sh
install -m 700 ~/dudan_upload.py ~/.hermes/dudan_upload.py
mkdir -p -m 700 ~/.hermes/uploads
mkdir -p ~/.config/systemd/user/dudan-upload.service.d
install -m 644 ~/dudan-upload.service ~/.config/systemd/user/dudan-upload.service
```

Merge a drop-in at `~/.config/systemd/user/dudan-upload.service.d/override.conf`:

```ini
[Service]
Environment=DUDAN_UPLOAD_HOST=<Hermes host's Tailscale IP>
```

Preserve other overrides. Then:

```sh
systemctl --user daemon-reload
systemctl --user enable --now dudan-upload
systemctl --user status dudan-upload
journalctl --user -u dudan-upload -n 20
loginctl show-user "$USER" -p Linger
```

If it must survive logout and lingering is off, enable it with `loginctl enable-linger "$USER"` using the host's required privileges. An existing running unit needs `systemctl --user restart dudan-upload` after script/config/key changes.

Check `GET http://<host>:8645/health` with `Authorization: Bearer <API_SERVER_KEY>` through an HTTP client that keeps the key out of output. Expect JSON with `ok: true`, `version: 1` and chunk/size limits. In dudan, Settings > Replies and files sets the upload URL. Empty means port 8645 on an HTTP Hermes host; with HTTPS, configure the upload URL explicitly. Upload a disposable text file and ask Hermes to read its contents.

For another OS or custom Hermes home, adapt service paths and persistence to that environment; the supplied systemd unit is Linux-specific. Keep its writable paths aligned with `DUDAN_UPLOAD_DIR`. Files expire after 30 days by default; preserve legacy upload paths during upgrades. The repository guide covers resumable uploads, checksum checks and systemd sandbox failures.

## Phone control

1. On the phone, enable Settings > Phone control and tap Copy Hermes setup. Use that snippet's actual address and token privately.
2. Copy `tools/hermes-phone-bridge/dudan_phone_bridge.py` to the active Hermes data directory. Run `hermes pm install --extra mcp` for the installation used by the gateway, if its MCP client is missing.
3. Merge `DUDAN_PHONE_TOKEN=<phone token>` into Hermes' `.env`. Merge this server under the existing `mcp_servers` map in `config.yaml`:

   ```yaml
   mcp_servers:
     phone:
       command: python3
       args: ["/absolute/path/to/hermes-home/dudan_phone_bridge.py"]
       env:
         DUDAN_PHONE_URL: http://<phone's Tailscale IP>:8643/mcp
         DUDAN_PHONE_TOKEN: ${DUDAN_PHONE_TOKEN}
   ```

   Use a Python executable available to the gateway; native Windows may require its absolute executable path. This bridge needs only the standard library.
4. Keep the phone online for the first tool-list fetch, restart the gateway, and run `hermes mcp test phone`. Ask Hermes for `phone_status` to verify forwarding. Use a harmless action such as listing apps before testing app launches.

Use the stdio bridge: it caches tools when the phone disconnects so Hermes doesn't drop them. A rotated phone token requires updating the host's env and restarting. The phone token is separate from the Hermes API key.

## Phone settings and diagnosis

Select dudan in Android's Digital assistant settings. On Samsung, set Side button > Press and hold to Digital assistant. Enable Use screenshot for Ask about screen. Let speech models download on Wi-Fi; text chat can work before those downloads finish.

| Symptom | Check |
| --- | --- |
| Chat timeout | Both Tailscale connections, host bind address, tailnet access rules/firewall and port 8642 |
| Chat auth failure | App key versus the active gateway's `API_SERVER_KEY` |
| Health works, chat fails | Provider setup and gateway logs; test a direct Hermes turn |
| Upload 401 | Same key as chat; restart upload service after key changes |
| Upload check finds Hermes | Wrong port; upload service is 8645 |
| MCP SDK missing | Install extra into the gateway's own Hermes installation |
| Phone tools unavailable | Phone control enabled, phone online for first fetch, bridge path/interpreter/token |
| App launch returns notification | Default assistant role or Display over other apps permission; Android restricts background launches |

Test from the phone or an equivalent tailnet peer; loopback-only checks don't establish phone connectivity.
