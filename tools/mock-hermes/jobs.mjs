// Scheduled tasks for the mock, kept in memory until it stops.
//
// By default they answer like stock Hermes (gateway/platforms/api_server.py, cron/jobs.py): 12-hex ids, `schedule` as
// {kind: cron | interval | once, expr | minutes | run_at, display}, PATCH taking only Hermes' whitelisted keys, errors
// as {error: "message"}, and no job endpoints in /v1/capabilities, which is how the app knows stock Hermes.
//
// MOCK_CLARK_JOBS=1 answers like Clark's dudan channel (src/channels/dudan/jobs.ts) instead: ids made from names,
// one-offs as `at YYYY-MM-DDTHH:MM` in the job's zone, `editable` with an `edit_note` on the hand-written heartbeat,
// `name_editable: false`, `deliver` and the list's `delivery_targets`, errors as {error: {code, message}}, and job,
// job_create, job_update and job_delete in /v1/capabilities. MOCK_CLARK_JOBS=old is a Clark from before that: it
// lists jobs and job_action only and has no single-job routes, so the app shows no add, edit or delete.
//
// Times are on MOCK_TZ's clock (default Europe/Amsterdam), like Hermes' configured zone and Clark's config.timezone.
import { randomBytes } from "node:crypto";

const MODE = { "1": "clark", old: "old-clark" }[process.env.MOCK_CLARK_JOBS] ?? "hermes";
const CLARK = MODE !== "hermes";
const ZONE = process.env.MOCK_TZ ?? "Europe/Amsterdam";
/** Clark's config.destinations: where a job's results can go. */
const DESTINATIONS = {
  telegram: { channel: "telegram", destination: "100200300", description: "Telegram (Bart)" },
  mail: { channel: "email", destination: "bart@example.com", description: "Email" },
};
const PROMPT_CHARS = 600; // Clark's list cuts prompts; GET /api/jobs/{id} doesn't
const HERMES_WHITELIST = new Set(["name", "schedule", "prompt", "deliver", "skills", "skill", "repeat", "enabled"]);
const HAND_WRITTEN = "Written by hand in heartbeat.yaml; change it on the server.";

const TEXT = {
  nl: {
    heartbeat: "Kijk of er iets is dat mijn aandacht nodig heeft, en meld het alleen als dat zo is.",
    dentist: "Tandarts bellen",
    dentistPrompt: "Herinner me eraan de tandarts te bellen voor een controle.",
  },
  en: {
    heartbeat: "Check whether anything needs my attention, and only tell me if something does.",
    dentist: "Call the dentist",
    dentistPrompt: "Remind me to call the dentist for a check-up.",
  },
};

const jobs = new Map(); // id -> stored job, in creation order

// ---- Time on a named zone's clock ------------------------------------------------------------------------------

