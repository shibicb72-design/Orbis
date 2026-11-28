"use strict";

// Video diagnostics page script (assets/extensions/diag/content.js): it must do nothing until the app answers
// "record" (ruling K1 b), and what it sends must hold no address. Run: node app/src/test/js/diag.test.js

const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const source = fs.readFileSync(path.join(__dirname, "../../main/assets/extensions/diag/content.js"), "utf8");

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

class HTMLMediaElement {
  constructor(props) {
    Object.assign(
      this,
      {
        currentSrc: "",
        src: "",
        readyState: 4,
        networkState: 2,
        currentTime: 12.34,
        duration: 100,
        paused: false,
        ended: false,
        error: null,
        buffered: { length: 1, start: () => 10, end: () => 20 },
        calls: [],
      },
      props,
    );
  }
}
class HTMLVideoElement extends HTMLMediaElement {
  getVideoPlaybackQuality() {
    this.calls.push("getVideoPlaybackQuality");
    return { totalVideoFrames: 100, droppedVideoFrames: 2 };
  }
}

function load({ top = true, existing = [] } = {}) {
  const sent = [];
  const portListeners = [];
  const docListeners = {};
  const winListeners = {};
  const created = [];
  let queried = 0;
  let intervals = 0;
  const port = {
    postMessage: (m) => sent.push(JSON.parse(JSON.stringify(m))),
    onMessage: { addListener: (f) => portListeners.push(f) },
    onDisconnect: { addListener: () => {} },
  };
  const document = {
    visibilityState: "visible",
    addEventListener: (name, f) => (docListeners[name] = f),
    removeEventListener: () => {},
    querySelectorAll: () => {
      queried++;
      return existing;
    },
    createElement: (tag) => {
      const el = { tag, canPlayType: (t) => (t === "application/vnd.apple.mpegurl" ? "maybe" : "") };
      created.push(el);
      return el;
    },
  };
  const win = {
    addEventListener: (name, f) => (winListeners[name] = f),
    removeEventListener: () => {},
  };
  win.top = top ? win : {};
  const context = vm.createContext({
    browser: { runtime: { connectNative: () => port } },
    document,
    window: win,
    location: { href: "https://www.example.com/watch?v=secret", hostname: "www.example.com" },
    HTMLMediaElement,
    HTMLVideoElement,
    URL,
    WeakRef,
    performance,
    setInterval: () => ++intervals,
    clearInterval: () => {},
  });
  vm.runInContext(source, context);
  return {
    sent,
    docListeners,
    winListeners,
    created,
    queried: () => queried,
    intervals: () => intervals,
    answer: (m) => portListeners.forEach((f) => f(m)),
  };
}

test("nothing but hello before the app answers record", () => {
  const s = load({ existing: [new HTMLVideoElement({})] });
  assert.deepStrictEqual(s.sent, [{ type: "hello" }]);
  assert.deepStrictEqual(Object.keys(s.docListeners), []);
  assert.deepStrictEqual(Object.keys(s.winListeners), []);
  assert.strictEqual(s.queried(), 0);
  assert.strictEqual(s.intervals(), 0);
  assert.strictEqual(s.created.length, 0);
  s.answer({ type: "other" });
  s.answer(null);
  assert.deepStrictEqual(s.sent, [{ type: "hello" }]);
  assert.deepStrictEqual(Object.keys(s.docListeners), []);
});

test("record starts listening once", () => {
  const s = load();
  s.answer({ type: "record" });
  s.answer({ type: "record" });
  assert.ok(s.docListeners.playing && s.docListeners.waiting && s.docListeners.resize);
  assert.ok(s.winListeners.online && s.winListeners.offline);
  assert.strictEqual(s.queried(), 1);
  assert.strictEqual(s.intervals(), 1);
  assert.deepStrictEqual(s.sent[1], { type: "hls", mpegurl: "maybe", xmpegurl: "" });
});

test("a media element is described by kind only, never by address", () => {
  const s = load();
  s.answer({ type: "record" });
  const el = new HTMLVideoElement({ currentSrc: "https://cdn.other.net/live/index.m3u8?token=abc" });
  s.docListeners.playing({ type: "playing", target: el });
  s.docListeners.error({ type: "error", target: Object.assign(el, { error: { code: 2, message: "secret text" } }) });
  const text = JSON.stringify(s.sent);
  for (const bad of ["://", "cdn", "token", "index.m3u8", "secret", "example"]) assert.ok(!text.includes(bad), bad + " in " + text);
  const media = s.sent.find((m) => m.type === "media");
  assert.deepStrictEqual(media, { type: "media", id: 1, kind: "video", source: "hls", same: false, frame: false });
  const playing = s.sent.find((m) => m.type === "event" && m.name === "playing");
  assert.strictEqual(playing.pos, 12.3);
  assert.strictEqual(playing.ahead, 7.7);
  assert.strictEqual(s.sent.find((m) => m.name === "error").code, 2);
  assert.deepStrictEqual(el.calls, []);
});

test("a blob source is the same site; a frame sends no HLS answer and says it is a frame", () => {
  const s = load({ top: false, existing: [new HTMLVideoElement({ currentSrc: "blob:https://www.example.com/1234" })] });
  s.answer({ type: "record" });
  assert.ok(!s.sent.some((m) => m.type === "hls"));
  assert.deepStrictEqual(s.sent[1], { type: "media", id: 1, kind: "video", source: "blob", same: true, frame: true });
  assert.strictEqual(s.sent[2].name, "found");
  assert.ok(!s.winListeners.online);
});

test("the same source started again after emptied is marked", () => {
  const s = load();
  s.answer({ type: "record" });
  const el = new HTMLVideoElement({ currentSrc: "https://www.example.com/a.mp4" });
  s.docListeners.loadstart({ type: "loadstart", target: el });
  s.docListeners.emptied({ type: "emptied", target: el });
  s.docListeners.loadstart({ type: "loadstart", target: el });
  const starts = s.sent.filter((m) => m.name === "loadstart");
  assert.deepStrictEqual(
    starts.map((m) => m.again),
    [false, true],
  );
});
