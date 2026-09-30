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
for (const [name, top, height] of [["model-picker", 470, 970]]) {
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
writeFileSync(
  out("assets/qr-download.svg"),
  await QRCode.toString(RELEASES, { type: "svg", margin: 1, errorCorrectionLevel: "M", color: { dark: "#141736", light: "#ffffff" } }),
);

// Icons: Phosphor (MIT) as an inline sprite between the markers in index.html. Regular weight,
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
const page = join(pub, "index.html");
writeFileSync(
  page,
  readFileSync(page, "utf8").replace(
    /<!-- icons:start -->[\s\S]*<!-- icons:end -->/,
    `<!-- icons:start -->\n<!-- Phosphor Icons (MIT). Generated by website/tools/generate.mjs. -->\n<svg class="sprite" aria-hidden="true">\n${symbols.join("\n")}\n</svg>\n<!-- icons:end -->`,
  ),
);

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
