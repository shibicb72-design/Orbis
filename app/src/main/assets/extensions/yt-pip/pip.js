/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Adapted from Firefox's webcompat intervention for bug 1956165,
// browser/extensions/webcompat/injections/js/bug1956165-www.youtube.com-picture-in-picture-fix.js
// at mozilla-firefox/firefox commit e98e47c20429bdabcd34f5b550bdbcb2d432a0a2 (2026-02-06).
//
// Picture-in-picture shrinks the whole page. On every resize, mobile YouTube compares the window area
// (outerWidth * outerHeight) with the largest area seen. Below a ratio (0.33 unless an experiment flag says otherwise)
// it treats the page as minimised: it leaves fullscreen and pauses. While the window is that small, this script
// reports the screen size as the window size and ignores exitFullscreen.
//
// A content script runs once per document, so there is no guard. The page sees only the two getters and
// exitFullscreen, no marker.

"use strict";

var OrbisYtPip = {
  DEFAULT_RATIO: 0.33,

  /** The ratio YouTube uses, read from its experiment flags; the default when absent. */
  ratioFromFlags: function (flags) {
    var m = typeof flags === "string" ? flags.match(/html5_picture_in_picture_logging_onresize_ratio=(\d+(\.\d+)?)/) : null;
    return (m && parseFloat(m[1])) || OrbisYtPip.DEFAULT_RATIO;
  },

  /** True when the window is as small as YouTube's own minimised test; [largest] is the biggest area seen so far. */
  isShrunk: function (windowArea, largest, ratio) {
    return largest > 0 && windowArea / largest < ratio;
  },
};

(function () {
  // Content script only: without a window (node), only the helpers above are defined.
  if (typeof window === "undefined" || !window.wrappedJSObject || typeof exportFunction !== "function") return;
  var page = window.wrappedJSObject;

  var ratio;
  function currentRatio() {
    if (ratio !== undefined) return ratio;
    var ytcfg = page.ytcfg;
    // Before YouTube's config exists, use the default without remembering it.
    if (!ytcfg || typeof ytcfg.get !== "function") return OrbisYtPip.DEFAULT_RATIO;
    var flags;
    try {
      var cfg = ytcfg.get("WEB_PLAYER_CONTEXT_CONFIGS");
      flags = cfg && cfg.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH && cfg.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH.serializedExperimentFlags;
    } catch (e) {
      flags = undefined;
    }
    ratio = OrbisYtPip.ratioFromFlags(flags);
    return ratio;
  }

  var largest = 0;
  function shrunk() {
    // The content script sees the real values: the page's overrides below do not apply here.
    var windowArea = window.outerWidth * window.outerHeight;
    largest = Math.max(largest, screen.width * screen.height, windowArea);
    return OrbisYtPip.isShrunk(windowArea, largest, currentRatio());
  }

  // Keep the rest of the original descriptor (its setter: the attributes are replaceable), as Firefox's fix does.
  function override(name, screenValue) {
    var original = Object.getOwnPropertyDescriptor(page, name) || { configurable: true, enumerable: true };
    Object.defineProperty(page, name, {
      configurable: original.configurable,
      enumerable: original.enumerable,
      set: original.set,
      get: exportFunction(function () {
        return shrunk() ? screenValue() : window[name];
      }, window),
    });
  }
  override("outerWidth", function () { return screen.width; });
  override("outerHeight", function () { return screen.height; });

  var exitFullscreen = page.Document.prototype.exitFullscreen;
  Object.defineProperty(page.Document.prototype, "exitFullscreen", {
    configurable: true,
    writable: true,
    value: exportFunction(function () {
      if (shrunk()) return undefined;
      return exitFullscreen.call(this);
    }, window),
  });
})();
