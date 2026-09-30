// Builds the website's static assets from the sources in the repository:
// screenshots, fonts, brand marks, icons, the download QR code and the share image.
// Run from the repository root: npm ci --prefix website/tools && npm run --prefix website/tools generate
import { execFileSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import QRCode from "qrcode";
import sharp from "sharp";
import subsetFont from "subset-font";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../..");
const pub = resolve(here, "../public");
const out = (...p) => {
  const file = join(pub, ...p);
  mkdirSync(dirname(file), { recursive: true });
  return file;
};

const RELEASES = "https://github.com/MajesteitBart/dudan/releases/latest";

// Screenshots: WebP at full resolution; they show at about half size on a 2x screen.
const shots = join(repo, "docs/screenshots");
for (const name of ["overlay", "rich-reply", "foldable", "approval", "files"]) {
  await sharp(join(shots, `${name}.jpg`)).webp({ quality: 82 }).toFile(out("assets/screens", `${name}.webp`));
}
// Crop for the spec sheet: the part of the model picker that shows the settings.
for (const [name, top, height] of [["model-picker", 328, 970]]) {
  await sharp(join(shots, `${name}.jpg`))
    .extract({ left: 0, top, width: 720, height })
    .webp({ quality: 82 })
    .toFile(out("assets/screens", `${name}-crop.webp`));
}

// Fonts: the app's Google Sans Flex and Code, cut down to Latin text and punctuation.
const ranges = [[0x20, 0x7e], [0xa0, 0xff], [0x2010, 0x2027], [0x2030, 0x203a], [0x20ac, 0x20ac], [0x2192, 0x2192], [0x2212, 0x2212]];
const glyphs = ranges.flatMap(([a, b]) => Array.from({ length: b - a + 1 }, (_, i) => String.fromCodePoint(a + i))).join("");
const fonts = join(repo, "app/src/main/res/font");
for (const [src, dst] of [
  ["google_sans_flex_400.ttf", "google-sans-flex-400.woff2"],
  ["google_sans_flex_500.ttf", "google-sans-flex-500.woff2"],
  ["google_sans_flex_600.ttf", "google-sans-flex-600.woff2"],
  ["google_sans_code_400.ttf", "google-sans-code-400.woff2"],
]) {
  writeFileSync(out("fonts", dst), await subsetFont(readFileSync(join(fonts, src)), glyphs, { targetFormat: "woff2" }));
}
copyFileSync(join(repo, "licenses/google-sans-flex-OFL.txt"), out("fonts/google-sans-flex-OFL.txt"));
copyFileSync(join(repo, "licenses/google-sans-code-OFL.txt"), out("fonts/google-sans-code-OFL.txt"));

// Brand marks: the supplied artwork without its dark backdrop, so it sits on the page's sky.
const assets = join(repo, "assets");
const noBackdrop = (svg) => svg.replace(/\s*<rect width="1100" height="460" fill="url\(#bg\)"\/>/, "");
const wordmarkFull = noBackdrop(readFileSync(join(assets, "dudan-wordmark.svg"), "utf8"));
// Same frame as the reveal, shown in its place before the animation starts or without JavaScript.
writeFileSync(out("assets/wordmark-full.svg"), wordmarkFull);
// Cropped to the letters and without the floor light, for the navigation bar.
writeFileSync(
  out("assets/wordmark.svg"),
  wordmarkFull
    .replace(/\s*<ellipse [^>]*url\(#floor\)[^>]*\/>/g, "")
    .replace('viewBox="0 0 1100 460" width="1100" height="460"', 'viewBox="130 70 842 305" width="842" height="305"'),
);
writeFileSync(out("assets/reveal.svg"), noBackdrop(readFileSync(join(assets, "dudan-reveal.svg"), "utf8")));
copyFileSync(join(assets, "dudan-app-icon.svg"), out("favicon.svg"));
await sharp(join(assets, "dudan-app-icon.png")).resize(180, 180).png().toFile(out("apple-touch-icon.png"));
await sharp(join(assets, "dudan-app-icon.png")).resize(32, 32).png().toFile(out("favicon-32.png"));

// QR code for desktop visitors: scan it with the phone to reach the latest release.
// Drawn here instead of with the library's SVG output: modules with rounded corners that join
// up, rounded finder squares and the app icon in the middle. Error correction level H covers
// the icon. The modules run from deep blue to violet, still dark enough for any scanner.
function qrSvg(text, iconSvg) {
  const { modules } = QRCode.create(text, { errorCorrectionLevel: "H" });
  const n = modules.size;
  const quiet = 3;
  const size = n + quiet * 2;
  const logo = Math.round(n * 0.22) | 1;
  const plate = logo + 2;
  const p0 = (n - plate) / 2;
  const isFinder = (x, y) => (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7);
  const isPlate = (x, y) => x >= p0 && x < p0 + plate && y >= p0 && y < p0 + plate;
  const dark = (x, y) => x >= 0 && y >= 0 && x < n && y < n && !isFinder(x, y) && !isPlate(x, y) && modules.get(y, x);

  const num = (v) => +v.toFixed(2);
  // A rounded rectangle as a path. Radii run clockwise from the top left.
  const box = (x, y, w, h, [a, b, c, d]) => {
    const arc = (r, dx, dy) => (r ? `a${r} ${r} 0 0 1 ${dx} ${dy}` : "");
    return `M${num(x + a)} ${num(y)}h${num(w - a - b)}${arc(b, b, b)}v${num(h - b - c)}${arc(c, -c, c)}h${num(-(w - c - d))}${arc(d, -d, -d)}v${num(-(h - d - a))}${arc(a, a, -a)}z`;
  };

  // A corner is round when neither module beside it is dark, so neighbours merge into one shape.
  const round = (p, q) => (p || q ? 0 : 0.5);
  let cells = "";
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (!dark(x, y)) continue;
      const [up, right, down, left] = [dark(x, y - 1), dark(x + 1, y), dark(x, y + 1), dark(x - 1, y)];
      cells += box(x + quiet, y + quiet, 1, 1, [round(up, left), round(up, right), round(down, right), round(down, left)]);
    }
  }

  // Finder squares: a ring with a rounded dot in it (drawn with the even-odd rule).
  let eyes = "";
  for (const [ex, ey] of [[0, 0], [n - 7, 0], [0, n - 7]]) {
    const x = ex + quiet;
    const y = ey + quiet;
    eyes += box(x, y, 7, 7, [2.2, 2.2, 2.2, 2.2]) + box(x + 1, y + 1, 5, 5, [1.4, 1.4, 1.4, 1.4]) + box(x + 2, y + 2, 3, 3, [1, 1, 1, 1]);
  }

  // The tile of the app icon, without the empty margin around it.
  const icon = Buffer.from(iconSvg.replace('viewBox="0 0 512 512" width="512" height="512"', 'viewBox="32 32 448 448" width="448" height="448"')).toString("base64");
  const at = p0 + 1 + quiet;
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 ${size} ${size}" width="${size * 10}" height="${size * 10}" role="img" aria-label="QR code for the latest dudan release on GitHub">`,
    `<defs><linearGradient id="ink" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="${size}" y2="${size}"><stop offset="0" stop-color="#14206b"/><stop offset="0.55" stop-color="#372aa0"/><stop offset="1" stop-color="#5a2bb0"/></linearGradient></defs>`,
    `<rect width="${size}" height="${size}" rx="3.2" fill="#ffffff"/>`,
    `<path fill="url(#ink)" d="${cells}"/>`,
    `<path fill="url(#ink)" fill-rule="evenodd" d="${eyes}"/>`,
    `<image xlink:href="data:image/svg+xml;base64,${icon}" x="${at}" y="${at}" width="${logo}" height="${logo}"/>`,
    `</svg>`,
  ].join("\n");
}
writeFileSync(out("assets/qr-download.svg"), qrSvg(RELEASES, readFileSync(join(assets, "dudan-app-icon.svg"), "utf8")));

