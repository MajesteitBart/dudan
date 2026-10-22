// Agent screen, protocol version 1 (docs/agent-screen.md), for the mock: MOCK_DESKTOP=1 turns it on. It seeds one
// conversation with two desktops, one stopped and one live, as a task that stopped and attached again leaves them.
// Frames are PNGs drawn here from rectangles, so no real page or person is ever on screen.
import { randomBytes } from "node:crypto";
import { deflateSync } from "node:zlib";

export const DESKTOP = process.env.MOCK_DESKTOP === "1";
export const DESKTOP_SESSION = "dudan_seed_desktop";
const WIDTH = 1280;
const HEIGHT = 800;

export const desktopFeature = () => (DESKTOP ? { desktop: { version: 1, supported: true, transports: ["png-poll"], control: true, recording: false, teaching: false } } : {});

const TEXT = {
  nl: { title: "Kantoorruimte Amsterdam", question: "Zoek kantoorruimte in Amsterdam, binnen 15 minuten van Centraal of Zuid, maximaal €1.000 per maand.", answer: "Ik kijk op twee sites in de browser van mijn bureaublad. Je kunt meekijken in het Agentscherm." },
  en: { title: "Office space in Amsterdam", question: "Find office space in Amsterdam within 15 minutes of Centraal or Zuid, at most €1,000 a month.", answer: "I'm checking two sites in my desktop's browser. You can watch in the Agent screen." },
};

/** The seeded conversation, for the mock's session list, and its desktops. */
export function seedDesktopConversation(lang, rowId, t) {
  const text = TEXT[lang];
  return {
    meta: { id: DESKTOP_SESSION, source: "api_server", title: text.title, started_at: t + 120, last_active: t + 300, pinned: false, message_count: 2 },
    messages: [
      { id: rowId(), role: "user", content: text.question, timestamp: t + 120 },
      { id: rowId(), role: "assistant", content: text.answer, timestamp: t + 150 },
    ],
  };
}

const started = Date.now();
const desktops = [
  { id: "00000000-0000-4000-8000-0000000000a1", state: "stopped", agent_paused: true, epoch: 3, last_frame_at: started - 90_000, scene: "search" },
  { id: "00000000-0000-4000-8000-0000000000a2", state: "live", agent_paused: false, epoch: 1, last_frame_at: started, scene: "listing" },
];
// As on Clark: a frame grant belongs to one desktop and viewer until it expires, and each desktop has at most one
// control lease, bound to its viewer and epoch, with inputs numbered from 1.
const grants = new Map(); // token -> { desktop, viewer, expires }
const LEASE_MS = Number(process.env.MOCK_DESKTOP_LEASE_MS ?? 30_000); // shorter to try what an expired lease does
const VIEWER = /^[a-zA-Z0-9_-]{16,100}$/;
// An expired lease ends the person's control, as on Clark: the desktop is live again and the agent stays paused
// until someone hands it back.
const expire = (d) => {
  if (d.lease && d.lease.expires <= Date.now()) Object.assign(d, { lease: null, state: "live", agent_paused: true, epoch: d.epoch + 1 });
};
const leaseOf = (d, req, body) => {
  const lease = d.lease;
  if (!lease || lease.expires <= Date.now() || lease.token !== body.lease || lease.viewer !== req.headers["x-desktop-viewer"]) return null;
  return lease;
};

function view(d) {
  return {
    version: 1, id: d.id, session_id: DESKTOP_SESSION, conversation_id: 1, task_id: "mock-task", run_id: "mock-run", resource_id: "mock-desktop",
    state: d.state, agent_paused: d.agent_paused, epoch: d.epoch, width: WIDTH, height: HEIGHT, last_frame_at: d.last_frame_at,
    expires_at: started + 3_600_000, control_expires_at: null, problem: null, transport: "png-poll",
  };
}

const error = (send, res, status, code) => send(res, status, { error: { code, message: code.replaceAll("_", " "), type: "invalid_request_error" } });

