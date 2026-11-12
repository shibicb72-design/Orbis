"use strict";

const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const pipPath = path.join(__dirname, "../../main/assets/extensions/yt-pip/pip.js");
// Outside Firefox there is no window or exportFunction, so the script only defines its helpers.
vm.runInThisContext(fs.readFileSync(pipPath, "utf8"));
const OrbisYtPip = global.OrbisYtPip;
assert.ok(OrbisYtPip, "pip.js should define OrbisYtPip");

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

test("ratio comes from YouTube's experiment flag, else 0.33", () => {
  assert.strictEqual(OrbisYtPip.ratioFromFlags("a=1&html5_picture_in_picture_logging_onresize_ratio=0.5&b=2"), 0.5);
  assert.strictEqual(OrbisYtPip.ratioFromFlags("a=1&b=2"), 0.33);
  assert.strictEqual(OrbisYtPip.ratioFromFlags(undefined), 0.33);
  assert.strictEqual(OrbisYtPip.ratioFromFlags("html5_picture_in_picture_logging_onresize_ratio=0"), 0.33);
});

test("a picture-in-picture window is shrunk, a full or rotated window is not", () => {
  const phone = 411 * 914;
  // Seen on API 31: fullscreen 920x414, picture-in-picture 194x109 (CSS pixels).
  assert.strictEqual(OrbisYtPip.isShrunk(194 * 109, phone, 0.33), true);
  assert.strictEqual(OrbisYtPip.isShrunk(920 * 414, phone, 0.33), false);
  assert.strictEqual(OrbisYtPip.isShrunk(414 * 779, phone, 0.33), false);
  // Split screen, half the phone: YouTube's own test does not fire, so neither does this one.
  assert.strictEqual(OrbisYtPip.isShrunk(411 * 450, phone, 0.33), false);
  assert.strictEqual(OrbisYtPip.isShrunk(100, 0, 0.33), false);
});