const formatters = new Map();
function localParts(ms, tz) {
  let format = formatters.get(tz);
  if (!format) {
    format = new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" });
    formatters.set(tz, format);
  }
  const p = {};
  for (const part of format.formatToParts(new Date(ms))) if (part.type !== "literal") p[part.type] = Number(part.value);
  return { year: p.year, month: p.month, day: p.day, hour: p.hour % 24, minute: p.minute, second: p.second };
}
const offsetMinutes = (ms, tz) => {
  const p = localParts(ms, tz);
  return Math.round((Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - Math.floor(ms / 1000) * 1000) / 60_000);
};
const pad = (n, w = 2) => String(n).padStart(w, "0");
/** 2026-10-27T09:00:00+01:00 */
function localIso(ms, tz) {
  const p = localParts(ms, tz);
  const offset = offsetMinutes(ms, tz);
  const abs = Math.abs(offset);
  return `${p.year}-${pad(p.month)}-${pad(p.day)}T${pad(p.hour)}:${pad(p.minute)}:${pad(p.second)}${offset < 0 ? "-" : "+"}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}
/** The first instant whose wall time in tz is `local`, or null in a spring-forward gap. */
function instantOf(local, tz) {
  const naive = Date.UTC(local.year, local.month - 1, local.day, local.hour, local.minute);
  const found = [];
  for (const probe of [-26, -3, -1, 0, 1, 3, 26]) {
    const candidate = naive - offsetMinutes(naive + probe * 3_600_000, tz) * 60_000;
    const p = localParts(candidate, tz);
    if (p.year === local.year && p.month === local.month && p.day === local.day && p.hour === local.hour && p.minute === local.minute) found.push(candidate);
  }
  return found.length ? Math.min(...found) : null;
}
const validZone = (tz) => {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
    return true;
  } catch {
    return false;
  }
};

// ---- Cron ------------------------------------------------------------------------------------------------------

function field(text, min, max, names = []) {
  const out = new Set();
  const value = (token) => {
    const named = names.indexOf(token.toLowerCase());
    if (named >= 0) return named + min;
    if (!/^\d+$/.test(token)) throw new Error(`"${token}" is not a number`);
    return Number(token);
  };
  for (const part of text.split(",")) {
    const [range = "", stepText] = part.split("/");
    const step = stepText === undefined ? 1 : Number(stepText);
    if (!Number.isInteger(step) || step < 1) throw new Error(`bad step in ${part}`);
    let [low, high] = range === "*" ? [min, max] : range.includes("-") ? range.split("-").map(value) : [value(range), stepText === undefined ? value(range) : max];
    if (low < min || high > max || low > high) throw new Error(`${part} is outside ${min}-${max}`);
    for (let v = low; v <= high; v += step) out.add(v);
  }
  return out;
}

function parseCron(source) {
  const parts = source.trim().split(/\s+/);
  if (parts.length !== 5) throw new Error(`expected 5 fields, got ${parts.length}`);
  const [m, h, dom, mon, dow] = parts;
  const weekdays = field(dow, 0, 7, ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]);
  if (weekdays.delete(7)) weekdays.add(0);
  return {
    minutes: [...field(m, 0, 59)].sort((a, b) => a - b),
    hours: [...field(h, 0, 23)].sort((a, b) => a - b),
    days: field(dom, 1, 31),
    months: field(mon, 1, 12, ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]),
    weekdays,
    dayRestricted: dom !== "*",
    weekdayRestricted: dow !== "*",
  };
}

/** The first run after `after` (ms), walking matching days; null when there's none in eight years. */
function nextCron(cron, tz, after) {
  const start = localParts(after, tz);
  let day = Date.UTC(start.year, start.month - 1, start.day);
  for (let i = 0; i < 366 * 8 + 2; i++, day += 86_400_000) {
    const date = new Date(day);
    const [year, month, d] = [date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate()];
    if (!cron.months.has(month)) continue;
    const domOk = cron.days.has(d);
    const dowOk = cron.weekdays.has(date.getUTCDay());
    const matches = cron.dayRestricted && cron.weekdayRestricted ? domOk || dowOk : cron.dayRestricted ? domOk : cron.weekdayRestricted ? dowOk : true;
    if (!matches) continue;
    for (const hour of cron.hours) for (const minute of cron.minutes) {
      const at = instantOf({ year, month, day: d, hour, minute }, tz);
      if (at !== null && at > after) return at;
    }
  }
  return null;
}

// ---- Hermes ----------------------------------------------------------------------------------------------------

class Refusal extends Error {
  constructor(status, code, message) {
    super(message);
    Object.assign(this, { status, code });
  }
}

/** A subset of cron/jobs.py:parse_schedule: intervals, cron, ISO one-offs and "in 30m". */
function hermesSchedule(text) {
  const s = String(text ?? "").trim();
  const duration = /^(?:every\s+)?(\d*)\s*(m|min|mins|minutes?|h|hr|hrs|hours?|d|days?)$/i.exec(s);
  if (duration) {
    const minutes = (Number(duration[1]) || 1) * { m: 1, h: 60, d: 1440 }[duration[2][0].toLowerCase()];
    return { kind: "interval", minutes, display: `every ${minutes}m` };
  }
  const parts = s.split(/\s+/);
  if (parts.length >= 5 && parts.slice(0, 5).every((p) => /^[A-Za-z\d*\-,/]+$/.test(p))) {
    try {
      parseCron(s);
    } catch (e) {
      throw new Refusal(400, null, `Invalid cron expression '${s}': ${e.message}`);
    }
    return { kind: "cron", expr: s, display: s };
  }
  if (s.includes("T") || /^\d{4}-\d{2}-\d{2}/.test(s)) {
    const at = /[zZ]|[+-]\d{2}:?\d{2}$/.test(s) ? Date.parse(s) : wallTime(s, ZONE);
    if (Number.isNaN(at) || at === null) throw new Refusal(400, null, `Invalid timestamp '${s}'`);
    const runAt = localIso(at, ZONE);
    return { kind: "once", run_at: runAt, display: `once at ${runAt.slice(0, 10)} ${runAt.slice(11, 16)}` };
  }
  const delay = /^in\s+(\d+)\s*(m|h|d)$/i.exec(s);
  if (delay) {
    const runAt = localIso(Date.now() + Number(delay[1]) * { m: 60_000, h: 3_600_000, d: 86_400_000 }[delay[2].toLowerCase()], ZONE);
    return { kind: "once", run_at: runAt, display: `once in ${delay[1]}${delay[2]}` };
  }
  throw new Refusal(400, null, `Invalid schedule '${s}'. Use:\n  - Interval: '30m', 'every 30m', 'every 2h' (recurring)\n  - One-shot delay: 'in 30m', 'in 2h' (fires once)\n  - Cron: '0 9 * * *' (cron expression)\n  - Timestamp: '2026-02-03T14:00:00' (one-shot at time)`);
}

/** Wall time without an offset, read on tz's clock: 2026-10-27T09:00[:00] → ms, or null. */
function wallTime(text, tz) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$/.exec(text.trim());
  if (!m) return null;
  const [year, month, day, hour, minute] = m.slice(1).map(Number);
  return instantOf({ year, month, day, hour, minute }, tz);
}

function hermesNext(job, after = Date.now()) {
  const s = job.schedule;
  if (s.kind === "once") {
    const at = Date.parse(s.run_at);
    return at > after - 120_000 && !job.last_run_at ? s.run_at : null;
  }
  if (s.kind === "interval") return localIso(after + s.minutes * 60_000, ZONE);
  const at = nextCron(parseCron(s.expr), ZONE, after);
  return at === null ? null : localIso(at, ZONE);
}

function hermesView(job) {
  const state = !job.enabled ? (job.paused_at ? "paused" : "disabled") : "scheduled";
  return { ...job, schedule_display: job.schedule.display, state, next_run_at: job.enabled ? hermesNext(job) : null };
}

function hermesCreate(body) {
  const name = String(body?.name ?? "").trim();
  if (!name) throw new Refusal(400, null, "Name is required");
  if (name.length > 200) throw new Refusal(400, null, "Name must be ≤ 200 characters");
  if (!String(body?.schedule ?? "").trim()) throw new Refusal(400, null, "Schedule is required");
  const prompt = String(body?.prompt ?? "");
  if (prompt.length > 5000) throw new Refusal(400, null, "Prompt must be ≤ 5000 characters");
  if (!prompt.trim()) throw new Refusal(400, null, "A cron job needs a prompt, a skill or a script.");
  const schedule = hermesSchedule(body.schedule);
  if (schedule.kind === "once" && Date.parse(schedule.run_at) < Date.now() - 120_000) {
    throw new Refusal(400, null, `Requested one-shot time ${schedule.run_at} is more than 120s in the past and cannot be scheduled.`);
  }
  const job = {
    id: randomBytes(6).toString("hex"), name, prompt: prompt.trim(), skills: [], skill: null, schedule,
    repeat: { times: null, completed: 0 }, enabled: true, paused_at: null, paused_reason: null,
    created_at: localIso(Date.now(), ZONE), last_run_at: null, last_status: null, last_error: null,
    deliver: body.deliver ?? "local", origin: null,
  };
  jobs.set(job.id, job);
  return job;
}

function hermesUpdate(job, body) {
  // Hermes drops keys it doesn't know, such as timezone, and refuses a body with nothing left.
  const changes = Object.fromEntries(Object.entries(body ?? {}).filter(([key]) => HERMES_WHITELIST.has(key)));
  if (Object.keys(changes).length === 0) throw new Refusal(400, null, "No valid fields to update");
  if ("name" in changes && String(changes.name).length > 200) throw new Refusal(400, null, "Name must be ≤ 200 characters");
  if ("prompt" in changes && String(changes.prompt).length > 5000) throw new Refusal(400, null, "Prompt must be ≤ 5000 characters");
  const schedule = "schedule" in changes ? hermesSchedule(changes.schedule) : job.schedule;
  Object.assign(job, changes, { schedule });
  if ("enabled" in changes) job.paused_at = changes.enabled ? null : localIso(Date.now(), ZONE);
}

// ---- Clark -----------------------------------------------------------------------------------------------------

/** Clark's id for a new job: the name as a slug, `task` when nothing is left. */
function slug(name) {
  const s = String(name ?? "").normalize("NFKD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 63).replace(/-+$/, "");
  return s || "task";
}

/** The schedule as Clark stores it: a cron, or `at YYYY-MM-DDTHH:MM` on the job's clock. */
function clarkSchedule(text, tz) {
  const s = String(text ?? "").trim();
  const at = /^at\s+(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})$/.exec(s);
  if (at) return `at ${at[1]}T${at[2]}`;
  const iso = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::\d{2}(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/i.exec(s);
  if (iso) {
    if (!iso[3]) return `at ${iso[1]}T${iso[2]}`;
    const at = Date.parse(s);
    if (Number.isNaN(at)) throw new Refusal(400, "invalid_job", `${s} is not a valid date and time.`);
    const p = localParts(at, tz);
    return `at ${p.year}-${pad(p.month)}-${pad(p.day)}T${pad(p.hour)}:${pad(p.minute)}`;
  }
  if (s.split(/\s+/).length === 5) {
    try {
      parseCron(s);
      return s;
    } catch (e) {
      throw new Refusal(400, "invalid_job", `${s} is not a valid cron expression: ${e.message}.`);
    }
  }
  throw new Refusal(400, "invalid_job", "Give the schedule as a five-field cron such as 30 7 * * 1-5, as at YYYY-MM-DDTHH:MM, or as an ISO date-time.");
}

function clarkNext(job, after = Date.now()) {
  const at = /^at (\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(job.schedule);
  if (at) {
    const [year, month, day, hour, minute] = at.slice(1).map(Number);
    const ms = instantOf({ year, month, day, hour, minute }, job.timezone);
    return ms !== null && ms > after ? ms : null;
  }
  return nextCron(parseCron(job.schedule), job.timezone, after);
}

/** Refuses an empty instruction, an unknown zone and, when the schedule is new, one with no time left to run. */
function clarkCheck(job, newSchedule) {
  if (!job.prompt?.trim()) throw new Refusal(400, "invalid_job", "The task has no instruction; say what the agent should do.");
  if (!validZone(job.timezone)) throw new Refusal(400, "invalid_job", `${job.timezone} is not a time zone Clark knows.`);
  if (newSchedule && clarkNext(job) === null) throw new Refusal(400, "invalid_job", `${job.schedule} has no time left to run; pick one in the future.`);
}

function clarkDeliver(value) {
  if (value === undefined || value === null || value === "none") return null;
  if (!(value in DESTINATIONS)) throw new Refusal(400, "unknown_destination", `Clark has no destination called ${value}; pick one of ${Object.keys(DESTINATIONS).join(", ")} or none.`);
  return value;
}

function clarkView(job, full = false) {
  const display = `${job.schedule} (${job.timezone})`;
  const next = job.enabled && !job.paused ? clarkNext(job) : null;
  const view = {
    id: job.id,
    name: job.id,
    schedule: { expr: job.schedule, timezone: job.timezone, display },
    schedule_display: display,
    prompt: full ? job.prompt : job.prompt.slice(0, PROMPT_CHARS),
    enabled: job.enabled,
    paused: job.paused,
    state: job.paused ? "paused" : !job.enabled ? "disabled" : "scheduled",
    next_run_at: next === null ? null : localIso(next, job.timezone),
    last_run_at: job.last_run_at,
    last_status: job.last_status,
    editable: job.editable,
    ...(job.editable ? {} : { edit_note: HAND_WRITTEN }),
    name_editable: false,
    deliver: job.deliver,
    timezone: job.timezone,
  };
  // A Clark from before job management sent none of the editing fields.
  if (MODE === "old-clark") for (const key of ["editable", "edit_note", "name_editable", "deliver", "timezone"]) delete view[key];
  return view;
}

function clarkCreate(body) {
  const timezone = body?.timezone || ZONE;
  if (!validZone(timezone)) throw new Refusal(400, "invalid_job", `${timezone} is not a time zone Clark knows.`);
  let id = slug(body?.name);
  for (let n = 2; jobs.has(id); n++) id = `${slug(body?.name).slice(0, 60)}-${n}`;
  const job = {
    id, editable: true, enabled: true, paused: false, timezone, prompt: String(body?.prompt ?? "").trim(),
    schedule: clarkSchedule(body?.schedule, timezone), deliver: clarkDeliver(body?.deliver), last_run_at: null, last_status: null,
  };
  clarkCheck(job, true);
  jobs.set(id, job);
  return job;
}

function clarkUpdate(job, body) {
  if (!job.editable) throw new Refusal(409, "job_read_only", HAND_WRITTEN);
  if ("name" in body && slug(body.name) !== job.id) {
    throw new Refusal(409, "rename_unsupported", "Clark names a job by its id; create a new task under the new name and delete this one.");
  }
  const next = { ...job };
  if ("timezone" in body && "schedule" in body) next.timezone = body.timezone || ZONE;
  if ("schedule" in body) next.schedule = clarkSchedule(body.schedule, next.timezone);
  if ("prompt" in body) next.prompt = String(body.prompt ?? "").trim();
  if ("deliver" in body) next.deliver = clarkDeliver(body.deliver);
  clarkCheck(next, "schedule" in body);
  Object.assign(job, next);
}

// ---- Seeds and routes ------------------------------------------------------------------------------------------

/** A heartbeat every half hour by day, and a paused one-off tomorrow at 09:00. */
export function seedJobs(lang) {
  const text = TEXT[lang] ?? TEXT.nl;
  const tomorrow = localParts(Date.now() + 86_400_000, ZONE);
  const date = `${tomorrow.year}-${pad(tomorrow.month)}-${pad(tomorrow.day)}`;
  if (CLARK) {
    jobs.set("heartbeat", { id: "heartbeat", editable: false, enabled: true, paused: false, timezone: ZONE, prompt: text.heartbeat, schedule: "*/30 7-22 * * *", deliver: "telegram", last_run_at: null, last_status: null });
    const id = slug(text.dentist);
    jobs.set(id, { id, editable: true, enabled: true, paused: true, timezone: ZONE, prompt: text.dentistPrompt, schedule: `at ${date}T09:00`, deliver: null, last_run_at: null, last_status: null });
    return;
  }
  const created = localIso(Date.now(), ZONE);
  const base = { skills: [], skill: null, repeat: { times: null, completed: 0 }, created_at: created, last_run_at: null, last_status: null, last_error: null, deliver: "local", origin: null };
  jobs.set("9f2c4e1a7b30", { ...base, id: "9f2c4e1a7b30", name: "Heartbeat", prompt: text.heartbeat, schedule: { kind: "cron", expr: "*/30 7-22 * * *", display: "*/30 7-22 * * *" }, enabled: true, paused_at: null, paused_reason: null, repeat: { times: null, completed: 41 }, last_run_at: localIso(Date.now() - 20 * 60_000, ZONE), last_status: "ok" });
  const runAt = localIso(instantOf({ year: tomorrow.year, month: tomorrow.month, day: tomorrow.day, hour: 9, minute: 0 }, ZONE), ZONE);
  jobs.set("3d8a6b2f0c91", { ...base, id: "3d8a6b2f0c91", name: text.dentist, prompt: text.dentistPrompt, schedule: { kind: "once", run_at: runAt, display: `once at ${date} 09:00` }, enabled: false, paused_at: created, paused_reason: null });
}

/** What /v1/capabilities adds: nothing for stock Hermes, Clark's job endpoints otherwise. */
export function jobEndpoints() {
  if (!CLARK) return {};
  return {
    jobs: { method: "GET", path: "/api/jobs" },
    job_action: { method: "POST", path: "/api/jobs/{job_id}/{action}" },
    ...(MODE === "clark"
      ? {
          job: { method: "GET", path: "/api/jobs/{job_id}" },
          job_create: { method: "POST", path: "/api/jobs" },
          job_update: { method: "PATCH", path: "/api/jobs/{job_id}" },
          job_delete: { method: "DELETE", path: "/api/jobs/{job_id}" },
        }
      : {}),
  };
}

/** Answers /api/jobs and /api/jobs/{id}[/{action}]; false for any other path. */
export async function handleJobs(req, res, parts, url, send, readBody) {
  if (parts[0] !== "api" || parts[1] !== "jobs" || parts.length > 4) return false;
  const reply = (status, body) => {
    send(res, status, body);
    return true;
  };
  const fail = (status, code, message) => reply(status, CLARK ? { error: { code: code ?? "invalid_request", message } } : { error: message });
  const id = parts[2] === undefined ? undefined : decodeURIComponent(parts[2]);
  const view = (job, full) => (CLARK ? clarkView(job, full) : hermesView(job));
  try {
    if (id === undefined) {
      if (req.method === "GET") {
        const all = url.searchParams.get("include_disabled") === "true" || url.searchParams.get("include_disabled") === "1";
        const list = [...jobs.values()].filter((job) => all || job.enabled).map((job) => view(job, false));
        const targets = Object.entries(DESTINATIONS).map(([name, d]) => ({ id: name, label: d.description || name }));
        return reply(200, { jobs: list, ...(MODE === "clark" ? { delivery_targets: targets } : {}) });
      }
      if (req.method === "POST" && MODE !== "old-clark") {
        const body = await readBody(req);
        if (body === null) return fail(400, "invalid_json", CLARK ? "The body is not JSON." : "Invalid JSON");
        const job = CLARK ? clarkCreate(body) : hermesCreate(body);
        return reply(CLARK ? 201 : 200, { job: view(job, true) });
      }
      return fail(MODE === "old-clark" ? 404 : 405, "not_found", "No such endpoint.");
    }
    if (!CLARK && !/^[a-f0-9]{12}$/.test(id)) return fail(400, null, "Invalid job ID format");
    if (MODE === "old-clark" && parts.length === 3) return fail(404, "not_found", "No such endpoint.");
    const job = jobs.get(id);
    if (!job) return fail(404, "job_not_found", CLARK ? `Clark has no job ${id}.` : "Job not found");
    if (parts.length === 3) {
      if (req.method === "GET") return reply(200, { job: view(job, true) });
      if (req.method === "PATCH") {
        const body = await readBody(req);
        if (body === null) return fail(400, "invalid_json", CLARK ? "The body is not JSON." : "Invalid JSON");
        if (CLARK) clarkUpdate(job, body);
        else hermesUpdate(job, body);
        return reply(200, { job: view(job, true) });
      }
      if (req.method === "DELETE") {
        if (CLARK && !job.editable) throw new Refusal(409, "job_read_only", HAND_WRITTEN);
        jobs.delete(id);
        return reply(200, { ok: true });
      }
      return fail(405, "method_not_allowed", "That method isn't allowed here.");
    }
    if (req.method !== "POST") return fail(405, "method_not_allowed", "That method isn't allowed here.");
    await readBody(req);
    const now = localIso(Date.now(), CLARK ? job.timezone : ZONE);
    switch (parts[3]) {
      case "pause":
        if (CLARK) job.paused = true;
        else Object.assign(job, { enabled: false, paused_at: now });
        break;
      case "resume":
        if (CLARK) {
          if (!job.enabled) throw new Refusal(409, "job_disabled", `${id} is disabled in its job file; enable it there to run it again.`);
          job.paused = false;
        } else {
          Object.assign(job, { enabled: true, paused_at: null });
        }
        break;
      case "run":
        Object.assign(job, { last_run_at: now, last_status: "ok" });
        if (!CLARK) job.repeat.completed++;
        return reply(200, CLARK ? { ok: true, occurrence: `${id}@${now}`, job: view(job, false) } : { job: view(job, true) });
      default:
        return fail(404, "not_found", "No such endpoint.");
    }
    return reply(200, { job: view(job, CLARK ? false : true) });
  } catch (e) {
    if (e instanceof Refusal) return fail(e.status, e.code, e.message);
    throw e;
  }
}
