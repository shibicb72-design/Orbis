// Video diagnostics (Settings > Video diagnostics). Loaded only while the log is recording; see DiagExtension.kt.
// It does nothing until the Orbis app answers "record". Then it only LISTENS to video and audio elements and reads
// their state. It never calls anything that changes an element or the page, and it sends no address, title or text:
// a media source leaves this script as its kind only, and as "same site" or "other site". Messages go to the Orbis
// app only. Media inside shadow roots is not seen.
(() => {
  "use strict";

  const SAMPLE_MILLIS = 10000;
  const EVENTS = [
    "loadstart",
    "loadedmetadata",
    "playing",
    "waiting",
    "stalled",
    "pause",
    "ended",
    "error",
    "emptied",
    "seeking",
    "resize",
  ];
  const isTop = window === window.top;

  let port;
  try {
    port = browser.runtime.connectNative("orbisDiag");
  } catch (e) {
    return;
  }

  let started = false;
  let stopped = false;
  let sampleTimer = 0;
  let nextId = 1;
  const info = new WeakMap();
  const live = new Set();

  function send(message) {
    if (stopped) return;
    try {
      port.postMessage(message);
    } catch (e) {
      stop();
    }
  }

  function stop() {
    stopped = true;
    if (sampleTimer) clearInterval(sampleTimer);
    for (const name of EVENTS) document.removeEventListener(name, onMediaEvent, true);
    document.removeEventListener("visibilitychange", onVisibility, true);
    window.removeEventListener("online", onOnline, true);
    window.removeEventListener("offline", onOffline, true);
  }

  function round1(n) {
    return Number.isFinite(n) ? Math.round(n * 10) / 10 : 0;
  }

  function hostOf(address) {
    try {
      return new URL(address, location.href).hostname.toLowerCase();
    } catch (e) {
      return "";
    }
  }

  function lastTwo(host) {
    return host.split(".").slice(-2).join(".");
  }

  // Only the yes/no answer leaves this script.
  function sameSite(address) {
    if (address.startsWith("blob:")) return true;
    const a = hostOf(address);
    const b = location.hostname.toLowerCase();
    if (!a || !b) return false;
    return a === b || a.endsWith("." + b) || b.endsWith("." + a) || lastTwo(a) === lastTwo(b);
  }

  function sourceOf(el) {
    return el.currentSrc || el.src || "";
  }

  function kindOf(address) {
    if (!address) return "none";
    if (address.startsWith("blob:")) return "blob";
    if (/\.m3u8([?#]|$)/i.test(address)) return "hls";
    if (/^https?:/i.test(address)) return "file";
    return "other";
  }

  function aheadOf(el) {
    const ranges = el.buffered;
    const now = el.currentTime;
    for (let i = 0; i < ranges.length; i++) {
      if (ranges.start(i) <= now + 0.05 && now <= ranges.end(i)) return round1(ranges.end(i) - now);
    }
    return 0;
  }

  function describe(el, entry) {
    const address = sourceOf(el);
    entry.address = address;
    send({
      type: "media",
      id: entry.id,
      kind: el instanceof HTMLVideoElement ? "video" : "audio",
      source: kindOf(address),
      same: address ? sameSite(address) : true,
      frame: !isTop,
    });
  }

  function register(el) {
    let entry = info.get(el);
    if (entry) return entry;
    if (nextId > 10000) return null;
    entry = { id: nextId++, t0: performance.now(), address: "", emptied: false, stalled: false };
    info.set(el, entry);
    live.add(new WeakRef(el));
    describe(el, entry);
    return entry;
  }

  function state(el, entry, name) {
    return {
      type: "event",
      id: entry.id,
      name: name,
      t: round1((performance.now() - entry.t0) / 1000),
      ready: el.readyState,
      network: el.networkState,
      pos: round1(el.currentTime),
      ahead: aheadOf(el),
      finite: Number.isFinite(el.duration),
    };
  }

  function onMediaEvent(event) {
    const el = event.target;
    if (!(el instanceof HTMLMediaElement)) return;
    const entry = register(el);
    if (!entry) return;
    const name = event.type;
    const message = state(el, entry, name);
    if (name === "loadstart") {
      const address = sourceOf(el);
      // A restart of the same source (for example by another script) shows as emptied, then loadstart.
      message.again = entry.emptied && address === entry.address;
      if (address !== entry.address) describe(el, entry);
      entry.emptied = false;
    } else if (name === "emptied") {
      entry.emptied = true;
    } else if (name === "error") {
      message.code = el.error ? el.error.code : 0;
    } else if (name === "resize") {
      message.w = el.videoWidth || 0;
      message.h = el.videoHeight || 0;
    }
    if (name === "waiting") entry.stalled = true;
    if (name === "playing" || name === "pause" || name === "ended" || name === "emptied") entry.stalled = false;
    send(message);
  }

  function sample() {
    for (const ref of live) {
      const el = ref.deref();
      if (!el) {
        live.delete(ref);
        continue;
      }
      const entry = info.get(el);
      if (!entry) continue;
      const playing = !el.paused && !el.ended;
      if (!playing && !entry.stalled) continue;
      const message = { type: "sample", id: entry.id, ahead: aheadOf(el), w: 0, h: 0, frames: 0, dropped: 0 };
      if (el instanceof HTMLVideoElement) {
        message.w = el.videoWidth || 0;
        message.h = el.videoHeight || 0;
        const quality = el.getVideoPlaybackQuality();
        message.frames = quality.totalVideoFrames;
        message.dropped = quality.droppedVideoFrames;
      }
      send(message);
    }
  }

  function onVisibility() {
    send({ type: "page", what: document.visibilityState === "hidden" ? "hidden" : "visible" });
  }

  function onOnline() {
    send({ type: "page", what: "online" });
  }

  function onOffline() {
    send({ type: "page", what: "offline" });
  }

  function start() {
    for (const name of EVENTS) document.addEventListener(name, onMediaEvent, true);
    if (isTop) {
      document.addEventListener("visibilitychange", onVisibility, true);
      window.addEventListener("online", onOnline, true);
      window.addEventListener("offline", onOffline, true);
      // Asked of an element this script makes and never adds to the page.
      const probe = document.createElement("video");
      send({
        type: "hls",
        mpegurl: probe.canPlayType("application/vnd.apple.mpegurl"),
        xmpegurl: probe.canPlayType("application/x-mpegURL"),
      });
    }
    // One first look for elements that were already there (recording switched on with the page open).
    for (const el of document.querySelectorAll("video, audio")) {
      const entry = register(el);
      if (entry) send(state(el, entry, "found"));
    }
    sampleTimer = setInterval(sample, SAMPLE_MILLIS);
  }

  port.onMessage.addListener((message) => {
    if (started || stopped || !message || message.type !== "record") return;
    started = true;
    start();
  });
  port.onDisconnect.addListener(stop);
  send({ type: "hello" });
})();
