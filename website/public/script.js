// Loaded in <head> without defer, so this class is set before the first paint.
document.documentElement.classList.add("js");

const RELEASES = "https://github.com/MajesteitBart/dudan/releases/latest";
const RELEASE_API = "https://api.github.com/repos/MajesteitBart/dudan/releases/latest";
const onAndroid = /Android/i.test(navigator.userAgent);
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
const wide = matchMedia("(min-width: 960px)");

// Find the APK in the latest GitHub release, so the button downloads it directly.
async function latestApk() {
  const cached = sessionStorage.getItem("dudan-apk");
  if (cached) return JSON.parse(cached);
  const res = await fetch(RELEASE_API, { headers: { Accept: "application/vnd.github+json" } });
  if (!res.ok) throw new Error(`GitHub answered ${res.status}`);
  const release = await res.json();
  const asset = release.assets.find((a) => a.name.endsWith(".apk"));
  if (!asset) throw new Error("No APK in the latest release");
  const apk = {
    url: asset.browser_download_url,
    version: release.tag_name.replace(/^android-v/, ""),
    megabytes: Math.round(asset.size / 1e6),
  };
  sessionStorage.setItem("dudan-apk", JSON.stringify(apk));
  return apk;
}

function setUpDownloads() {
  // On a phone, every download button goes straight to the file. Elsewhere they lead to
  // the setup section, which has a QR code for the phone.
  if (onAndroid) document.querySelectorAll("[data-apk]").forEach((a) => (a.href = RELEASES));
  latestApk()
    .then((apk) => {
      document.querySelectorAll("[data-apk-direct]").forEach((a) => (a.href = apk.url));
      if (onAndroid) document.querySelectorAll("[data-apk]").forEach((a) => (a.href = apk.url));
      document.querySelectorAll("[data-apk-meta]").forEach((el) => {
        el.textContent = `Version ${apk.version}, ${apk.megabytes} MB. Android 12 or later, 64‑bit ARM.`;
      });
    })
    .catch(() => {
      // The links already point at the releases page, which always has the APK.
    });
}

function setUpReveals() {
  const items = document.querySelectorAll("[data-reveal]");
  if (!("IntersectionObserver" in window)) {
    items.forEach((el) => el.classList.add("is-in"));
    return;
  }
  const io = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add("is-in");
        io.unobserve(entry.target);
      }
    },
    { rootMargin: "0px 0px -8% 0px", threshold: 0.12 },
  );
  items.forEach((el) => io.observe(el));
}

// The story's phone. On wide screens one frame in the rail holds every screen and shows
// the one for the chapter in the middle of the viewport. On narrow screens the screens
// move into the inline frames of their chapters. Videos are only attached when the file
// exists, and they only play while their screen is active and in view.
function setUpStory() {
  const rail = document.getElementById("screens");
  if (!rail) return;
  const screens = [...rail.querySelectorAll(".screen")];
  const slots = new Map([...document.querySelectorAll("[data-slot]")].map((el) => [el.dataset.slot, el]));
  const chapters = [...document.querySelectorAll("[data-chapter]")];
  const videos = [...rail.querySelectorAll("video[data-src]")];
  const probes = new Map();
  const inView = new WeakSet();
  let active = "voice";

  function place() {
    for (const screen of screens) {
      const slot = slots.get(screen.dataset.screen);
      if (wide.matches || !slot) {
        rail.append(screen);
        if (slot) slot.classList.remove("has-screen");
      } else {
        slot.querySelector(".screens").append(screen);
        slot.classList.add("has-screen");
      }
    }
    syncVideos();
  }

  function setActive(name) {
    if (name === active) return;
    active = name;
    for (const screen of screens) screen.classList.toggle("is-active", screen.dataset.screen === name);
    syncVideos();
  }

  // One HEAD request per file, and only once its phone is near the viewport. A missing
  // file leaves the poster in place.
  function available(url) {
    if (!probes.has(url)) {
      probes.set(
        url,
        fetch(url, { method: "HEAD" })
          .then((res) => res.ok && /^video\//.test(res.headers.get("content-type") || ""))
          .catch(() => false),
      );
    }
    return probes.get(url);
  }

  async function syncVideo(video) {
    const screen = video.closest(".screen");
    const phone = video.closest(".phone");
    const wanted = !reducedMotion && inView.has(phone) && (phone.classList.contains("phone-inline") || screen.classList.contains("is-active"));
    if (!wanted) {
      if (!video.paused) video.pause();
      return;
    }
    if (!video.getAttribute("src")) {
      if (!(await available(video.dataset.src))) return;
      if (!inView.has(phone)) return;
      video.src = video.dataset.src;
    }
    video.play().catch(() => {
      // Autoplay refused or the file failed to decode: the poster stays.
    });
  }

  function syncVideos() {
    videos.forEach(syncVideo);
  }

  if (!("IntersectionObserver" in window)) {
    place();
    return;
  }

  // Which chapter sits in the middle of the viewport.
  const centre = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) if (entry.isIntersecting) setActive(entry.target.dataset.chapter);
    },
    { rootMargin: "-45% 0px -45% 0px", threshold: 0 },
  );
  chapters.forEach((el) => centre.observe(el));

  // Which phones are on screen, for playing and pausing.
  const visible = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) inView.add(entry.target);
        else inView.delete(entry.target);
      }
      syncVideos();
    },
    { rootMargin: "80px 0px", threshold: 0.15 },
  );
  document.querySelectorAll(".phone").forEach((el) => visible.observe(el));

  wide.addEventListener("change", place);
  place();
}

// The footer wordmark loads its animated version once it scrolls into view, so the
// animation plays while someone is looking at it.
function setUpWordmark() {
  const img = document.querySelector(".reveal");
  if (!img) return;
  const show = () => img.classList.add("is-playing");
  if (reducedMotion || !("IntersectionObserver" in window)) return show();
  const io = new IntersectionObserver(
    ([entry]) => {
      if (!entry.isIntersecting) return;
      io.disconnect();
      const fallback = img.src;
      img.addEventListener("load", show, { once: true });
      img.addEventListener("error", () => ((img.src = fallback), show()), { once: true });
      img.src = img.dataset.src;
    },
    { threshold: 0.6 },
  );
  io.observe(img);
}

document.addEventListener("DOMContentLoaded", () => {
  setUpDownloads();
  setUpReveals();
  setUpStory();
  setUpWordmark();
});
