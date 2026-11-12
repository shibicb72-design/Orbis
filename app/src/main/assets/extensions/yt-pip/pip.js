/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Adapted from Firefox's webcompat intervention for bug 1956165
// (browser/extensions/webcompat/injections/js/bug1956165-www.youtube.com-picture-in-picture-fix.js).
//
// Picture-in-picture shrinks the whole page. On every resize, mobile YouTube compares the window area
// (outerWidth * outerHeight) with the largest area seen. Below a ratio (0.33 unless an experiment flag says otherwise)
// it treats the page as minimised: it leaves fullscreen and pauses. While the window is that small, this script
// reports the screen size as the window size and ignores exitFullscreen.

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
  // Content script only: node loads this file for the tests above.
  if (typeof window === "undefined" || !window.wrappedJSObject || typeof exportFunction !== "function") return;
  var page = window.wrappedJSObject;
  if (page.__orbisYtPip) return;
  Object.defineProperty(page, "__orbisYtPip", { value: true });

  var ratio;
  function currentRatio() {
    if (ratio !== undefined) return ratio;
    var flags;
    try {
      var cfg = page.ytcfg && page.ytcfg.get("WEB_PLAYER_CONTEXT_CONFIGS");
      flags = cfg && cfg.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH && cfg.WEB_PLAYER_CONTEXT_CONFIG_ID_MWEB_WATCH.serializedExperimentFlags;
    } catch (e) {
      flags = undefined;
    }
    // Remember it only once YouTube's config exists; before that, use the default without caching it.
    if (typeof flags === "string") ratio = OrbisYtPip.ratioFromFlags(flags);
    return ratio === undefined ? OrbisYtPip.DEFAULT_RATIO : ratio;
  }

  var largest = 0;
  function shrunk() {
    // The content script sees the real values: the page's overrides below do not apply here.
    var windowArea = window.outerWidth * window.outerHeight;
    largest = Math.max(largest, screen.width * screen.height, windowArea);
    return OrbisYtPip.isShrunk(windowArea, largest, currentRatio());
  }

  Object.defineProperty(page, "outerWidth", {
    configurable: true,
    enumerable: true,
    get: exportFunction(function () {
      return shrunk() ? screen.width : window.outerWidth;
    }, window),
  });
  Object.defineProperty(page, "outerHeight", {
    configurable: true,
    enumerable: true,
    get: exportFunction(function () {
      return shrunk() ? screen.height : window.outerHeight;
    }, window),
  });

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
