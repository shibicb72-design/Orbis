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


// ---- The reconnect window: a live playlist killed by the engine's HLS player -------------------------------------

const PLAYLIST = "https://cdn.example/live/index.m3u8?token=1";

function live(over) {
  return media(Object.assign({ src: PLAYLIST, duration: Infinity, live: true, readyState: 1, connected: true }, over));
}

function died(over) {
  return live(Object.assign({ error: true, errorCode: 2 }, over));
}

function opened(now) {
  const t = S.newTrack();
  assert.strictEqual(S.onError(t, died(), now || 0), "open");
  return t;
}

/**
 * Looks once a second from `from` to `to` (ms). `each(now)` may return a snapshot override, or call S.onError /
 * S.onDownload / S.onMetadata itself. Returns every verdict that is not "wait", with its time.
 */
function drive(t, from, to, each) {
  const out = [];
  for (let now = from; now <= to; now += 1000) {
    const over = (each && each(now)) || {};
    const v = S.winStep(t, live(over), now);
    if (v !== "wait") out.push(v + "@" + now);
    if (!t.win) break;
  }
  return out;
}

test("window: the first reload comes 3 s after the error", () => {
  const t = opened(0);
  assert.deepStrictEqual(drive(t, 1000, 3000), ["load@3000"]);
});

test("window: the next attempt comes 1 s after a failure, never while one is in flight", () => {
  const t = opened(0);
  // The attempt keeps getting data, so it is in flight (not hung) all the time: no second load.
  const got = drive(t, 1000, 20000, now => {
    if (now > 3000) S.onDownload(t, now - 500, true);
  });
  assert.deepStrictEqual(got, ["load@3000"]);
  // Now it fails (any code; after load() the engine says 4 for the same dead stream).
  assert.strictEqual(S.onError(t, died({ errorCode: 4 }), 20000), "failed");
  assert.deepStrictEqual(drive(t, 20500, 20500), []);
  assert.deepStrictEqual(drive(t, 21000, 21000), ["load@21000"]);
});

test("window: a hung attempt (6 s without metadata or progress) counts as failed", () => {
  const t = opened(0);
  assert.deepStrictEqual(drive(t, 1000, 10000), ["load@3000", "load@10000"]);
});

test("window: code 4 with no window open starts nothing; code 2 does", () => {
  const t = S.newTrack();
  assert.strictEqual(S.onError(t, died({ errorCode: 4 }), 0), "none");
  assert.strictEqual(S.onError(t, died({ errorCode: 3 }), 0), "none");
  assert.strictEqual(t.win, null);
  assert.strictEqual(S.onError(t, died(), 0), "open");
});

function playFor(t, seconds, start) {
  // Loaded at start + 500, then currentTime moves one second per second.
  S.onMetadata(t, start + 500, true);
  return drive(t, start + 1000, start + 1000 + seconds * 1000, now => ({ currentTime: 300 + (now - start - 1000) / 1000 }));
}

test("window: 10 s of playing closes the window, 9 s does not", () => {
  let t = opened(0);
  drive(t, 1000, 3000);
  assert.deepStrictEqual(playFor(t, 9, 3000), []);
  assert.ok(t.win, "9 s: still in the window");
  t = opened(0);
  drive(t, 1000, 3000);
  assert.deepStrictEqual(playFor(t, 10, 3000), ["back@14000"]);
  assert.strictEqual(t.win, null);
  assert.strictEqual(t.lastSignal, null, "back without a drop: no signal");
  // A later error opens a fresh window.
  assert.strictEqual(S.onError(t, died(), 20000), "open");
  assert.strictEqual(t.win.elapsed, 0);
});

test("window: playing less than 10 s and dying again stays in the same window", () => {
  const t = opened(0);
  drive(t, 1000, 3000);
  playFor(t, 5, 3000);
  assert.strictEqual(S.onError(t, died(), 9500), "failed");
  assert.strictEqual(t.win.elapsed, 9500, "the 30 s keep running");
  assert.deepStrictEqual(drive(t, 10000, 10500), []);
  assert.deepStrictEqual(drive(t, 11000, 11000), ["load@11000"]);
});

