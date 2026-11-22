"use strict";

const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const dir = path.join(__dirname, "../../main/assets/extensions/stall");
const source = fs.readFileSync(path.join(dir, "content.js"), "utf8");

function test(name, fn) {
  try {
    fn();
    console.log("ok " + name);
  } catch (e) {
    console.error("FAIL " + name);
    console.error(e && e.stack ? e.stack : e);
    process.exitCode = 1;
  }
}

// ---- The pure decision -------------------------------------------------------------------------------------------

const S = vm.runInNewContext(source + "\nOrbisStall;", {});
// Objects made inside the context have its prototypes; compare plain copies.
const plain = o => JSON.parse(JSON.stringify(o));

function media(over) {
  return Object.assign(
    {
      src: "https://cdn.example/clip.mp4",
      paused: false,
      ended: false,
      online: true,
      error: false,
      readyState: 2,
      currentTime: 30,
      duration: 120,
      bufferedEnd: 35,
    },
    over,
  );
}

/** Arms at t=0 and steps once a second until `untilMs`; returns the times (ms) at which it said "act". */
function run(s, untilMs, track) {
  const t = track || S.newTrack();
  const acts = [];
  S.arm(t, s, 0);
  for (let now = 1000; now <= untilMs; now += 1000) {
    if (!t.armed) S.arm(t, s, now);
    if (S.step(t, s, now) === "act") acts.push(now);
  }
  return { t, acts };
}

test("a blob: source is never restarted", () => {
  const s = media({ src: "blob:https://site.example/1f2e" });
  const t = S.newTrack();
  assert.strictEqual(S.arm(t, s, 0), false);
  assert.deepStrictEqual(run(s, 200000).acts, []);
  assert.strictEqual(S.eligible(s), false);
  for (const src of ["", "data:video/mp4;base64,AAAA", "mediasource:x", "file:///sdcard/a.mp4"]) {
    assert.strictEqual(S.eligible(media({ src })), false, src);
  }
  assert.strictEqual(S.eligible(media({ src: "http://cdn.example/a.mp4" })), true);
  assert.strictEqual(S.eligible(media({ src: "HTTPS://cdn.example/live.m3u8" })), true);
});

test("a paused element is never restarted", () => {
  assert.deepStrictEqual(run(media({ paused: true }), 200000).acts, []);
});

test("an ended element is never restarted", () => {
  assert.deepStrictEqual(run(media({ ended: true }), 200000).acts, []);
});

test("offline: never restarted", () => {
  assert.deepStrictEqual(run(media({ online: false }), 200000).acts, []);
});

test("an element that reports an error is never restarted (no retry against a server answering errors)", () => {
  assert.deepStrictEqual(run(media({ error: true }), 200000).acts, []);
});

test("an 11 s stall: no restart; 12 s: restart", () => {
  const t = S.newTrack();
  const s = media();
  assert.strictEqual(S.arm(t, s, 0), true);
  assert.strictEqual(S.step(t, s, 11000), "wait");
  assert.strictEqual(S.step(t, s, 11999), "wait");
  assert.strictEqual(S.step(t, s, 12000), "act");
  assert.strictEqual(t.tries, 1);
});

test("the second restart needs 24 s, the third 48 s, and there is no fourth", () => {
  const { t, acts } = run(media(), 400000);
  assert.deepStrictEqual(acts, [12000, 12000 + 24000, 12000 + 24000 + 48000]);
  assert.strictEqual(t.tries, 3);
  // Once the three are used up, a new stall does not even start the clock.
  assert.strictEqual(S.arm(t, media(), 500000), false);
  assert.strictEqual(S.step(t, media(), 900000), "stop");
});

test("the second restart is not made after 23 s", () => {
  const t = S.newTrack();
  const s = media();
  S.arm(t, s, 0);
  assert.strictEqual(S.step(t, s, 12000), "act");
  assert.strictEqual(S.step(t, s, 12000 + 23000), "wait");
  assert.strictEqual(S.step(t, s, 12000 + 24000), "act");
});

