// Serves the built APKs in artifacts/ so a phone on the tailnet can download and install them.
// Usage: node tools/serve-apk.mjs <bind-address> <port>
import http from "node:http";
import { createReadStream, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";

const host = process.argv[2] ?? "127.0.0.1";
const port = Number(process.argv[3] ?? 8765);
const dir = resolve(new URL("../artifacts", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));

const apks = () => readdirSync(dir).filter((f) => f.endsWith(".apk")).sort().reverse();

http.createServer((req, res) => {
  const name = decodeURIComponent(new URL(req.url, "http://x").pathname.slice(1));
  if (name === "" || name === "index.html") {
    const links = apks()
      .map((f) => `<li><a href="/${encodeURIComponent(f)}">${f}</a> (${(statSync(join(dir, f)).size / 1e6).toFixed(1)} MB)</li>`)
      .join("");
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(`<!doctype html><meta name=viewport content="width=device-width"><body style="font-family:sans-serif;background:#000;color:#eee;padding:24px"><h2>dudan</h2><ul>${links}</ul></body>`);
    return;
  }
  if (!apks().includes(name)) {
    res.writeHead(404).end("not found");
    return;
  }
  const file = join(dir, name);
  res.writeHead(200, {
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Length": statSync(file).size,
    "Content-Disposition": `attachment; filename="${name}"`,
  });
  createReadStream(file).pipe(res);
}).listen(port, host, () => console.log(`serving ${dir} on http://${host}:${port}`));
