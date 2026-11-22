// Restarts a video or audio element that has stopped loading for a long time, where the browser itself plays the
// file (a plain media file, or a playlist given straight to the element). The engine has no time limit for a response
// that goes silent in the middle, so without this such a video can hang until the user reloads the page.
//
// Never touches a source a page's own player script feeds (a blob: address from Media Source): those players have
// their own recovery. Never acts on a paused or ended element, on one that reports an error (a server that answers
// with errors is not asked again), or while the device is offline. At most MAX_TRIES restarts per element per page
// load, after 12, 24 and 48 seconds of unbroken stall. Sends nothing, stores nothing, logs nothing.
//
// The decision (OrbisStall.arm / step / restartPlan / afterMetadata) is pure, so it can be tested without a browser.

"use strict";

var OrbisStall = {
  FIRST_STALL_MS: 12000,
  MAX_TRIES: 3,
  // HTMLMediaElement.HAVE_FUTURE_DATA: at or above it the element has data to play on.
  HAVE_FUTURE_DATA: 3,

  /** Per-element state. tries counts restarts for the whole page load and never goes down. */
  newTrack: function () {
    return { armed: false, since: null, time: null, tries: 0, pending: false, onMeta: null };
  },

  /** How long the stall must have lasted, unbroken, before restart number tries + 1. */
  stallNeededMs: function (tries) {
    return OrbisStall.FIRST_STALL_MS * Math.pow(2, tries);
  },

  /**
   * Whether the element is one this script may act on at all. s is a snapshot of the element:
   * { src, paused, ended, online, error, readyState, currentTime, duration }.
   */
  eligible: function (s) {
    return (
      typeof s.src === "string" &&
      /^https?:/i.test(s.src) &&
      !s.paused &&
      !s.ended &&
      s.online === true &&
      !s.error
    );
  },

  /** A stall began (waiting or stalled fired, or found waiting). Starts the clock if it is not running. */
  arm: function (t, s, now) {
    if (t.tries >= OrbisStall.MAX_TRIES) return false;
    if (!OrbisStall.eligible(s) || s.readyState >= OrbisStall.HAVE_FUTURE_DATA) return false;
    if (!t.armed) {
      t.armed = true;
      t.since = now;
      t.time = s.currentTime;
    }
    return true;
  },

  /**
   * One look at an armed element. Returns "act" (restart it now), "wait" (still stalled, not long enough), or
   * "stop" (the stall is over or the element is out of bounds; the clock is cleared).
   */
  step: function (t, s, now) {
    if (!t.armed) return "stop";
    var progressed = s.readyState >= OrbisStall.HAVE_FUTURE_DATA || (t.time !== null && s.currentTime !== t.time);
    if (t.tries >= OrbisStall.MAX_TRIES || !OrbisStall.eligible(s) || progressed) {
      t.armed = false;
      t.since = null;
      t.time = null;
      return "stop";
    }
    // time is null right after this script's own load() or seek: take the new position as the reference.
    t.time = s.currentTime;
    if (now - t.since < OrbisStall.stallNeededMs(t.tries)) return "wait";
    t.tries += 1;
    t.since = now;
    t.time = null;
    return "act";
  },

  /** What to restore after load(). A live stream (duration Infinity) is not sought back; nor is position 0. */
  restartPlan: function (s) {
    var seekTo = isFinite(s.duration) && s.currentTime > 0 ? s.currentTime : null;
    return { src: s.src, seekTo: seekTo };
  },

  /** On loadedmetadata after a restart: if the page has replaced the source meanwhile, do nothing. */
  afterMetadata: function (plan, currentSrc) {
    if (currentSrc !== plan.src) return { seekTo: null, play: false };
    return { seekTo: plan.seekTo, play: true };
  },
};

(function () {
  if (typeof document === "undefined" || !document.addEventListener) return;

  var tracks = new WeakMap();
  var active = new Set();
  var timer = null;

  function isMedia(el) {
    return !!el && (el.localName === "video" || el.localName === "audio") && typeof el.load === "function";
  }

  function trackOf(el) {
    var t = tracks.get(el);
    if (!t) {
      t = OrbisStall.newTrack();
      tracks.set(el, t);
    }
    return t;
  }

  function snapshot(el, t) {
    return {
      src: el.currentSrc,
      // load() pauses the element; while this script's own restart is pending that pause is not the user's.
      paused: el.paused && !t.pending,
      ended: el.ended,
      online: navigator.onLine,
      error: !!el.error,
      readyState: el.readyState,
      currentTime: el.currentTime,
      duration: el.duration,
    };
  }

  function watch(el) {
    var t = trackOf(el);
    if (!OrbisStall.arm(t, snapshot(el, t), performance.now())) return;
    active.add(el);
    if (timer === null) timer = setInterval(tick, 1000);
  }

  function restart(el, t) {
    var plan = OrbisStall.restartPlan(snapshot(el, t));
    if (t.onMeta) el.removeEventListener("loadedmetadata", t.onMeta);
    var onMeta = function () {
      el.removeEventListener("loadedmetadata", onMeta);
      if (t.onMeta !== onMeta) return;
      t.onMeta = null;
      t.pending = false;
      var next = OrbisStall.afterMetadata(plan, el.currentSrc);
      if (next.seekTo !== null) {
        el.currentTime = next.seekTo;
        t.time = null;
      }
      if (next.play) {
        var played = el.play();
        if (played && typeof played.catch === "function") played.catch(function () {});
      }
    };
    t.onMeta = onMeta;
    el.addEventListener("loadedmetadata", onMeta);
    t.pending = true;
    el.load();
  }

  function tick() {
    active.forEach(function (el) {
      var t = trackOf(el);
      var verdict = el.isConnected === false ? "stop" : OrbisStall.step(t, snapshot(el, t), performance.now());
      if (verdict === "stop") {
        t.armed = false;
        active.delete(el);
      } else if (verdict === "act") {
        restart(el, t);
      }
    });
    if (active.size === 0 && timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  }

  function scan() {
    var all = document.querySelectorAll("video, audio");
    for (var i = 0; i < all.length; i++) watch(all[i]);
  }

  function onStall(e) {
    if (isMedia(e.target)) watch(e.target);
  }

  // Media events do not bubble; a capturing listener on the document sees them for every element in it, including
  // ones added later.
  document.addEventListener("waiting", onStall, true);
  document.addEventListener("stalled", onStall, true);
  document.addEventListener(
    "play",
    function (e) {
      // The user or the page started playback itself: this script's restart no longer owns the pause.
      if (isMedia(e.target) && tracks.has(e.target)) tracks.get(e.target).pending = false;
    },
    true,
  );
  window.addEventListener("online", scan);
  scan();
})();