test("time moving resets the clock", () => {
  const t = S.newTrack();
  S.arm(t, media({ currentTime: 30 }), 0);
  assert.strictEqual(S.step(t, media({ currentTime: 30 }), 10000), "wait");
  assert.strictEqual(S.step(t, media({ currentTime: 30.2 }), 11000), "stop");
  assert.strictEqual(t.armed, false);
  // A new stall starts a new 12 s clock.
  S.arm(t, media({ currentTime: 30.2 }), 11000);
  assert.strictEqual(S.step(t, media({ currentTime: 30.2 }), 22000), "wait");
  assert.strictEqual(S.step(t, media({ currentTime: 30.2 }), 23000), "act");
});

test("enough data (readyState 3 or more) ends the stall", () => {
  const t = S.newTrack();
  assert.strictEqual(S.arm(t, media({ readyState: 3 }), 0), false);
  S.arm(t, media({ readyState: 2 }), 0);
  assert.strictEqual(S.step(t, media({ readyState: 4 }), 5000), "stop");
});

test("a pause, going offline or an error during the stall breaks the clock", () => {
  for (const over of [{ paused: true }, { online: false }, { error: true }, { ended: true }, { src: "blob:x" }]) {
    const t = S.newTrack();
    S.arm(t, media(), 0);
    assert.strictEqual(S.step(t, media(), 6000), "wait");
    assert.strictEqual(S.step(t, media(over), 7000), "stop", JSON.stringify(over));
    S.arm(t, media(), 8000);
    assert.strictEqual(S.step(t, media(), 19000), "wait", JSON.stringify(over));
    assert.strictEqual(S.step(t, media(), 20000), "act", JSON.stringify(over));
  }
});

test("a repeated stalled event does not restart the clock", () => {
  const t = S.newTrack();
  S.arm(t, media(), 0);
  S.arm(t, media(), 9000);
  assert.strictEqual(S.step(t, media(), 12000), "act");
});

test("after a restart the new position is the reference, not a sign of progress", () => {
  const t = S.newTrack();
  S.arm(t, media({ currentTime: 30 }), 0);
  assert.strictEqual(S.step(t, media({ currentTime: 30 }), 12000), "act");
  // load() put the position at 0 and readyState at 0.
  assert.strictEqual(S.step(t, media({ currentTime: 0, readyState: 0 }), 13000), "wait");
  assert.strictEqual(S.step(t, media({ currentTime: 0, readyState: 0 }), 36000), "act");
});

test("a slow download: buffered data growing for 60 s while playback is stuck is not restarted", () => {
  const t = S.newTrack();
  S.arm(t, media({ bufferedEnd: 35 }), 0);
  for (let now = 1000; now <= 60000; now += 1000) {
    assert.strictEqual(S.step(t, media({ bufferedEnd: 35 + now / 100000 }), now), "wait", "at " + now);
  }
  // Then the bytes stop: a true silence restarts 12 s after the last byte.
  const last = 35 + 60000 / 100000;
  assert.strictEqual(S.step(t, media({ bufferedEnd: last }), 71000), "wait");
  assert.strictEqual(S.step(t, media({ bufferedEnd: last }), 72000), "act");
});

test("a slow download: progress events for 60 s while playback is stuck are not restarted", () => {
  const t = S.newTrack();
  S.arm(t, media(), 0);
  for (let now = 1000; now <= 60000; now += 1000) {
    S.downloaded(t, now - 500);
    assert.strictEqual(S.step(t, media(), now), "wait", "at " + now);
  }
  assert.strictEqual(S.step(t, media(), 71000), "wait");
  assert.strictEqual(S.step(t, media(), 71500), "act");
});

test("a true silence (no progress, buffered end still) restarts at 12 s", () => {
  const t = S.newTrack();
  S.arm(t, media({ bufferedEnd: 35 }), 0);
  for (let now = 1000; now < 12000; now += 1000) assert.strictEqual(S.step(t, media({ bufferedEnd: 35 }), now), "wait");
  assert.strictEqual(S.step(t, media({ bufferedEnd: 35 }), 12000), "act");
});

test("progress on an element that is not stalled starts no clock", () => {
  const t = S.newTrack();
  S.downloaded(t, 5000);
  assert.strictEqual(t.armed, false);
  assert.strictEqual(t.since, null);
});