/** Handles /api/sessions/{session}/desktops[/{id}[/{action}]]; returns false for any other path. */
export async function handleDesktop(req, res, parts, send, readBody) {
  if (!DESKTOP || parts[0] !== "api" || parts[1] !== "sessions" || parts[3] !== "desktops") return false;
  const noStore = { "Cache-Control": "no-store" };
  const mine = decodeURIComponent(parts[2]) === DESKTOP_SESSION;
  desktops.forEach(expire);
  if (parts.length === 4 && req.method === "GET") return send(res, 200, { version: 1, data: mine ? desktops.map(view) : [] }, noStore), true;
  const d = mine ? desktops.find((x) => x.id === parts[4]) : undefined;
  if (!d) return error(send, res, 404, "desktop_not_found"), true;
  const action = parts[5];
  const viewer = req.headers["x-desktop-viewer"];
  if (action !== undefined && (typeof viewer !== "string" || !VIEWER.test(viewer))) return error(send, res, 400, "desktop_viewer_invalid"), true;
  if (action === undefined && req.method === "GET") return send(res, 200, view(d), noStore), true;
  if (action === "frame" && req.method === "GET") {
    const grant = grants.get(req.headers["x-desktop-grant"]);
    if (!grant || grant.desktop !== d.id || grant.viewer !== req.headers["x-desktop-viewer"] || grant.expires <= Date.now()) return error(send, res, 403, "desktop_grant_invalid"), true;
    if (d.state !== "stopped") d.last_frame_at = Date.now();
    res.writeHead(200, { "Content-Type": "image/png", "X-Desktop-Frame-At": String(d.last_frame_at), ...noStore });
    res.end(frame(d.scene, d.state === "stopped" ? 0 : Math.floor(Date.now() / 1000)));
    return true;
  }
  if (req.method !== "POST") return error(send, res, 404, "desktop_not_found"), true;
  const body = (await readBody(req)) ?? {};
  if (d.state === "stopped" && action !== "stop") return error(send, res, 409, "desktop_stopped"), true;
  switch (action) {
    case "grant": {
      for (const [token, g] of grants) if (g.expires <= Date.now() || (g.desktop === d.id && g.viewer === viewer)) grants.delete(token);
      const grant = randomBytes(24).toString("base64url"), expires = Date.now() + 30_000;
      grants.set(grant, { desktop: d.id, viewer, expires });
      return send(res, 200, { version: 1, grant, expires_at: expires, transport: "png-poll", interval_ms: 1000 }, noStore), true;
    }
    case "acquire": {
      if (d.lease && d.lease.expires > Date.now()) return error(send, res, 409, "desktop_control_busy"), true;
      const token = randomBytes(24).toString("base64url");
      d.epoch += 1;
      d.lease = { token, viewer, epoch: d.epoch, sequence: 0, expires: Date.now() + LEASE_MS };
      Object.assign(d, { state: "human-controlled", agent_paused: true });
      return send(res, 200, { version: 1, lease: token, expires_at: d.lease.expires, epoch: d.epoch, next_sequence: 1 }, noStore), true;
    }
    case "renew": {
      const lease = leaseOf(d, req, body);
      if (!lease) return error(send, res, 403, "desktop_lease_invalid"), true;
      lease.expires = Date.now() + LEASE_MS;
      return send(res, 200, { expires_at: lease.expires }, noStore), true;
    }
    case "input": {
      const lease = leaseOf(d, req, body);
      if (!lease) return error(send, res, 403, "desktop_lease_invalid"), true;
      if (body.epoch !== lease.epoch || body.sequence !== lease.sequence + 1) return error(send, res, 409, "desktop_stale_input"), true;
      lease.sequence = body.sequence;
      return send(res, 200, { version: 1, accepted: true, epoch: d.epoch }, noStore), true;
    }
    case "release":
      if (!leaseOf(d, req, body)) return error(send, res, 403, "desktop_lease_invalid"), true;
      d.lease = null;
      Object.assign(d, { state: "live", agent_paused: true, epoch: d.epoch + 1 });
      return send(res, 200, view(d), noStore), true;
    case "handback":
      if (body.epoch !== d.epoch) return error(send, res, 409, "desktop_stale_epoch"), true;
      if (d.lease && d.lease.expires > Date.now() && !leaseOf(d, req, body)) return error(send, res, 403, "desktop_lease_invalid"), true;
      d.lease = null;
      Object.assign(d, { state: "live", agent_paused: false, epoch: d.epoch + 1 });
      return send(res, 200, view(d), noStore), true;
    case "stop":
      d.lease = null;
      for (const [token, g] of grants) if (g.desktop === d.id) grants.delete(token);
      Object.assign(d, { state: "stopped", agent_paused: true, epoch: d.epoch + 1 });
      return send(res, 200, view(d), noStore), true;
    default:
      return error(send, res, 404, "desktop_not_found"), true;
  }
}

