"use strict";

// The compat content script's "empty" decision (auto-desktop mobile retry). Runs content.js in a fake page: the
// timers are run by hand, and the one message the script sends to the app is captured.

const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const scriptPath = path.join(__dirname, "../../main/assets/extensions/compat/content.js");
const source = fs.readFileSync(scriptPath, "utf8");

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

// An element as the script sees it: [shown] false means checkVisibility() says hidden.
function element({ shown = true, width = 100, height = 50 } = {}) {
  return {
    checkVisibility: () => shown,
    getClientRects: () => (shown ? [{}] : []),
    getBoundingClientRect: () => ({ width, height }),
    innerText: "",
  };
}

/**
 * [text] is body.innerText (visible text only, as in Firefox); [hiddenText] is only in textContent.
 * [elements] maps a tag name to the elements of that kind.
 */
function run({ text = "", hiddenText = "", title = "", readyState = "complete", elements = {} } = {}) {
  const sent = [];
  const timers = [];
  const document = {
    title,
    readyState,
    body: { innerText: text, textContent: text + hiddenText },
    addEventListener: () => {},
    removeEventListener: () => {},
    querySelectorAll: sel => {
      const out = [];
      for (const tag of sel.split(",").map(s => s.trim())) {
        if (elements[tag]) out.push(...elements[tag]);
      }
      return out;
    },
  };
  const context = vm.createContext({
    document,
    location: { pathname: "/page" },
    setTimeout: (fn, ms) => {
      timers.push({ fn, ms });
      return timers.length;
    },
    clearTimeout: () => {},
    browser: {
      runtime: {
        connectNative: name => {
          assert.strictEqual(name, "orbisCompat");
          return { postMessage: m => sent.push(JSON.parse(JSON.stringify(m))) };
        },
      },
    },
  });
  vm.runInContext(source, context);
  // Every timer in order (1.5 s, then 3 s); the script stops after its one message.
  for (const t of timers.sort((a, b) => a.ms - b.ms)) t.fn();
  assert.strictEqual(sent.length, 1, "exactly one message");
  return sent[0];
}

const EMPTY = { type: "clean", page: "empty" };
const NOT_EMPTY = { type: "clean", page: "not-empty" };

test("19 characters and nothing visible is empty", () => {
  assert.deepStrictEqual(run({ text: "a".repeat(19) }), EMPTY);
});

test("20 characters is not empty", () => {
  assert.deepStrictEqual(run({ text: "a".repeat(20) }), NOT_EMPTY);
});

test("spaces and line breaks do not count as text", () => {
  const spaced = n => " \n\t" + "x ".repeat(n) + "\n";
  assert.deepStrictEqual(run({ text: spaced(20) }), NOT_EMPTY);
  assert.deepStrictEqual(run({ text: spaced(19) }), EMPTY);
});

test("a blank page is empty", () => {
  assert.deepStrictEqual(run({}), EMPTY);
});

test("an image alone is not empty", () => {
  assert.deepStrictEqual(run({ elements: { img: [element()] } }), NOT_EMPTY);
});

test("a video, a canvas or a form control alone is not empty", () => {
  for (const tag of ["video", "canvas", "input", "button", "select", "textarea", "svg", "iframe"]) {
    assert.deepStrictEqual(run({ elements: { [tag]: [element()] } }), NOT_EMPTY, tag);
  }
});

test("hidden text is empty", () => {
  assert.deepStrictEqual(run({ hiddenText: "This page opens the app for Android users only." }), EMPTY);
});

test("hidden, zero-size and 1-pixel images do not count", () => {
  const imgs = [element({ shown: false }), element({ width: 0, height: 0 }), element({ width: 1, height: 1 })];
  assert.deepStrictEqual(run({ elements: { img: imgs } }), EMPTY);
});

test("a page still loading at 3 s is not called empty", () => {
  assert.deepStrictEqual(run({ readyState: "interactive" }), NOT_EMPTY);
});

test("only the word empty or not-empty is sent: no text, address or counts", () => {
  const m = run({ text: "secret", title: "Private title" });
  assert.deepStrictEqual(Object.keys(m).sort(), ["page", "type"]);
});

test("a blocked page still sends its sample, not clean", () => {
  const m = run({ text: "This browser is not supported. Please use Chrome.", title: "Unsupported browser" });
  assert.strictEqual(m.type, "sample");
});