test("a file is sought back to where it stalled; a live stream is not", () => {
  assert.deepStrictEqual(plain(S.restartPlan(media({ currentTime: 42.5, duration: 120 }))), { src: "https://cdn.example/clip.mp4", seekTo: 42.5 });
  const live = S.restartPlan(media({ src: "https://cdn.example/live.m3u8", currentTime: 42.5, duration: Infinity }));
  assert.strictEqual(live.seekTo, null);
  assert.strictEqual(S.restartPlan(media({ currentTime: 0 })).seekTo, null);
  assert.strictEqual(S.restartPlan(media({ duration: NaN })).seekTo, null);
});

test("a source the page replaced meanwhile is left alone", () => {
  const plan = { src: "https://cdn.example/clip.mp4", seekTo: 42.5 };
  assert.deepStrictEqual(plain(S.afterMetadata(plan, "https://cdn.example/other.mp4")), { seekTo: null, play: false });
  assert.deepStrictEqual(plain(S.afterMetadata(plan, "https://cdn.example/clip.mp4")), { seekTo: 42.5, play: true });
});

// ---- Errors of the engine's HLS player on a live playlist --------------------------------------------------------

const PLAYLIST = "https://cdn.example/live/index.m3u8?token=1";

function hlsDied(over) {
  return media(Object.assign({ src: PLAYLIST, duration: Infinity, live: true, error: true, errorCode: 2, readyState: 1 }, over));
}

test("a live playlist that dies with the HLS player's error while playing is restarted 3 s later", () => {
  const t = S.newTrack();
  assert.strictEqual(S.errorDelayMs(t, hlsDied()), 3000);
  assert.strictEqual(S.errorDue(t, { src: PLAYLIST }, hlsDied()), "act");
  assert.strictEqual(t.tries, 1);
  // The next error restarts come 12 s and 24 s after their errors.
  assert.strictEqual(S.errorDelayMs(t, hlsDied()), 12000);
  S.errorDue(t, { src: PLAYLIST }, hlsDied());
  assert.strictEqual(S.errorDelayMs(t, hlsDied()), 24000);
});

test("a plain file with the same error code is never restarted", () => {
  const t = S.newTrack();
  assert.strictEqual(S.errorDelayMs(t, hlsDied({ src: "https://cdn.example/clip.mp4", live: false, duration: 120 })), null);
  // An endless http audio stream (internet radio) is live, but a plain file too.
  assert.strictEqual(S.errorDelayMs(t, hlsDied({ src: "https://radio.example/stream.mp3" })), null);
  // A playlist that is not live (a recording) is not restarted on an error either.
  assert.strictEqual(S.errorDelayMs(t, hlsDied({ live: false, duration: 600 })), null);
  // An address that only mentions m3u8 in its query is not a playlist path.
  assert.strictEqual(S.errorDelayMs(t, hlsDied({ src: "https://cdn.example/play?f=a.m3u8" })), null);
  assert.strictEqual(S.isLivePlaylist("https://cdn.example/a/INDEX.M3U8#t=1", true), true);
});

test("a blob: playlist, another error code, paused or ended before the error: never", () => {
  const t = S.newTrack();
  assert.strictEqual(S.errorDelayMs(t, hlsDied({ src: "blob:https://site.example/abc.m3u8" })), null);
  assert.strictEqual(S.errorDelayMs(t, hlsDied({ errorCode: 3 })), null, "decode error");
  assert.strictEqual(S.errorDelayMs(t, hlsDied({ errorCode: 4 })), null, "could not load at all");
  assert.strictEqual(S.errorDelayMs(t, hlsDied({ paused: true })), null);
  assert.strictEqual(S.errorDelayMs(t, hlsDied({ ended: true })), null);
  assert.strictEqual(t.tries, 0);
});

test("offline when the restart is due: not until online", () => {
  const t = S.newTrack();
  assert.strictEqual(S.errorDue(t, { src: PLAYLIST }, hlsDied({ online: false })), "offline");
  assert.strictEqual(t.tries, 0);
  assert.strictEqual(S.errorDue(t, { src: PLAYLIST }, hlsDied()), "act");
});

