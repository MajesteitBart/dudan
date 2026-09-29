// Builds the signed release APK and prepares a GitHub release in artifacts/android-v<version>/:
// dudan-release-arm64-v8a-v<version>-<commit>.apk, SHA256SUMS and notes.md, the same layout as
// Ownkey Keyboard's releases. It prints the gh command that publishes them.
// Usage: node tools/release-apk.mjs
// Needs a clean checkout, the Android SDK, signing.properties and the keystore it names.

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const windows = process.platform === "win32";

function fail(message) {
  console.error(message);
  process.exit(1);
}

// Windows can only start .bat files through the shell, which doesn't quote arguments itself.
function run(command, args, options = {}) {
  const shell = windows && command.endsWith(".bat");
  const quote = (a) => (shell && /[\s"]/.test(a) ? `"${a}"` : a);
  return execFileSync(quote(command), args.map(quote), { cwd: root, encoding: "utf8", shell, ...options });
}

if (!existsSync(join(root, "signing.properties"))) fail("signing.properties is missing, so the APK would be signed with the debug key.");
if (run("git", ["status", "--porcelain"]).trim()) fail("Commit or stash your changes first: the file name records the commit.");

const gradle = readFileSync(join(root, "app/build.gradle.kts"), "utf8");
const field = (name, pattern) => gradle.match(pattern)?.[1] ?? fail(`Couldn't read ${name} from app/build.gradle.kts.`);
const version = field("versionName", /versionName = "([^"]+)"/);
const versionCode = field("versionCode", /versionCode = (\d+)/);
const packageName = field("applicationId", /applicationId = "([^"]+)"/);
const minSdk = Number(field("minSdk", /minSdk = (\d+)/));
const commit = run("git", ["rev-parse", "HEAD"]).trim();

run(join(root, windows ? "gradlew.bat" : "gradlew"), ["--console=plain", "clean", "testDebugUnitTest", "assembleRelease"], { stdio: "inherit" });

const tag = `android-v${version}`;
const outDir = join(root, "artifacts", tag);
mkdirSync(outDir, { recursive: true });
const apkName = `dudan-release-arm64-v8a-v${version}-${commit.slice(0, 7)}.apk`;
const apk = join(outDir, apkName);
copyFileSync(join(root, "app/build/outputs/apk/release/app-release.apk"), apk);
writeFileSync(join(outDir, "SHA256SUMS"), `${createHash("sha256").update(readFileSync(apk)).digest("hex")}  ${apkName}\n`);

// The certificate goes in the notes, so people can check that an update comes from the same key.
const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT ??
  readFileSync(join(root, "local.properties"), "utf8").match(/^sdk\.dir=(.*)$/m)?.[1].replace(/\\([:\\])/g, "$1");
if (!sdk) fail("Set ANDROID_HOME or sdk.dir in local.properties so apksigner can be found.");
const newest = readdirSync(join(sdk, "build-tools"))
  .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
  .at(-1);
const certs = run(join(sdk, "build-tools", newest, windows ? "apksigner.bat" : "apksigner"), ["verify", "--print-certs", apk]);
if (/CN=Android Debug/.test(certs)) fail("The APK is signed with the debug key. Check signing.properties.");
const cert = certs.match(/certificate SHA-256 digest: ([0-9a-f]+)/)?.[1] ?? fail("apksigner printed no certificate digest.");

const androidVersion = { 31: "12", 32: "12L", 33: "13", 34: "14", 35: "15", 36: "16" }[minSdk] ?? `API ${minSdk}`;
writeFileSync(join(outDir, "notes.md"), `Production-signed Android APK for dudan ${version}.

- Package: \`${packageName}\`
- Version code: \`${versionCode}\`
- Build: release variant with R8/resource shrinking
- Commit: \`${commit}\`
- Signing certificate SHA-256: \`${cert}\`
- APK SHA-256: see \`SHA256SUMS\`

The APK is for 64-bit ARM phones (\`arm64-v8a\`) with Android ${androidVersion} (API ${minSdk}) or newer. On first use the app downloads its speech models on Wi-Fi; see [Model credits](https://github.com/MajesteitBart/dudan/blob/main/docs/reference.md#model-credits) for their licenses.

## What's new

-
`);

console.log(`
Prepared ${outDir}
  ${apkName}
  SHA256SUMS
  notes.md (fill in "What's new")

Publish with:
  gh release create ${tag} "artifacts/${tag}/${apkName}" "artifacts/${tag}/SHA256SUMS" --title "dudan v${version}" --notes-file "artifacts/${tag}/notes.md" --target ${commit}
`);
