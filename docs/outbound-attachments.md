# Files returned by Clark

[Back to dudan](../README.md) | [Documentation](README.md)

Clark's `attach_file` tool returns images and other files with an answer. dudan shows them under the answer, from metadata the server sends with it, never from links in the answer's text. Images show at their own aspect ratio. Every file has Save file, which opens Android's document picker. dudan never opens or installs a file, also not an APK.

This needs a Clark server with outbound attachments. Hermes doesn't send the metadata, so with Hermes nothing changes.

## Protocol, version 1

`GET /v1/capabilities` advertises `features.outbound_attachments: {"version":1,"authenticated":true}`. dudan doesn't wait for the capability: it reads the metadata wherever it comes, checks each entry's version and skips entries it doesn't understand.

The `attachments` array comes with:

- `GET /v1/runs/{run_id}`, the run status dudan polls when a stream drops;
- `run.completed` on the run event stream;
- `assistant.completed` and `run.completed` on the session chat stream, which turns with photos use;
- the answer's assistant rows in `GET /api/sessions/{session_id}/messages`. Clark can list the files on any assistant row of the answer, also on the one that called `attach_file`; dudan shows each file once, with the reply that follows.
- a scheduled task's result in the same transcript, a row with `display_kind: scheduled_result`. Its files have input ids like `result-41` and show with that result only, never with a turn's answer.

A missing array means no files. An entry looks like this (synthetic metadata):

```json
{"version":1,"session_id":"example-chat","input_id":"i_example","index":0,"name":"screen.png","mime_type":"image/png","size":12,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
```

dudan skips an entry when `version` isn't 1; `session_id` is empty, longer than 256 characters, `.` or `..`, or holds `/`, `\` or a control character; `input_id` isn't 1 to 100 letters, digits, `_` or `-`; `index` isn't 0 to 99999; `size` isn't 0 to 100 MiB; or `sha256` isn't 64 lowercase hex digits. In `name` it replaces path separators, control and bidi characters and `<>:"|?*` with `_`, strips leading dots and keeps 180 characters; an empty name becomes `attachment`. A `mime_type` that isn't a plain `type/subtype` becomes `application/octet-stream`. An entry listed twice shows once, and a file whose `session_id` isn't the open chat's doesn't show.

## Download

dudan fetches `GET /api/sessions/{session_id}/attachments/{input_id}/{index}` on the configured server, each id its own encoded path segment, with the API key in the `Authorization: Bearer` header like every other request. The metadata can't choose a host, path or query, and redirects aren't followed. Clark checks the key and that the input belongs to the chat on every request: a wrong key gets 401, a wrong chat or attachment 404, and bytes that are gone 410. The key never goes in a URL, the metadata, an error message or a log.

The response streams into a private temporary file in the app's cache, under `cache/attachments`. The download stops as soon as more bytes arrive than `size` says or Content-Length disagrees with it, and at the end the exact size and SHA-256 must match. Only a verified file is decoded or saved. The temporary file is deleted after use and on any failure or cancel; the first download after a restart also clears files a killed process left there. A download may take up to 10 minutes, so a 100 MiB file needs about 170 KB/s.

## Showing and saving

PNG, JPEG, WebP and GIF files up to 16 MiB get a preview; a GIF shows its first frame. A preview is decoded at no more than 1,600 pixels on its longest side, one image at a time. Before decoding, dudan reads the image's size and header and refuses an image over 16,384 pixels on a side or over 100 megapixels in total. Some decoders hold the whole image in memory, whatever size the preview is: those for interlaced PNG, progressive JPEG and JPEG with more than one scan, WebP other than simple lossy, and GIF. For those, and for anything it doesn't recognise, the limit is 24 megapixels. An image that is refused, or that Android can't decode, shows as a card like other files and isn't downloaded again for a preview while the app runs. Recent previews stay in memory, up to 24 MB, so scrolling back doesn't download them again. Larger images and other files show as a card with their name and size.

Save file opens the document picker with the file's name and type, then downloads and verifies the file again and writes it to the picked place. When that fails, dudan deletes the picked file if it is empty, which a new file from the picker is, or if writing to it had started, so no empty or partial file stays behind. When you chose to overwrite an existing file, the picker returns that file, and a failed download or check leaves it as it was. A save keeps going when its row scrolls away and stops when the chat closes. Its progress and result live with the chat, not the row, so a row that comes back shows them, and Save file stays off while the save runs. A preview stops downloading when its row scrolls away. A 404 or 410 shows that the file is no longer available. Other errors ask to try again; a failed preview has a Retry button.

When an answer with only files arrives while dudan isn't on screen, its notification names the files.

The side-key overlay doesn't show an answer's files, since the document picker needs an activity, and it doesn't read an answer with only files aloud. Open the chat in the app to see them.

## Retention and rollback

Clark stores the metadata with the answer, so it survives a restart and comes back with the history when you reopen a chat. The bytes follow Clark's artifact retention, so an older file can become unavailable. dudan keeps no copy besides the temporary file and the preview in memory.

The feature is additive. A server without it sends no `attachments`, and an older dudan ignores the field and shows the answer's text.

## Tests

`OutboundAttachmentsTest` covers the documented entry, file-only answers in the history, one copy per answer across rows and repeated terminal events, a live file-only answer the transcript doesn't hold yet and its id once it does, which picked file a failed save deletes, the pixel limit per image format, old servers, unsafe metadata, the encoded route with the bearer header, size and digest mismatches, 410, refused redirects and run status. `ChatEngineTest` checks that an answer with only files completes its turn for the reply notification. Clark tests its side in `test/channels/dudan/attachments.test.ts`.