// Icons: Phosphor (MIT) as an inline sprite between the markers in each page. Regular weight,
// except names ending in -fill.
const icons = {
  download: "download-simple", github: "github-logo", android: "android-logo", server: "hard-drives", lock: "lock-key",
  telegram: "telegram-logo", timer: "timer", caret: "caret-down", play: "play-fill", close: "x",
};
const phosphor = join(here, "node_modules/@phosphor-icons/core/assets");
const symbols = Object.entries(icons).map(([id, file]) => {
  const weight = file.endsWith("-fill") ? "fill" : "regular";
  const paths = readFileSync(join(phosphor, weight, `${file}.svg`), "utf8").replace(/^<svg[^>]*>|<\/svg>\s*$/g, "");
  return `  <symbol id="i-${id}" viewBox="0 0 256 256">${paths}</symbol>`;
});
for (const name of ["index.html", "get-started/index.html"]) {
  const page = join(pub, name);
  writeFileSync(
    page,
    readFileSync(page, "utf8").replace(
      /<!-- icons:start -->[\s\S]*<!-- icons:end -->/,
      `<!-- icons:start -->\n<!-- Phosphor Icons (MIT). Generated by website/tools/generate.mjs. -->\n<svg class="sprite" aria-hidden="true">\n${symbols.join("\n")}\n</svg>\n<!-- icons:end -->`,
    ),
  );
}

// Share image: og/og.html rendered at 1200x630 by headless Chrome.
const chrome =
  process.env.CHROME ??
  ["C:/Program Files/Google/Chrome/Application/chrome.exe", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "/usr/bin/google-chrome"].find(existsSync);
if (chrome) {
  execFileSync(chrome, [
    "--headless=new",
    "--disable-gpu",
    "--hide-scrollbars",
    "--force-device-scale-factor=1",
    "--window-size=1200,630",
    `--screenshot=${out("og-image.png")}`,
    pathToFileURL(resolve(here, "../og/og.html")).href,
  ], { stdio: "ignore" });
} else {
  console.warn("Chrome not found; set CHROME to render og-image.png");
}

console.log("Website assets written to", pub);