test("the page replaced the source or cleared the error meanwhile, or the user paused: nothing", () => {
  const t = S.newTrack();
  assert.strictEqual(S.errorDue(t, { src: PLAYLIST }, hlsDied({ src: "https://cdn.example/other.m3u8" })), "drop");
  assert.strictEqual(S.errorDue(t, { src: PLAYLIST }, hlsDied({ error: false, errorCode: 0 })), "drop");
  assert.strictEqual(S.errorDue(t, { src: PLAYLIST }, hlsDied({ paused: true })), "drop");
  assert.strictEqual(t.tries, 0);
});

test("the limit is shared: two stall restarts and one error restart, then nothing", () => {
  const { t, acts } = run(media({ src: PLAYLIST, duration: Infinity }), 12000 + 24000);
  assert.deepStrictEqual(acts, [12000, 36000]);
  assert.strictEqual(S.errorDelayMs(t, hlsDied()), 24000);
  assert.strictEqual(S.errorDue(t, { src: PLAYLIST }, hlsDied()), "act");
  assert.strictEqual(S.errorDelayMs(t, hlsDied()), null);
  assert.strictEqual(S.errorDue(t, { src: PLAYLIST }, hlsDied()), "drop");
  assert.strictEqual(S.arm(t, media({ src: PLAYLIST }), 0), false);
});

// ---- The script in a page (fake DOM, fake clock) -----------------------------------------------------------------

function page({ online = true } = {}) {
  const docListeners = {};
  const winListeners = {};
  const elements = [];
  let clock = 0;
  let intervals = [];
  let timeouts = [];
  const context = {
    document: {
      addEventListener: (type, fn, capture) => {
        assert.strictEqual(capture, true, "media events do not bubble: capture is needed");
        (docListeners[type] = docListeners[type] || []).push(fn);
      },
      querySelectorAll: () => elements.filter(e => e.isConnected),
    },
    window: { addEventListener: (type, fn) => (winListeners[type] = winListeners[type] || []).push(fn) },
    navigator: { onLine: online },
    performance: { now: () => clock },
    setInterval: fn => {
      const id = { fn };
      intervals.push(id);
      return id;
    },
    clearInterval: id => {
      intervals = intervals.filter(i => i !== id);
    },
    setTimeout: (fn, ms) => {
      const id = { fn, due: clock + ms };
      timeouts.push(id);
      return id;
    },
    WeakMap,
    Set,
    Map,
    Math,
    isFinite,
  };
  const calls = [];
  function element(over) {
    const listeners = {};
    const el = Object.assign(
      {
        localName: "video",
        currentSrc: "https://cdn.example/clip.mp4",
        paused: false,
        ended: false,
        error: null,
        readyState: 2,
        currentTime: 30,
        duration: 120,
        bufferedTo: 35,
        get buffered() {
          const end = this.bufferedTo;
          return { length: 1, start: () => 0, end: () => end };
        },
        isConnected: true,
        addEventListener: (type, fn) => (listeners[type] = listeners[type] || []).push(fn),
        removeEventListener: (type, fn) => {
          listeners[type] = (listeners[type] || []).filter(f => f !== fn);
        },
        load() {
          calls.push("load");
          this.paused = true;
          this.currentTime = 0;
          this.readyState = 0;
          this.error = null;
        },
        play() {
          calls.push("play");
          this.paused = false;
          fire(this, "play");
          return Promise.resolve();
        },
        fireOwn(type) {
          (listeners[type] || []).slice().forEach(fn => fn({ target: el }));
        },
      },
      over,
    );
    elements.push(el);
    return el;
  }
  function fire(el, type) {
    (docListeners[type] || []).forEach(fn => fn({ target: el }));
  }
  function advance(ms) {
    for (let i = 0; i < ms / 1000; i++) {
      clock += 1000;
      const due = timeouts.filter(id => id.due <= clock);
      timeouts = timeouts.filter(id => id.due > clock);
      due.forEach(id => id.fn());
      intervals.slice().forEach(id => id.fn());
    }
  }
  return {
    element,
    fire,
    advance,
    calls,
    start: () => vm.runInNewContext(source, context),
    timers: () => intervals.length,
    goOnline: () => {
      context.navigator.onLine = true;
      (winListeners.online || []).forEach(fn => fn({}));
    },
    goOffline: () => {
      context.navigator.onLine = false;
    },
    /** The engine's HLS player gives up: the element reports MEDIA_ERR_NETWORK and fires error. */
    hlsError: el => {
      el.error = { code: 2 };
      fire(el, "error");
    },
  };
}