/** Every attempt fails half a second after it starts. */
function failEveryAttempt(t, from, to) {
  const loads = [];
  const other = [];
  for (let now = from; now <= to; now += 1000) {
    const v = S.winStep(t, live(), now);
    if (v === "load") {
      loads.push(now);
      S.onError(t, died({ errorCode: 4 }), now + 500);
    } else if (v !== "wait") other.push(v + "@" + now);
    if (!t.win) break;
  }
  return { loads, other };
}

test("window: at 30 s no new attempt starts, and \"dropped\" comes exactly once", () => {
  const t = opened(0);
  const { loads, other } = failEveryAttempt(t, 1000, 90000);
  assert.strictEqual(loads[0], 3000);
  assert.ok(loads.length > 3, "the limit of 3 does not apply to error restarts");
  assert.ok(loads.every(n => n < 30000), "no attempt at or after 30 s: " + loads);
  for (let i = 1; i < loads.length; i++) assert.ok(loads[i] - loads[i - 1] >= 1500, "1 s after the failure, not faster");
  assert.deepStrictEqual(other, ["dropped@30000"]);
  // Nothing more after that: no second "dropped", no attempt.
  for (let now = 31000; now <= 90000; now += 1000) assert.strictEqual(S.winStep(t, live(), now), "stop");
  assert.strictEqual(t.dropped, true);
  assert.strictEqual(t.lastSignal, "dropped");
});

test("window: an attempt in flight at 30 s may finish; \"dropped\" comes after it fails", () => {
  const t = opened(0);
  // Fails until 28 s; the attempt that starts at 29 s keeps getting data until 32.5 s.
  const first = failEveryAttempt(t, 1000, 27000);
  assert.ok(first.loads.length > 0);
  assert.deepStrictEqual(drive(t, 28000, 29000), ["load@29000"]);
  assert.deepStrictEqual(
    drive(t, 30000, 32000, now => {
      S.onDownload(t, now - 100, true);
    }),
    [],
  );
  S.onError(t, died({ errorCode: 4 }), 32500);
  assert.deepStrictEqual(drive(t, 33000, 60000), ["dropped@33000"]);
});

test("window: the user pausing, the source replaced or the element removed stop it without a signal", () => {
  for (const over of [{ paused: true }, { src: "https://cdn.example/other/index.m3u8" }, { connected: false }, { ended: true }]) {
    const t = opened(0);
    drive(t, 1000, 3000);
    assert.strictEqual(S.winStep(t, live(over), 4000), "stop", JSON.stringify(over));
    assert.strictEqual(t.win, null);
    assert.strictEqual(t.dropped, false);
    assert.strictEqual(t.lastSignal, null);
  }
  // No longer a live playlist: the reload came back with a finite duration.
  const t = opened(0);
  drive(t, 1000, 3000);
  S.onMetadata(t, 3500, true);
  assert.strictEqual(S.winStep(t, live({ duration: 600 }), 4000), "stop");
  assert.strictEqual(t.lastSignal, null);
});

test("window: offline holds its clock and its attempts", () => {
  const t = opened(0);
  assert.deepStrictEqual(drive(t, 1000, 60000, () => ({ online: false })), []);
  assert.strictEqual(t.win.elapsed, 0);
  // Online again at 61 s: window time goes on from 0, so the first attempt comes at 63 s.
  assert.deepStrictEqual(drive(t, 61000, 63000), ["load@63000"]);
});

