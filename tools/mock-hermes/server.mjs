// Local stand-in for the Hermes Agent API server, for UI development without a live agent.
// Mirrors the wire contract of gateway/platforms/api_server.py (Hermes 0.21): sessions, runs with
// SSE events, session chat stream, approvals, model options, skills and jobs.
//
// Usage: node tools/mock-hermes/server.mjs [port] [upload-port]
//   port         the Hermes API (default 8642)
//   upload-port  the file upload service, same protocol as tools/hermes-upload/aight_upload.py
//                (default 8645). Files go to <OS temp dir>/aight-mock-uploads; upload state lives
//                in memory and is gone after a restart.
// API key for both: dev-key-aight-0000000000 (or set MOCK_KEY).
// With Rich replies on, questions with "vergelijk", "formulier", "grafiek" or "stappen" get OpenUI
// replies (see openui-demo.mjs), and turns with attached files get a reply that names them.
// From the Android emulator the host is http://10.0.2.2:<port>.

import http from "node:http";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { createReadStream, mkdirSync } from "node:fs";
import { open, rename, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileReply, openUiReply } from "./openui-demo.mjs";

const PORT = Number(process.argv[2] ?? 8642);
const UPLOAD_PORT = Number(process.argv[3] ?? 8645);
const KEY = process.env.MOCK_KEY ?? "dev-key-aight-0000000000";

const sessions = new Map(); // id -> { meta, messages }
const runs = new Map(); // id -> { status, output, error, events: [], listeners: Set, approval, stop }
const now = () => Date.now() / 1000;

function seed() {
  const id = "aight_seed_herfst";
  const t = now() - 3600;
  sessions.set(id, {
    meta: { id, source: "api_server", title: "Herfstvakantie regio Midden", started_at: t, last_active: t + 60, pinned: true, message_count: 4 },
    messages: [
      { id: 1, role: "user", content: "Wanneer is de herfstvakantie 2026 in regio Midden?", timestamp: t },
      { id: 2, role: "assistant", content: "", tool_calls: [{ id: "c1", type: "function", function: { name: "web_search", arguments: JSON.stringify({ query: "herfstvakantie 2026 regio midden" }) } }], timestamp: t + 5 },
      { id: 3, role: "tool", tool_call_id: "c1", content: "rijksoverheid.nl: regio Midden 17 t/m 25 oktober 2026", timestamp: t + 9 },
      { id: 4, role: "assistant", content: "De herfstvakantie voor **regio Midden** loopt van **zaterdag 17 oktober** tot en met **zondag 25 oktober 2026**.\n\nZal ik hem in je agenda zetten?", timestamp: t + 14 },
    ],
  });
  const id2 = "aight_seed_code";
  sessions.set(id2, {
    meta: { id: id2, source: "api_server", title: "Drizzle schema voor doos", started_at: t - 86400, last_active: t - 86000, pinned: false, message_count: 2 },
    messages: [
      { id: 5, role: "user", content: "Schrijf een Drizzle schema voor accounts en threads", timestamp: t - 86400 },
      { id: 6, role: "assistant", content: "```typescript\nimport { sqliteTable, text, integer } from 'drizzle-orm/sqlite-core';\n\nexport const accounts = sqliteTable('accounts', {\n  id: text('id').primaryKey(),\n  email: text('email').notNull(),\n});\n```", timestamp: t - 86380 },
    ],
  });
}
seed();

// Questions containing "english" get a short English answer, for testing the English voice.
const answer = (question) => /english/i.test(question)
  ? "Sure. This quarter has three priorities: ship the assistant, close two new clients, and take a proper week off."
  : `Goede vraag. Dit is een **testantwoord** van de mock-server op: _${question.slice(0, 80)}_

## Wat ik deed
1. Ik zocht op het web naar actuele bronnen.
2. Ik las het bestand \`notes.md\` in je vault.
3. Ik vatte het samen.

- Punt een met een [link](https://hermes-agent.nousresearch.com)
- Punt twee
  - Genest punt

| Onderdeel | Status |
|---|---|
| Streaming | werkt |
| Tools | werkt |

\`\`\`kotlin
fun greet(name: String) = "Hallo, $name"
\`\`\`

Nog iets anders waarmee ik je kan helpen?`;

function send(res, status, body, headers = {}) {
  res.writeHead(status, { "Content-Type": "application/json", ...headers });
  res.end(body === undefined ? "" : JSON.stringify(body));
}