test("in a page: a stalled file is reloaded after 12 s, sought back and played; the timer stops after", () => {
  const p = page();
  p.start();
  const el = p.element();
  assert.strictEqual(p.timers(), 0, "no timer while nothing stalls");
  p.fire(el, "waiting");
  assert.strictEqual(p.timers(), 1);
  p.advance(11000);
  assert.deepStrictEqual(p.calls, []);
  p.advance(1000);
  assert.deepStrictEqual(p.calls, ["load"]);
  el.readyState = 1;
  el.fireOwn("loadedmetadata");
  assert.strictEqual(el.currentTime, 30);
  assert.deepStrictEqual(p.calls, ["load", "play"]);
  el.readyState = 4;
  el.currentTime = 31;
  p.advance(1000);
  assert.strictEqual(p.timers(), 0, "playing again: the timer is gone");
});

test("in a page: while its own reload hangs the element still counts as playing, up to three tries", () => {
  const p = page();
  p.start();
  const el = p.element();
  p.fire(el, "stalled");
  p.advance(12000 + 24000 + 48000 + 200000);
  assert.deepStrictEqual(p.calls, ["load", "load", "load"]);
  assert.strictEqual(p.timers(), 0);
  p.fire(el, "waiting");
  p.advance(200000);
  assert.deepStrictEqual(p.calls, ["load", "load", "load"]);
});

test("in a page: progress events for 60 s while stuck: no restart; then 12 s of silence: restart", () => {
  const p = page();
  p.start();
  const el = p.element();
  p.fire(el, "waiting");
  for (let i = 0; i < 60; i++) {
    p.advance(1000);
    p.fire(el, "progress");
  }
  assert.deepStrictEqual(p.calls, []);
  p.advance(11000);
  assert.deepStrictEqual(p.calls, []);
  p.advance(1000);
  assert.deepStrictEqual(p.calls, ["load"]);
});

test("in a page: the buffered end moving for 60 s while stuck (no progress event seen): no restart", () => {
  const p = page();
  p.start();
  const el = p.element();
  p.fire(el, "stalled");
  for (let i = 0; i < 60; i++) {
    el.bufferedTo += 0.01;
    p.advance(1000);
  }
  assert.deepStrictEqual(p.calls, []);
  p.advance(12000);
  assert.deepStrictEqual(p.calls, ["load"]);
});

test("in a page: a live stream is played again without a seek", () => {
  const p = page();
  p.start();
  const el = p.element({ currentSrc: "https://cdn.example/live.m3u8", duration: Infinity, currentTime: 300 });
  p.fire(el, "waiting");
  p.advance(12000);
  el.fireOwn("loadedmetadata");
  assert.strictEqual(el.currentTime, 0);
  assert.deepStrictEqual(p.calls, ["load", "play"]);
});

test("in a page: a source replaced before the metadata arrives is not played or sought", () => {
  const p = page();
  p.start();
  const el = p.element();
  p.fire(el, "waiting");
  p.advance(12000);
  el.currentSrc = "https://cdn.example/next.mp4";
  el.fireOwn("loadedmetadata");
  assert.deepStrictEqual(p.calls, ["load"]);
  assert.strictEqual(el.currentTime, 0);
});

test("in a page: Media Source, audio and offline", () => {
  const p = page({ online: false });
  p.start();
  const mse = p.element({ currentSrc: "blob:https://site.example/9a" });
  const audio = p.element({ localName: "audio" });
  p.fire(mse, "waiting");
  p.fire(audio, "waiting");
  p.advance(60000);
  assert.deepStrictEqual(p.calls, [], "offline: nothing");
  assert.strictEqual(p.timers(), 0);
  // Back online: elements already stalled are found again; the blob: one is not.
  p.goOnline();
  p.advance(12000);
  assert.deepStrictEqual(p.calls, ["load"]);
});