test("window: after giving up, online or play opens one fresh window; back raises \"playing\" once", () => {
  const t = opened(0);
  failEveryAttempt(t, 1000, 31000);
  assert.strictEqual(t.dropped, true);
  assert.strictEqual(S.onError(t, died(), 40000), "none", "a dead element's errors open nothing by themselves");
  assert.strictEqual(S.reopen(t, died(), 50000), true);
  assert.strictEqual(S.reopen(t, died(), 50000), false, "one window, not two");
  assert.deepStrictEqual(drive(t, 51000, 53000), ["load@53000"]);
  assert.deepStrictEqual(playFor(t, 10, 53000), ["playing@64000"]);
  assert.strictEqual(t.lastSignal, "playing");
  // The next death and recovery raise no second "playing".
  assert.strictEqual(S.onError(t, died(), 70000), "open");
  drive(t, 71000, 73000);
  assert.deepStrictEqual(playFor(t, 10, 73000), ["back@84000"]);
  // reopen does nothing when nothing was given up.
  assert.strictEqual(S.reopen(S.newTrack(), died(), 0), false);
});

test("window: a plain file, a playlist that is not live, a blob: source never open one", () => {
  for (const over of [
    { src: "https://cdn.example/clip.mp4", live: false, duration: 120 },
    { src: "https://radio.example/stream.mp3" },
    { live: false, duration: 600 },
    { src: "https://cdn.example/play?f=a.m3u8" },
    { src: "blob:https://site.example/abc.m3u8" },
    { paused: true },
    { ended: true },
  ]) {
    const t = S.newTrack();
    assert.strictEqual(S.onError(t, died(over), 0), "none", JSON.stringify(over));
    assert.strictEqual(t.win, null);
  }
  assert.strictEqual(S.isLivePlaylist("https://cdn.example/a/INDEX.M3U8#t=1", true), true);
});

test("window: error restarts are not held to the stall limit, and stalls stay quiet while a window is open", () => {
  const { t } = run(media({ src: PLAYLIST, duration: Infinity }), 12000 + 24000 + 48000);
  assert.strictEqual(t.tries, 3);
  assert.strictEqual(S.onError(t, died(), 0), "open");
  const t2 = opened(0);
  assert.strictEqual(S.arm(t2, live({ error: false }), 0), false);
});

test("the stream signal hook takes one value and does nothing yet", () => {
  assert.strictEqual(typeof S.streamSignal, "function");
  assert.strictEqual(S.streamSignal.length, 1);
  assert.strictEqual(S.streamSignal("dropped"), undefined);
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
  const signals = [];
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
    signals,
    start: () => {
      vm.runInNewContext(source, context);
      // Watch the hook the app's message will use (brief live12-8): every call, with all its arguments.
      context.OrbisStall.streamSignal = function () {
        signals.push(Array.prototype.slice.call(arguments));
      };
    },
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

/** The reload worked: metadata, then playing at the live edge for `seconds`. */
function comesBack(p, el, seconds) {
  el.duration = Infinity;
  el.readyState = 4;
  el.currentTime = 500;
  el.fireOwn("loadedmetadata");
  for (let i = 0; i < seconds; i++) {
    el.currentTime += 1;
    p.advance(1000);
  }
}

test("in a page: a live playlist killed by the HLS player is reloaded 3 s later, at the live edge; 10 s of play closes it", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  p.hlsError(el);
  p.advance(2000);
  assert.deepStrictEqual(p.calls, []);
  p.advance(1000);
  assert.deepStrictEqual(p.calls, ["load"]);
  comesBack(p, el, 11);
  assert.deepStrictEqual(p.calls, ["load", "play"]);
  assert.ok(el.currentTime > 500, "no seek back");
  assert.strictEqual(p.timers(), 0, "window closed");
  assert.deepStrictEqual(p.signals, []);
});

test("in a page: every reload fails: attempts one after another, never two at once, none after 30 s, then \"dropped\" once", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  p.hlsError(el);
  const loadTimes = [];
  for (let s = 1; s <= 120; s++) {
    const before = p.calls.length;
    p.advance(1000);
    if (p.calls.length > before) {
      loadTimes.push(s);
      // The reload of the dead stream errors with code 4 half way to the next look.
      el.error = { code: 4 };
      p.fire(el, "error");
    }
  }
  assert.strictEqual(loadTimes[0], 3);
  assert.ok(loadTimes.length > 3);
  assert.ok(loadTimes.every(s => s < 30), String(loadTimes));
  assert.deepStrictEqual(p.signals, [["dropped"]]);
  assert.strictEqual(p.timers(), 0);
});

