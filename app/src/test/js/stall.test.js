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
    { src: "https://cdn.example/clip.mp4", paused: false, ended: false, online: true, error: false, readyState: 2, currentTime: 30, duration: 120 },
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

// ---- The script in a page (fake DOM, fake clock) -----------------------------------------------------------------

function page({ online = true } = {}) {
  const docListeners = {};
  const winListeners = {};
  const elements = [];
  let clock = 0;
  let intervals = [];
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
    WeakMap,
    Set,
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