test("in a page: an element stalled before the script ran is found at start; other elements are ignored", () => {
  const p = page();
  const el = p.element();
  p.start();
  p.fire({ localName: "img" }, "stalled");
  p.advance(12000);
  assert.deepStrictEqual(p.calls, ["load"]);
  assert.strictEqual(el.paused, true, "between load() and the metadata, paused is this script's own doing");
});

function livePlaylist(p) {
  const el = p.element({ currentSrc: PLAYLIST, duration: Infinity, currentTime: 300 });
  p.fire(el, "durationchange");
  return el;
}

test("in a page: a live playlist killed by the HLS player is reloaded 3 s later and played at the live edge", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  p.hlsError(el);
  p.advance(2000);
  assert.deepStrictEqual(p.calls, []);
  p.advance(1000);
  assert.deepStrictEqual(p.calls, ["load"]);
  el.currentTime = 0;
  el.fireOwn("loadedmetadata");
  assert.deepStrictEqual(p.calls, ["load", "play"]);
  assert.strictEqual(el.currentTime, 0, "no seek back on a live stream");
});

test("in a page: the live flag survives the error even if the duration is gone", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  el.duration = NaN;
  p.hlsError(el);
  p.advance(3000);
  assert.deepStrictEqual(p.calls, ["load"]);
});

test("in a page: a plain file with the same error is never restarted", () => {
  const p = page();
  p.start();
  const el = p.element();
  p.hlsError(el);
  p.advance(120000);
  assert.deepStrictEqual(p.calls, []);
});

test("in a page: offline when due, restarted 3 s after the device is online again", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  p.goOffline();
  p.hlsError(el);
  p.advance(60000);
  assert.deepStrictEqual(p.calls, []);
  p.goOnline();
  p.advance(2000);
  assert.deepStrictEqual(p.calls, []);
  p.advance(1000);
  assert.deepStrictEqual(p.calls, ["load"]);
});

test("in a page: the page replaced the source before the restart was due: nothing", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  p.hlsError(el);
  p.advance(1000);
  el.currentSrc = "https://cdn.example/other/index.m3u8";
  p.advance(5000);
  assert.deepStrictEqual(p.calls, []);
});

test("in a page: paused before the error: never", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  el.paused = true;
  p.hlsError(el);
  p.advance(60000);
  assert.deepStrictEqual(p.calls, []);
});

test("in a page: two stall restarts, one error restart, then nothing more", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  p.fire(el, "waiting");
  p.advance(12000 + 24000);
  assert.deepStrictEqual(p.calls, ["load", "load"]);
  // The second reload ends in the HLS player's error.
  p.hlsError(el);
  p.advance(24000);
  assert.deepStrictEqual(p.calls, ["load", "load", "load"]);
  p.hlsError(el);
  p.fire(el, "waiting");
  p.advance(300000);
  assert.deepStrictEqual(p.calls, ["load", "load", "load"]);
});

// ---- The manifest ------------------------------------------------------------------------------------------------

test("the manifest: one content script in every frame at document_idle, no permissions, no background", () => {
  const m = JSON.parse(fs.readFileSync(path.join(dir, "manifest.json"), "utf8"));
  assert.deepStrictEqual(Object.keys(m).sort(), ["browser_specific_settings", "content_scripts", "description", "manifest_version", "name", "version"]);
  assert.strictEqual(m.manifest_version, 2);
  assert.strictEqual(m.browser_specific_settings.gecko.id, "stall@orbis.shibi.com");
  assert.deepStrictEqual(m.content_scripts, [
    { matches: ["http://*/*", "https://*/*"], js: ["content.js"], run_at: "document_idle", all_frames: true },
  ]);
  assert.strictEqual(m.permissions, undefined);
  assert.strictEqual(m.background, undefined);
  assert.ok(!JSON.stringify(m).includes("webRequest"));
  assert.deepStrictEqual(fs.readdirSync(dir).sort(), ["content.js", "manifest.json"]);
});

test("the script sends, stores and logs nothing", () => {
  for (const word of ["runtime", "sendMessage", "connectNative", "storage", "fetch(", "XMLHttpRequest", "console.", "localStorage", "chrome."]) {
    assert.ok(!source.includes(word), word);
  }
});