test("in a page: an attempt that hangs is given up after 6 s and the next follows 1 s later", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  p.hlsError(el);
  p.advance(3000);
  assert.strictEqual(p.calls.length, 1);
  p.advance(5000);
  assert.strictEqual(p.calls.length, 1, "in flight: no second load");
  p.advance(2000);
  assert.strictEqual(p.calls.length, 2);
});

test("in a page: progress keeps an attempt in flight", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  p.hlsError(el);
  p.advance(3000);
  for (let i = 0; i < 20; i++) {
    p.advance(1000);
    p.fire(el, "progress");
  }
  assert.strictEqual(p.calls.length, 1);
});

function dropIt(p, el) {
  p.hlsError(el);
  for (let s = 1; s <= 40; s++) {
    const before = p.calls.length;
    p.advance(1000);
    if (p.calls.length > before) {
      el.error = { code: 4 };
      p.fire(el, "error");
    }
  }
  assert.deepStrictEqual(p.signals, [["dropped"]]);
}

test("in a page: after \"dropped\", coming back online opens one fresh window; back raises \"playing\"", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  dropIt(p, el);
  const n = p.calls.length;
  p.advance(60000);
  assert.strictEqual(p.calls.length, n, "nothing while given up");
  p.goOnline();
  p.advance(3000);
  assert.strictEqual(p.calls.length, n + 1);
  comesBack(p, el, 11);
  assert.deepStrictEqual(p.signals, [["dropped"], ["playing"]]);
});

test("in a page: after \"dropped\", the user pressing play opens one fresh window", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  dropIt(p, el);
  const loads = () => p.calls.filter(c => c === "load").length;
  const n = loads();
  el.play();
  p.advance(2000);
  assert.strictEqual(loads(), n);
  p.advance(1000);
  assert.strictEqual(loads(), n + 1);
});

test("in a page: the user pausing or the element being removed ends the window without a signal", () => {
  for (const end of ["pause", "remove"]) {
    const p = page();
    p.start();
    const el = livePlaylist(p);
    p.hlsError(el);
    p.advance(3000);
    el.fireOwn("loadedmetadata");
    if (end === "pause") el.paused = true;
    else el.isConnected = false;
    p.advance(60000);
    assert.deepStrictEqual(p.calls, ["load", "play"], end);
    assert.deepStrictEqual(p.signals, [], end);
    assert.strictEqual(p.timers(), 0, end);
  }
});

test("in a page: the page replaced the source: the window ends, nothing more", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  p.hlsError(el);
  p.advance(1000);
  el.currentSrc = "https://cdn.example/other/index.m3u8";
  p.advance(60000);
  assert.deepStrictEqual(p.calls, []);
  assert.deepStrictEqual(p.signals, []);
});

test("in a page: a plain file with the same error, or code 4 alone, opens nothing", () => {
  const p = page();
  p.start();
  const file = p.element();
  p.hlsError(file);
  const pl = livePlaylist(p);
  pl.error = { code: 4 };
  p.fire(pl, "error");
  p.advance(120000);
  assert.deepStrictEqual(p.calls, []);
});

test("in a page: offline holds the window; attempts go on once online", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  p.goOffline();
  p.hlsError(el);
  p.advance(60000);
  assert.deepStrictEqual(p.calls, []);
  assert.deepStrictEqual(p.signals, []);
  p.goOnline();
  p.advance(3000);
  assert.deepStrictEqual(p.calls, ["load"]);
});

test("in a page: live is remembered through a reload (duration NaN while loading)", () => {
  const p = page();
  p.start();
  const el = livePlaylist(p);
  el.duration = NaN;
  p.fire(el, "durationchange");
  p.hlsError(el);
  p.advance(3000);
  assert.deepStrictEqual(p.calls, ["load"]);
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
