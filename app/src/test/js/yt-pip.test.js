"use strict";

const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const pipPath = path.join(__dirname, "../../main/assets/extensions/yt-pip/pip.js");
const source = fs.readFileSync(pipPath, "utf8");

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

/**
 * Runs pip.js as Firefox would run the content script: [window] is the content script's view (real sizes), and
 * window.wrappedJSObject is the page's own window, where the overrides land. exportFunction is the identity here.
 */
function load({ width, height, screenWidth = 400, screenHeight = 900, ytcfg } = {}) {
  const exits = [];
  const replaced = {};
  const page = {
    Document: {
      prototype: {
        exitFullscreen: function () {
          exits.push(this);
          return "exited";
        },
      },
    },
  };
  if (ytcfg) page.ytcfg = ytcfg;
  // The page's own window has the attributes as replaceable accessors (getter and setter).
  for (const name of ["outerWidth", "outerHeight"]) {
    Object.defineProperty(page, name, {
      configurable: true,
      enumerable: true,
      get: () => "page original",
      set: v => {
        replaced[name] = v;
      },
    });
  }
  const win = { outerWidth: width, outerHeight: height, wrappedJSObject: page };
  const context = vm.createContext({
    window: win,
    screen: { width: screenWidth, height: screenHeight },
    exportFunction: f => f,
  });
  vm.runInContext(source, context);
  return { page, win, exits, replaced, helpers: context.OrbisYtPip };
}

function flags(ratio) {
  return {
    get: key =>
      key === "WEB_PLAYER_CONTEXT_CONFIGS"
        ? { WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH: { serializedExperimentFlags: "a=1&html5_picture_in_picture_logging_onresize_ratio=" + ratio } }
        : undefined,
  };
}

test("ratio comes from YouTube's experiment flag, else 0.33", () => {
  const { helpers } = load({ width: 400, height: 900 });
  assert.strictEqual(helpers.ratioFromFlags("a=1&html5_picture_in_picture_logging_onresize_ratio=0.5&b=2"), 0.5);
  assert.strictEqual(helpers.ratioFromFlags("a=1&b=2"), 0.33);
  assert.strictEqual(helpers.ratioFromFlags(undefined), 0.33);
  assert.strictEqual(helpers.ratioFromFlags("html5_picture_in_picture_logging_onresize_ratio=0"), 0.33);
});

test("a picture-in-picture window reports the screen size and keeps fullscreen", () => {
  const { page, win, exits } = load({ width: 920, height: 414 });
  // Fullscreen first (the largest area), then the picture-in-picture window seen on API 31.
  assert.strictEqual(page.outerWidth, 920);
  win.outerWidth = 194;
  win.outerHeight = 109;
  assert.strictEqual(page.outerWidth, 400);
  assert.strictEqual(page.outerHeight, 900);
  const doc = { name: "the page document" };
  assert.strictEqual(page.Document.prototype.exitFullscreen.call(doc), undefined);
  assert.deepStrictEqual(exits, []);
});

test("at full size the real values pass through and exitFullscreen runs on the right document", () => {
  const { page, win, exits } = load({ width: 400, height: 900 });
  assert.strictEqual(page.outerWidth, 400);
  assert.strictEqual(page.outerHeight, 900);
  // Split screen, half the phone: not small enough for YouTube's test, so not for this one either.
  win.outerHeight = 450;
  assert.strictEqual(page.outerHeight, 450);
  const doc = { name: "the page document" };
  assert.strictEqual(page.Document.prototype.exitFullscreen.call(doc), "exited");
  assert.deepStrictEqual(exits, [doc]);
});

test("the original setters stay, so the page can still replace the attributes", () => {
  const { page, replaced } = load({ width: 400, height: 900 });
  page.outerWidth = 5;
  page.outerHeight = 6;
  assert.deepStrictEqual(replaced, { outerWidth: 5, outerHeight: 6 });
  const d = Object.getOwnPropertyDescriptor(page, "outerWidth");
  assert.strictEqual(d.configurable, true);
  assert.strictEqual(d.enumerable, true);
});

test("the ratio is remembered only once YouTube's config exists", () => {
  // Window at 40% of the screen: small under a 0.5 ratio, not under the 0.33 default.
  const { page, win } = load({ width: 400, height: 900 });
  win.outerHeight = 360;
  assert.strictEqual(page.outerHeight, 360, "no ytcfg yet: default 0.33, not small");
  page.ytcfg = flags(0.5);
  assert.strictEqual(page.outerHeight, 900, "ytcfg ratio 0.5: small");
  page.ytcfg = flags(0.1);
  assert.strictEqual(page.outerHeight, 900, "the first config's ratio is kept");
});

test("nothing marks the page", () => {
  const { page } = load({ width: 400, height: 900 });
  assert.deepStrictEqual(Object.keys(page).sort(), ["Document", "outerHeight", "outerWidth"]);
});