function authorized(req) {
  return req.headers.authorization === `Bearer ${KEY}`;
}

async function readBody(req) {
  let data = "";
  for await (const chunk of req) data += chunk;
  if (!data) return {};
  try {
    return JSON.parse(data);
  } catch {
    return null;
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function textOf(content) {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) return content.filter((p) => p.type === "text").map((p) => p.text).join("\n");
  return "";
}

// Runs the scripted agent turn, calling emit(name, payload) for every event.
async function agentTurn(session, input, emit, control, instructions) {
  const question = textOf(input);
  const hasImage = Array.isArray(input) && input.some((p) => p.type === "image_url");
  const t0 = now();
  session.messages.push({ id: session.messages.length + 100, role: "user", content: input, timestamp: t0 });
  emit("reasoning.available", { text: "De gebruiker wil een antwoord. Ik zoek eerst even op." });
  await sleep(500);
  if (control.stopped) return { status: "cancelled" };

  emit("tool.started", { tool: "web_search", preview: `web_search: "${question.slice(0, 40)}"` });
  await sleep(1200);
  emit("tool.completed", { tool: "web_search", duration: 1.2, error: false, preview: "3 results" });
  if (control.stopped) return { status: "cancelled" };

  emit("message.interim", { text: "Ik heb resultaten gevonden, nu lees ik je notities.", already_streamed: false });
  emit("tool.started", { tool: "read_file", preview: "notes.md" });
  await sleep(700);
  emit("tool.completed", { tool: "read_file", duration: 0.7, error: false, preview: "42 lines" });

  if (/verwijder|delete|rm /i.test(question)) {
    const approval = { command: "rm -rf ~/tmp/old-exports", description: "Dangerous command: recursive delete", choices: ["once", "session", "always", "deny"] };
    control.approval = approval;
    emit("approval.request", approval);
    while (!control.decision && !control.stopped) await sleep(200);
    emit("approval.responded", { choice: control.decision });
    control.approval = null;
    if (control.decision === "deny") {
      const out = "Oké, ik heb niets verwijderd.";
      for (const ch of out.match(/.{1,6}/g)) { emit("message.delta", { delta: ch }); await sleep(30); }
      finish(session, out, t0);
      return { status: "completed", output: out };
    }
    emit("tool.started", { tool: "terminal", preview: approval.command });
    await sleep(600);
    emit("tool.completed", { tool: "terminal", duration: 0.6, error: false, preview: "exit 0" });
  }

  const out = (hasImage ? "Ik zie een afbeelding. " : "") + (fileReply(question) ?? openUiReply(question, instructions) ?? answer(question));
  for (const piece of out.match(/[\s\S]{1,8}/g)) {
    if (control.stopped) return { status: "cancelled", output: "" };
    emit("message.delta", { delta: piece });
    await sleep(25);
  }
  finish(session, out, t0);
  return { status: "completed", output: out };
}

function finish(session, out, t0) {
  const t = now();
  session.messages.push(
    { id: session.messages.length + 100, role: "assistant", content: "", tool_calls: [{ id: "x1", function: { name: "web_search", arguments: "{\"query\":\"mock\"}" } }], timestamp: t0 + 1 },
    { id: session.messages.length + 101, role: "tool", tool_call_id: "x1", content: "3 results", timestamp: t0 + 2 },
    { id: session.messages.length + 102, role: "assistant", content: out, timestamp: t },
  );
  session.meta.last_active = t;
  session.meta.message_count = session.messages.length;
  if (!session.meta.title) session.meta.title = textOf(session.messages.find((m) => m.role === "user")?.content ?? "").slice(0, 40);
}

function sse(res, headers = {}) {
  res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", ...headers });
  const keepalive = setInterval(() => res.write(": keepalive\n\n"), 10000);
  res.on("close", () => clearInterval(keepalive));
  return keepalive;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  const path = url.pathname.replace(/\/+$/, "");
  const parts = path.split("/").filter(Boolean);
  console.log(req.method, path);

  if (path === "/health" || path === "/v1/health") return send(res, 200, { status: "ok", platform: "hermes-agent", version: "mock" });
  if (!authorized(req)) return send(res, 401, { error: { message: "Invalid API key", code: "invalid_api_key" } });

  if (path === "/v1/capabilities") return send(res, 200, { object: "hermes.api_server.capabilities", platform: "hermes-agent", model: "clark", features: { run_submission: true } });
  if (path === "/api/model/options") {
    return send(res, 200, {
      current_provider: "openrouter",
      current_model: "anthropic/claude-opus-4.6",
      providers: [
        {
          slug: "openrouter", name: "OpenRouter", authenticated: true, models: ["anthropic/claude-opus-4.6", "google/gemini-3-flash", "openai/gpt-5.5"],
          // Real Hermes reports per model whether it takes a thinking level and fast mode (priority processing).
          capabilities: {
            "anthropic/claude-opus-4.6": { fast: true, reasoning: true },
            "google/gemini-3-flash": { fast: false, reasoning: true },
            "openai/gpt-5.5": { fast: true, reasoning: true },
          },
        },
        { slug: "nous", name: "Nous Portal", authenticated: true, models: [{ id: "hermes-4-405b", label: "Hermes 4 405B" }], capabilities: { "hermes-4-405b": { fast: false, reasoning: false } } },
        { slug: "anthropic", name: "Anthropic", authenticated: false, models: ["claude-haiku"] },
      ],
    });
  }
  if (path === "/v1/skills") {
    return send(res, 200, { object: "list", data: [
      { name: "github-pr-workflow", description: "Open, review and merge pull requests", category: "software" },
      { name: "obsidian-notes", description: "Search and write notes in the Obsidian vault", category: "knowledge" },
      { name: "todoist", description: "Manage Todoist tasks", category: "productivity" },
    ] });
  }
  if (path === "/api/jobs" && req.method === "GET") {
    return send(res, 200, { jobs: [
      { id: "a1b2c3d4e5f6", name: "Morning brief", schedule: { display: "every day at 07:30" }, prompt: "Summarise my agenda and inbox", enabled: true, next_run_at: "2026-09-26T07:30:00" },
      { id: "b1b2c3d4e5f6", name: "Weekly review", schedule: { display: "Fridays 16:00" }, prompt: "Review the week in Todoist", enabled: false },
    ] });
  }
  if (parts[0] === "api" && parts[1] === "jobs" && req.method === "POST") return send(res, 200, { job: { id: parts[2] } });

  if (path === "/api/sessions" && req.method === "GET") {
    const list = [...sessions.values()].map((s) => ({ ...s.meta, preview: textOf(s.messages.find((m) => m.role === "user")?.content ?? "").slice(0, 60) }));
    list.sort((a, b) => b.last_active - a.last_active);
    return send(res, 200, { object: "list", data: list, limit: 100, offset: 0, has_more: false });
  }
  if (path === "/api/sessions" && req.method === "POST") {
    const body = await readBody(req);
    const id = body?.id ?? `api_${Date.now()}`;
    if (sessions.has(id)) return send(res, 409, { error: { message: `Session already exists: ${id}`, code: "session_exists" } });
    const t = now();
    sessions.set(id, { meta: { id, source: "api_server", title: body?.title ?? null, started_at: t, last_active: t, pinned: false, message_count: 0 }, messages: [] });
    return send(res, 201, { object: "hermes.session", session: sessions.get(id).meta });
  }
  if (parts[0] === "api" && parts[1] === "sessions" && parts[2]) {
    const s = sessions.get(decodeURIComponent(parts[2]));
    if (!s) return send(res, 404, { error: { message: `Session not found: ${parts[2]}`, code: "session_not_found" } });
    if (parts.length === 3 && req.method === "PATCH") {
      const body = await readBody(req);
      if ("title" in body) s.meta.title = body.title;
      if ("pinned" in body) s.meta.pinned = body.pinned;
      return send(res, 200, { object: "hermes.session", session: s.meta });
    }
    if (parts.length === 3 && req.method === "DELETE") {
      sessions.delete(s.meta.id);
      return send(res, 200, { object: "hermes.session.deleted", id: s.meta.id, deleted: true });
    }
    if (parts[3] === "messages") return send(res, 200, { object: "list", session_id: s.meta.id, data: s.messages });
    if (parts[3] === "chat" && parts[4] === "stream") {
      const body = await readBody(req);
      const runId = `run_${randomUUID().replace(/-/g, "")}`;
      const control = { stopped: false };
      runs.set(runId, { status: "running", control });
      const keepalive = sse(res, { "X-Hermes-Session-Id": s.meta.id });
      let seq = 0;
      const emit = (name, payload) => {
        const map = { "message.delta": "assistant.delta", "message.interim": "assistant.commentary", "reasoning.available": "tool.progress" };
        const out = { session_id: s.meta.id, run_id: runId, seq: ++seq, ts: now(), ...payload };
        if (name === "reasoning.available") { out.tool_name = "_thinking"; out.delta = payload.text; }
        if (name.startsWith("tool.") && payload.tool) out.tool_name = payload.tool;
        res.write(`event: ${map[name] ?? name}\ndata: ${JSON.stringify(out)}\n\n`);
      };
      emit("run.started", { user_message: { role: "user", content: body.message } });
      const result = await agentTurn(s, body.message, emit, control, body.system_message ?? body.instructions);
      emit("assistant.completed", { content: result.output ?? "" });
      emit(`run.${result.status}`, { messages: [] });
      emit("done", {});
      runs.get(runId).status = result.status;
      clearInterval(keepalive);
      return res.end();
    }
  }

  if (path === "/v1/runs" && req.method === "POST") {
    const body = await readBody(req);
    const s = sessions.get(body.session_id);
    if (!s) return send(res, 404, { error: { message: "Session not found" } });
    console.log("  run", body.session_id, "model:", body.provider ?? "-", body.model ?? "(server default)", JSON.stringify(body.model_options ?? {}));
    const runId = `run_${randomUUID().replace(/-/g, "")}`;
    const run = { status: "running", output: null, error: null, events: [], listeners: new Set(), control: { stopped: false } };
    runs.set(runId, run);
    const emit = (name, payload) => {
      const event = { event: name, run_id: runId, timestamp: now(), ...payload };
      run.events.push(event);
      for (const l of run.listeners) l(event);
    };
    (async () => {
      await sleep(300);
      const result = await agentTurn(s, body.input, emit, run.control, body.instructions);
      run.status = result.status;
      run.output = result.output ?? null;
      emit(`run.${result.status}`, { output: result.output ?? "" });
      for (const l of run.listeners) l(null);
    })();
    return send(res, 202, { run_id: runId, status: "started" });
  }
  if (parts[0] === "v1" && parts[1] === "runs" && parts[2]) {
    const run = runs.get(parts[2]);
    if (!run) return send(res, 404, { error: { message: `Run not found: ${parts[2]}`, code: "run_not_found" } });
    if (parts[3] === "events") {
      const keepalive = sse(res);
      const write = (event) => {
        if (event === null) { res.write(": stream closed\n\n"); clearInterval(keepalive); res.end(); return; }
        res.write(`data: ${JSON.stringify(event)}\n\n`);
      };
      run.events.forEach(write);
      if (["completed", "failed", "cancelled"].includes(run.status)) return write(null);
      run.listeners.add(write);
      res.on("close", () => run.listeners.delete(write));
      return;
    }
    if (parts[3] === "stop") {
      run.control.stopped = true;
      return send(res, 200, { run_id: parts[2], status: "stopping" });
    }
    if (parts[3] === "approval") {
      const body = await readBody(req);
      run.control.decision = body.choice;
      return send(res, 200, { run_id: parts[2], resolved: 1 });
    }
    return send(res, 200, { object: "hermes.run", run_id: parts[2], status: run.status, output: run.output, approval: run.control.approval ?? undefined });
  }

  send(res, 404, { error: { message: `No route ${req.method} ${path}` } });
});

