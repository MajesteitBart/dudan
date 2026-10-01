# Behavior and limitations

[Back to dudan](../README.md) | [Documentation](README.md)

## Interface and behavior

- Frosted glass cards on a dusk sky, after Superhuman's panels, with components after [beautifului.dev](https://www.beautifului.dev/). Messages sit straight on the sky, which darkens while a chat is open so the text keeps its contrast; the prompt bar is the only glass card on the chat screen. An empty chat shows a large greeting and the prompt bar. The prompt bar holds the message, the attach menu, the model picker, the mic and the send button. Work steps are tool chips, code blocks have a header and line numbers, and approvals have one filled primary choice. Dialogs, sheets and the side-key overlay blur the screen behind them. The Fold 7 inner screen shows the sidebar as a second card; the cover screen gets a full-screen frosted sheet.
- Settings > Appearance > Background picks the sky: Dusk (the default indigo and lavender), Ocean, Forest, Sunset, Graphite or Midnight. The glass, menus, sheets and the overlay take their tint from it.
- Settings > Appearance > Accent color picks one of eight colors (moon white, lavender, blue, teal, green, amber, coral, pink) for the send button, your chat bubbles, links, switches and the glow in the sky.
- Settings > Appearance > Reduce transparency swaps the glass for solid panels. It also switches on by itself when the phone's color contrast (Accessibility > Color and motion) is set to medium or high.
- Agent turns go through Hermes' Runs API. If the phone loses the connection mid-task, the run keeps going on the server and the app polls until the result is in.
- Background work: when the agent hands a task to a subagent in the background, its turn ends right away and Hermes saves the subagent's result in the chat later, without starting a turn. dudan shows that result as a card from the background work, not as your message, and marks failed and partly failed batches. It doesn't continue on its own, because the agent may still need your confirmation, or you may have stopped or changed the task since. Review result and finish asks the agent to act on the result in a normal turn, with the usual approvals, after checking that no other client continued the chat first. While the app runs, it checks the chat for results every few seconds at first and every 30 seconds after that, for up to an hour, and posts one notification per result when dudan isn't on screen. Reopening a chat or returning to the app also shows what arrived meanwhile.
- A collapsible "Worked for 1m 10s" panel shows tool calls, commentary and reasoning. Dangerous commands surface as an approval card with Hermes' choices (once, this chat, always, deny).
- Voice input with a live waveform, read-aloud per reply, and a hands-free Live mode. Speech is transcribed on the phone by [Orukeet](https://github.com/Oruk-AI/orukeet), Oruk's multilingual fine-tune of NVIDIA Parakeet TDT 0.6B v3, which handles Dutch and English without a language setting. English replies are read by Kokoro-82M and Dutch replies by Supertonic 3, both on the phone. Before Supertonic reads a Dutch reply, the app writes out numbers, times, amounts and dates, because it misreads digits.
- Assistant overlay on the side key: starts listening right away, can attach the current screen ("Ask about screen"), reads spoken questions' answers aloud, and hands off to the full app.
- Model picker backed by Hermes' model inventory. Each default also sets the thinking level (Hermes' own setting, or off up to max) and, for models that support it, fast mode, which asks the provider for priority processing at a higher price. There are two defaults: one for chats started in the app, and a quick one for chats started from the assistant overlay or the assist gesture. A chat keeps the model of the place it started, also when you continue it in the app.
- Files: the attach menu takes photos, videos and any other file up to 250 MB (PDF, Word, spreadsheets, audio, archives), up to eight per message, and other apps can share files to dudan. Photos go to the model as pictures. Other files upload to a small service next to Hermes while you type, and the turn tells the agent where the file is, in the same words Hermes' Telegram and Discord adapters use. The agent then reads, transcribes or inspects it with its own tools. See [Files](setup.md#files).
- Rich replies: when a table, chart, steps, choices or a short form help, the agent adds an [OpenUI](https://www.openui.com) block to its answer, and dudan draws it as native UI in the chat. Buttons, follow-up suggestions and form submits send your next message. See [Rich replies](#rich-replies).
- Search, pin, rename and delete chats; browse Hermes skills; run, pause or resume scheduled tasks.
- Phone control: Hermes can open apps and links, set timers and alarms, and control media on the phone, whichever channel you ask it from. See [Phone control](setup.md#phone-control).
- English and Dutch UI.

## Rich replies

[OpenUI](https://www.openui.com) is an open standard for generative UI: the model writes a compact, line-based language (OpenUI Lang) that names components from a fixed library, and the client renders them. dudan sends Hermes the language rules and the components it draws as extra instructions on each turn (`instructions` on the Runs API, `system_message` on the session stream). Hermes adds them to the system prompt for that turn only and doesn't store them, so Telegram and the CLI never see them. Settings > Replies and files > Rich replies turns this off.

The agent answers in markdown as usual and adds a fenced `openui-lang` block when structure helps:

````
```openui-lang
root = Card([header, table, followUps])
header = CardHeader("Energy contracts", "Fixed, 1 year")
table = Table([Col("Supplier", ["A", "B"]), Col("Per month", ["€142", "€148"], "number")])
followUps = FollowUpBlock([FollowUpItem("Which one is greenest?")])
```
````

`openui/` holds a Kotlin port of the reference parser in `@openuidev/lang-core` (spec v0.5): statements, references in any order, expressions, `$variables`, `@Each` and the data built-ins. Like the reference, it closes unfinished strings and brackets, so a block renders while it streams in. `ui/openui/` draws the OpenUI chat library's components with their positional arguments in the same order: cards, headers, text, callouts, code, images, tables, bar, line, area, pie and stacked charts, steps, tabs, accordions, lists, tags, buttons, follow-ups and forms with validation. Charts take their colors from a fixed palette checked for colorblind separation on dudan's skies; tap one to read its values, or switch it to a table. A block that doesn't parse, or whose components have nothing to draw, shows as code. Components dudan doesn't draw show their text. Once a reply is complete, parts that draw nothing, such as an empty slide, fold or hidden callout, are left out instead of leaving a gap.

Images in blocks share one memory budget: 64 MB of decoded pictures on screen at once, each decoded at no more than 1280 pixels on its longest side (640 in a gallery). An image that doesn't fit the budget, or doesn't load, shows its alt text or link, and tapping it opens the source. Galleries of up to four images sit in a grid; larger ones scroll sideways and let go of the thumbnails that scroll out of view.

When a button or form submit can't send because a reply is still coming in, dudan asks you to wait and skips the steps after it, such as resetting the form.

Copy, share, the reply notification and read-aloud turn each block into the markdown it stands for: a table stays a table, and steps become a numbered list.

## Known limits

- Turns with photos use the session chat stream, because that is where Hermes validates image parts. Those turns stop if the connection drops mid-run. Turns with other files are text and use the Runs API.
- Files other than photos need the upload service on the Hermes host. The model never sees them directly: the agent opens them with its tools, so what it can do with a video or a spreadsheet depends on the tools and skills Hermes has.
- Background results show up live, with a notification, only while the app process runs. Hermes has no push channel for them, so when Android has stopped dudan or keeps a sleeping phone offline, the result appears the next time you open the chat or return to the app, without a notification. Nothing continues the task until you tap Review result and finish; continuing without the phone would have to happen on the Hermes server.
- When two phones with dudan both tap Review result and finish, they share one run, because the run carries an idempotency key named after the result. Telegram or another client continuing the chat in the same moment can still start a turn of its own; only Hermes could rule that out.
- A chat shows its latest 500 rows, Hermes' default page. Background results land at the end, so they always show. After a restart, dudan reads older pages to find background work that is still out, but only work started in the last six hours; results of older work appear when you open the chat, not live.
- Uploads run while dudan is open. When Android stops the app during a large upload, pick the file again; the service deletes the abandoned part after 24 hours.
- OpenUI blocks can't fetch data: `Query` renders its defaults, and `Mutation` and `@Run` do nothing. dudan draws the chat library's common components; card blocks such as `SnippetCardBlock` show their text without their layout, and icons cover a few dozen lucide names.
- The side-key overlay needs the microphone permission granted once in the app. Without it, the overlay opens the app to ask.
- Kokoro synthesizes a whole sentence before it plays it, so a long first sentence delays the start of speech. On the x86 emulator a sentence of about 3 seconds starts playing 1.7 to 2.1 seconds after the request once the model is loaded; a phone's arm64 cores are faster, but this hasn't been measured on the Fold 7 yet.
- Orukeet transcribes after you stop talking (1.1 s of silence ends a question), with previews of the text so far. Voice typing in other apps still goes to the phone's recognizer.
- Supertonic synthesizes a sentence before it plays it. On the x86 emulator the first word of a Dutch reply comes 1.4 seconds after the request once the model is loaded; this hasn't been measured on the Fold 7 yet. When a very short first sentence ("Goede vraag.") is followed by a long one, a pause can fall between them while the long one is synthesized.
- Kokoro and Supertonic loaded together take about 1.1 GB of memory, and Orukeet adds its own while listening. Each is released after a few idle minutes.
- If Kokoro's or Supertonic's native library ever crashes the app while loading, the next start notices, switches those replies to the Android voice and shows a Retry link in its Settings section.
- Phone control can't open apps on a locked phone; they show after unlocking, and the tool tells the agent so. It can't read the screen or tap things in other apps either. That would need an accessibility service, which dudan doesn't have.
- The phone must be on Tailscale for Hermes to reach it. Samsung's battery optimizations can stop the service; set dudan's battery usage to Unrestricted if Hermes loses the phone while it sleeps.
- Dialogs, the model sheet and the side-key overlay frost the screen behind them with Android's cross-window blur. When a phone turns that off (some battery savers do), they switch to solid panels over the sharp background. Menus are popups, which can't blur what's behind them, so they are nearly opaque.

## Model credits

The app downloads these models at first use; they are not part of this repository.

- Orukeet v0.1.0 by Oruk AI, weights under CC BY-SA 4.0, adapted from NVIDIA Parakeet TDT 0.6B v3 (CC BY 4.0).
- Kokoro-82M by hexgrad, Apache 2.0, in the sherpa-onnx packaging.
- Supertonic 3 by Supertone, OpenRAIL-M (with use restrictions), from Supertone's archived Hugging Face release.
- Silero VAD, MIT.