// A desktop drawn from rectangles: the top panel, a browser window filling the screen below it, and a page. `tick`
// moves a highlight down the page so a live preview visibly changes.
function frame(scene, tick) {
  const pixels = Buffer.alloc(WIDTH * HEIGHT * 3);
  const fill = (x, y, w, h, [r, g, b]) => {
    for (let row = Math.max(0, y); row < Math.min(HEIGHT, y + h); row++) {
      for (let col = Math.max(0, x); col < Math.min(WIDTH, x + w); col++) {
        const i = (row * WIDTH + col) * 3;
        pixels[i] = r; pixels[i + 1] = g; pixels[i + 2] = b;
      }
    }
  };
  const page = [250, 250, 251], ink = [60, 64, 72], soft = [205, 209, 216], accent = scene === "search" ? [26, 115, 232] : [237, 125, 49];
  fill(0, 0, WIDTH, HEIGHT, [24, 33, 46]);
  fill(0, 0, WIDTH, 26, [32, 36, 43]);
  fill(8, 6, 110, 14, [70, 78, 92]);
  fill(0, 26, WIDTH, 44, [222, 226, 233]);
  fill(16, 36, 200, 26, page);
  fill(240, 38, 760, 22, page);
  fill(256, 46, 300, 6, soft);
  fill(0, 70, WIDTH, HEIGHT - 70, page);
  fill(0, 70, WIDTH, 4, accent);
  fill(40, 96, 160, 22, accent);
  fill(240, 98, 520, 18, soft);
  fill(900, 94, 200, 28, accent);
  if (scene === "search") {
    for (let i = 0; i < 6; i++) {
      const y = 150 + i * 102;
      fill(40, y, 420, 14, [26, 13, 171]);
      fill(40, y + 26, 760, 10, soft);
      fill(40, y + 44, 640, 10, soft);
    }
  } else {
    fill(40, 150, 220, 600, [241, 243, 246]);
    for (let i = 0; i < 8; i++) fill(60, 172 + i * 44, 160, 12, soft);
    for (let i = 0; i < 4; i++) {
      const y = 150 + i * 150;
      fill(290, y, 190, 130, [214, 219, 228]);
      fill(500, y + 8, 420, 16, ink);
      fill(500, y + 40, 140, 16, accent);
      fill(500, y + 72, 520, 10, soft);
      fill(500, y + 90, 380, 10, soft);
    }
  }
  if (tick) fill(290, 150 + (tick % 4) * 150, 4, 130, accent);
  return png(pixels);
}

function png(rgb) {
  const raw = Buffer.alloc((WIDTH * 3 + 1) * HEIGHT);
  for (let row = 0; row < HEIGHT; row++) rgb.copy(raw, row * (WIDTH * 3 + 1) + 1, row * WIDTH * 3, (row + 1) * WIDTH * 3);
  const header = Buffer.alloc(13);
  header.writeUInt32BE(WIDTH, 0); header.writeUInt32BE(HEIGHT, 4);
  header[8] = 8; header[9] = 2; // 8-bit RGB
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", header), chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

const CRC = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
function chunk(type, data) {
  const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  let crc = 0xffffffff;
  for (const byte of body) crc = CRC[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  const sum = Buffer.alloc(4); sum.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
  return Buffer.concat([length, body, sum]);
}