server.listen(PORT, "0.0.0.0", () => console.log(`mock hermes on http://0.0.0.0:${PORT} key=${KEY}`));

// Upload service ---------------------------------------------------------------------------------
// Stand-in for tools/hermes-upload/aight_upload.py with the same endpoints, status codes and error
// codes. It skips fsync, the free-space check and pruning.

const UPLOAD_DIR = join(tmpdir(), "aight-mock-uploads");
const UPLOAD_MAX_BYTES = 250 * 1024 * 1024;
const UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024;
const UPLOAD_MAX_CHUNK = 16 * 1024 * 1024;
const uploads = new Map(); // id -> { id, name, safe_name, mime, size, offset, complete, expected, sha256, path, part, busy }

const uploadError = (res, status, code, message, extra = {}, headers = {}) =>
  send(res, status, { error: { message, code }, ...extra }, headers);
const notAllowed = (res, methods) =>
  uploadError(res, 405, "method_not_allowed", `Use ${methods.join(" or ")} here.`, {}, { Allow: methods.join(", ") });
const megabytes = (n) => `${+(n / 1048576).toPrecision(4)} MB`;

// Same rules as safe_name() in aight_upload.py.
function safeName(name) {
  let base = name.split(/[\\/]/).pop();
  base = base.replace(/\s+/gu, " ").replace(/[\p{Cc}\p{Cf}\p{Cs}<>:"/\\|?*]/gu, "").replace(/ {2,}/g, " ");
  base = base.replace(/^[\s.]+|[\s.]+$/gu, "");
  const dot = base.lastIndexOf(".");
  let ext = dot > 0 ? base.slice(dot) : "";
  if (ext.length > 16) ext = "";
  const stem = [...(ext ? base.slice(0, -ext.length) : base)].slice(0, 120 - ext.length);
  while (stem.length && Buffer.byteLength(stem.join("") + ext) > 200) stem.pop();
  const clean = stem.join("").replace(/[ .]+$/, "");
  return clean ? clean + ext : "file";
}

const sha256File = (file) => new Promise((resolve, reject) => {
  const hash = createHash("sha256");
  createReadStream(file).on("data", (d) => hash.update(d)).on("error", reject).on("end", () => resolve(hash.digest("hex")));
});

const completion = (u) => ({
  offset: u.size, complete: true, path: u.path, name: u.name, safe_name: u.safe_name, mime: u.mime, size: u.size, sha256: u.sha256,
});

const describeUpload = (u) => ({
  id: u.id, name: u.name, mime: u.mime, size: u.size, offset: u.offset, complete: u.complete,
  ...(u.complete ? { path: u.path, sha256: u.sha256 } : {}),
});

async function createUpload(req, res) {
  if (req.headers["content-length"] === undefined) return uploadError(res, 411, "length_required", "Content-Length is required.");
  if (Number(req.headers["content-length"]) > 64 * 1024) return uploadError(res, 413, "request_too_large", "The request body is over 64 KiB.");
  const body = await readBody(req);
  if (!body || typeof body !== "object" || Array.isArray(body)) return uploadError(res, 400, "invalid_request", "The body must be a JSON object.");
  const { name, size, mime, sha256 } = body;
  if (typeof name !== "string" || !name.trim()) return uploadError(res, 400, "invalid_request", "name must be a non-empty string.");
  if (!Number.isInteger(size) || size <= 0) return uploadError(res, 400, "invalid_request", "size must be a positive whole number of bytes.");
  if (size > UPLOAD_MAX_BYTES) return uploadError(res, 413, "too_large", `The file is ${megabytes(size)}; the limit is ${megabytes(UPLOAD_MAX_BYTES)}.`);
  if (mime != null && (typeof mime !== "string" || mime.length > 255)) return uploadError(res, 400, "invalid_request", "mime must be a string of at most 255 characters.");
  if (sha256 != null && (typeof sha256 !== "string" || !/^[0-9a-f]{64}$/i.test(sha256))) return uploadError(res, 400, "invalid_request", "sha256 must be 64 hexadecimal characters.");
  const id = randomBytes(16).toString("hex");
  const u = {
    id, name, safe_name: safeName(name), mime: mime?.trim() || "application/octet-stream", size, offset: 0, complete: false,
    expected: sha256?.toLowerCase() ?? null, sha256: null, path: null, part: join(UPLOAD_DIR, ".partial", `${id}.part`), busy: false,
  };
  await writeFile(u.part, "");
  uploads.set(id, u);
  console.log(`  upload ${id} created: ${JSON.stringify(name)}, ${size} bytes`);
  return send(res, 201, { id, offset: 0, size, chunk_bytes: UPLOAD_CHUNK_BYTES });
}

async function putChunk(req, res, u, url) {
  const offsetParam = url.searchParams.getAll("offset");
  if (offsetParam.length !== 1 || !/^\d+$/.test(offsetParam[0])) return uploadError(res, 400, "invalid_request", "Pass the chunk's position as ?offset=<bytes>.");
  const rawLength = req.headers["content-length"];
  if (rawLength === undefined) return uploadError(res, 411, "length_required", "Content-Length is required.");
  const length = Number(rawLength);
  if (length > UPLOAD_MAX_CHUNK) return uploadError(res, 413, "chunk_too_large", `A chunk can be at most ${UPLOAD_MAX_CHUNK} bytes; this one is ${length}.`, {}, { Connection: "close" });
  const offset = Number(offsetParam[0]);
  if (offset !== u.offset) return uploadError(res, 409, "offset_mismatch", `The upload is at offset ${u.offset}, not ${offset}.`, { offset: u.offset });
  if (offset + length > u.size) return uploadError(res, 400, "invalid_request", `The chunk runs past the end of the file (${u.size} bytes).`);
  if (u.busy) return uploadError(res, 409, "upload_busy", "Another request is still sending a chunk for this upload. Try again in a few seconds.", { offset: u.offset });
  // An empty PUT at the end of a finished upload repeats the final answer, for a client that lost it.
  if (u.complete) return send(res, 200, completion(u));

  u.busy = true;
  let received = 0;
  try {
    const fh = await open(u.part, "r+");
    try {
      await fh.truncate(u.offset); // bytes past the committed offset come from an interrupted chunk
      try {
        for await (const chunk of req) {
          await fh.write(chunk, 0, chunk.length, u.offset + received);
          received += chunk.length;
        }
      } catch {
        // the client went away; handled below
      }
      if (received !== length) {
        await fh.truncate(u.offset);
        console.log(`  upload ${u.id}: connection lost after ${received} of ${length} bytes; rolled back to offset ${u.offset}`);
        return;
      }
    } finally {
      await fh.close();
    }
    u.offset += length;
    if (u.offset < u.size) return send(res, 200, { offset: u.offset, complete: false });

    const digest = await sha256File(u.part);
    if (u.expected && digest !== u.expected) {
      await rm(u.part, { force: true });
      uploads.delete(u.id);
      return uploadError(res, 422, "checksum_mismatch", `The received file has SHA-256 ${digest}, not ${u.expected}. The upload was removed; send the file again.`);
    }
    const final = join(UPLOAD_DIR, `${u.id.slice(0, 12)}_${u.safe_name}`);
    await rename(u.part, final);
    Object.assign(u, { complete: true, sha256: digest, path: final });
    console.log(`  upload ${u.id} complete: ${final}`);
    return send(res, 200, completion(u));
  } finally {
    u.busy = false;
  }
}

const uploadServer = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  const route = url.pathname.replace(/\/+$/, "");
  console.log("upload", req.method, route + url.search);

  if (req.headers.origin !== undefined) return uploadError(res, 403, "browser_refused", "Browsers can't use this service.");
  if (!authorized(req)) return uploadError(res, 401, "unauthorized", "Missing or wrong API key.");

  if (route === "/health") {
    if (req.method !== "GET") return notAllowed(res, ["GET"]);
    return send(res, 200, { ok: true, version: 1, max_bytes: UPLOAD_MAX_BYTES, chunk_bytes: UPLOAD_CHUNK_BYTES });
  }
  if (route === "/uploads") {
    if (req.method !== "POST") return notAllowed(res, ["POST"]);
    return createUpload(req, res);
  }
  const match = route.match(/^\/uploads\/([^/]+)$/);
  if (!match || !/^[0-9a-f]{32}$/.test(match[1])) return uploadError(res, 404, "not_found", match ? "No such upload." : "No such endpoint.");
  if (!["GET", "PUT", "DELETE"].includes(req.method)) return notAllowed(res, ["GET", "PUT", "DELETE"]);
  const u = uploads.get(match[1]);
  if (!u) return uploadError(res, 404, "not_found", "No such upload.");

  if (req.method === "GET") return send(res, 200, describeUpload(u));
  if (req.method === "PUT") return putChunk(req, res, u, url);
  if (u.busy) return uploadError(res, 409, "upload_busy", "A chunk for this upload is still being written. Try again shortly.", { offset: u.offset });
  uploads.delete(u.id);
  await rm(u.complete ? u.path : u.part, { force: true });
  console.log(`  upload ${u.id} deleted`);
  res.writeHead(204);
  res.end();
});

mkdirSync(join(UPLOAD_DIR, ".partial"), { recursive: true });
uploadServer.listen(UPLOAD_PORT, "0.0.0.0", () => console.log(`mock upload service on http://0.0.0.0:${UPLOAD_PORT}, files in ${UPLOAD_DIR}`));
