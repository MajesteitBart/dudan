# aight upload service

Hermes' API server takes images only and refuses request bodies over 10 MB. For other files, aight
uses this small service on the Hermes host. The phone uploads the file in chunks and gets back the
file's absolute path on the host. It then puts that path in a text note in the chat turn, and the
agent reads the file with its own tools. Hermes' Telegram and other messaging adapters handle files
the same way.

Uploads resume after a dropped connection, which matters for a 250 MB video over mobile data. The
service is one Python file with no dependencies. It checks requests against Hermes' own API key, so
the app needs no new secret.

## Setup on the Hermes host

You need Python 3.10 or newer at `/usr/bin/python3` and a user systemd instance, which any recent
Linux distribution has.

From a checkout of this repository, copy the script and the unit to the host:

```sh
scp tools/hermes-upload/aight_upload.py tools/hermes-upload/aight-upload.service <user>@<hermes-host>:~/
```

On the host, as the user that runs Hermes:

```sh
install -m 700 ~/aight_upload.py ~/.hermes/aight_upload.py
mkdir -p -m 700 ~/.hermes/uploads
mkdir -p ~/.config/systemd/user/aight-upload.service.d
install -m 644 ~/aight-upload.service ~/.config/systemd/user/aight-upload.service
rm ~/aight_upload.py ~/aight-upload.service

# Listen on the Tailscale address only.
printf '[Service]\nEnvironment=AIGHT_UPLOAD_HOST=%s\n' "$(tailscale ip -4)" \
  > ~/.config/systemd/user/aight-upload.service.d/override.conf

systemctl --user daemon-reload && systemctl --user enable --now aight-upload
```

A user service stops when you log out, and it doesn't start at boot until you log in. To keep it
running, enable lingering once:

```sh
loginctl enable-linger "$USER"
```

`loginctl show-user "$USER" -p Linger` shows whether it is already on.

Check that it works:

```sh
systemctl --user status aight-upload
journalctl --user -u aight-upload -n 20

KEY=$(grep -E '^(export )?API_SERVER_KEY=' ~/.hermes/.env | tail -n 1 | cut -d= -f2- | tr -d "\"'")
HOST=$(tailscale ip -4)
curl -s -H "Authorization: Bearer $KEY" "http://$HOST:8645/health"
# {"ok": true, "version": 1, "max_bytes": 262144000, "chunk_bytes": 8388608}
```

An upload by hand, to see the whole flow:

```sh
printf 'hello\n' > /tmp/hello.txt
curl -s -H "Authorization: Bearer $KEY" -d '{"name": "hello.txt", "size": 6}' "http://$HOST:8645/uploads"
# {"id": "<id>", "offset": 0, "size": 6, "chunk_bytes": 8388608}
curl -s -X PUT -H "Authorization: Bearer $KEY" --data-binary @/tmp/hello.txt "http://$HOST:8645/uploads/<id>?offset=0"
# {"offset": 6, "complete": true, "path": "/home/<user>/.hermes/uploads/aight/<id>_hello.txt", ...}
curl -s -X DELETE -H "Authorization: Bearer $KEY" "http://$HOST:8645/uploads/<id>"
```

The app talks to the service at `http://<the host's Tailscale IP>:8645`.

To update the service later, copy the new `aight_upload.py` over the old one and run
`systemctl --user restart aight-upload`.

## Settings

The service reads environment variables. Put them in the drop-in file from the setup
(`~/.config/systemd/user/aight-upload.service.d/override.conf`) as `Environment=NAME=value` lines,
then run `systemctl --user daemon-reload && systemctl --user restart aight-upload`.

| Variable | Default | Meaning |
| --- | --- | --- |
| `AIGHT_UPLOAD_HOST` | `0.0.0.0` | Address to listen on. Use the host's Tailscale IP. |
| `AIGHT_UPLOAD_PORT` | `8645` | Port to listen on. |
| `AIGHT_UPLOAD_DIR` | `~/.hermes/uploads/aight` | Where finished files go. |
| `AIGHT_UPLOAD_MAX_BYTES` | `262144000` (250 MiB) | Largest file accepted. |
| `AIGHT_UPLOAD_CHUNK_BYTES` | `8388608` (8 MiB) | Chunk size the service suggests to the app. At most 16 MiB. |
| `AIGHT_UPLOAD_RETENTION_DAYS` | `30` | Days to keep finished files. `0` keeps them forever. |

The service uses `API_SERVER_KEY` from its environment or the `API_SERVER_KEY=` line in
`~/.hermes/.env`. It won't start without a key, or with one
shorter than 16 characters. It reads the key once at startup, so restart it after you change the
key in Hermes.

`--host`, `--port` and `--dir` on the command line override the matching variables. `--verbose`
logs every request.

If you move `AIGHT_UPLOAD_DIR` out of `~/.hermes/uploads`, change `ReadWritePaths` in the unit to
match. The sandbox doesn't allow writes anywhere else.

## Where files go and how long they stay

A finished upload is saved as `<first 12 characters of the upload id>_<file name>` in the upload
directory, for example `~/.hermes/uploads/aight/3f9c2a7be01d_Quarterly report.pdf`. The id prefix
keeps two files with the same name apart. Uploads in progress live in `.partial/` in the same
directory, next to a small JSON file with each upload's state.

The service prunes at startup and then every hour:

- Finished files older than the retention period (30 days by default) are deleted along with their
  state. Age is measured from the file's modification time.
- Partial uploads nobody touched for 24 hours are deleted.

If the agent needs a file for longer, it should copy it somewhere else.

## Security

- Anyone with the key can already make the agent run shell commands through Hermes' API. The upload
  service accepts the same key, so it gives no one new powers.
- Traffic is plain HTTP. Tailscale encrypts it between the phone and the host, which is why the
  service should listen on the Tailscale IP. The default `0.0.0.0` also listens on every other
  network the host is on, and on a public network the key would travel in the clear.
- Requests with an `Origin` header are refused, so a web page in a browser can't use the service,
  even from inside the tailnet.
- File names from the phone are cleaned before use: directory parts, control characters and
  `<>:"/\|?*` are removed, leading dots are dropped and long names are cut to 120 characters. A
  name like `../../etc/passwd` becomes `passwd` inside the upload directory. The service also
  checks the resolved path before saving.
- The upload directory is `0700` and files are `0600`, so only the Hermes user can read them.
- The systemd unit keeps the service from writing outside `~/.hermes/uploads` and from gaining
  privileges.
- Uploaded files are untrusted input for the agent. A document can contain instructions aimed at
  the model, just like a web page can.
- The service starts a thread per connection. A connection that sends nothing for 120 seconds is
  dropped, so a phone that lost its network doesn't hold a thread for long.

## Troubleshooting

- `status=226/NAMESPACE` in `systemctl --user status`: the kernel doesn't let user services set up
  the sandbox, or `~/.hermes/uploads` doesn't exist. Create the directory first. If that doesn't
  help, comment out the second hardening block in the unit, then run `daemon-reload` and restart.
- The service keeps restarting with "can't listen on ...": the Tailscale address isn't up yet, or
  something else uses the port. It tries again every 5 seconds.
- The service stops with "no API key": check that `~/.hermes/.env` has `API_SERVER_KEY=`. Exit
  status 2 means a configuration problem, so systemd doesn't retry it.
- The app gets 401: the key in aight differs from the one the service read at startup. Restart the
  service after changing the key.

## Protocol

All responses are JSON except the 204 from DELETE. Errors look like
`{"error": {"message": "...", "code": "..."}}`. Every request needs
`Authorization: Bearer <key>`.

| Request | Answer |
| --- | --- |
| `GET /health` | `{"ok": true, "version": 1, "max_bytes", "chunk_bytes"}` |
| `POST /uploads` with `{"name", "size", "mime"?, "sha256"?}` | 201 `{"id", "offset": 0, "size", "chunk_bytes"}` |
| `GET /uploads/<id>` | `{"id", "name", "mime", "size", "offset", "complete"}`, plus `"path"` and `"sha256"` once complete |
| `PUT /uploads/<id>?offset=<n>` with raw bytes | `{"offset", "complete": false}`, or after the last chunk `{"offset", "complete": true, "path", "name", "safe_name", "mime", "size", "sha256"}` |
| `DELETE /uploads/<id>` | 204 |

Error codes: `unauthorized` (401), `browser_refused` (403), `not_found` (404),
`method_not_allowed` (405), `length_required` (411), `too_large` and `chunk_too_large` (413),
`request_too_large` (413, JSON body over 64 KiB), `invalid_request` (400), `offset_mismatch` (409,
with the current `"offset"`), `upload_busy` (409, another request is still writing a chunk, with
the current `"offset"`), `checksum_mismatch` (422, the upload is deleted), `insufficient_storage`
(507), `storage_error` and `internal_error` (500).

A finished upload sits at offset = size. An empty PUT there returns the final answer again, so a
client that lost that answer can still get the path.

A PUT may carry `Expect: 100-continue`. The service then answers a wrong offset, a busy upload or a
bad key before the client sends the chunk.

The mock server in `tools/mock-hermes/server.mjs` speaks the same protocol on port 8645, for testing
the app in the emulator.
