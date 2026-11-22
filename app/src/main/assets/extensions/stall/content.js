// Restarts a video or audio element that has stopped loading for a long time, where the browser itself plays the
// file (a plain media file, or a playlist given straight to the element). The engine has no time limit for a response
// that goes silent in the middle, so without this such a video can hang until the user reloads the page.
//
// Never touches a source a page's own player script feeds (a blob: address from Media Source): those players have
// their own recovery. Never acts on a paused or ended element, on one that reports an error (a server that answers
// with errors is not asked again), or while the device is offline. At most MAX_TRIES restarts per element per page
// load, after 12, 24 and 48 seconds of unbroken stall. Time while bytes still arrive (a progress event, or the end of
// the buffered data moving) does not count: a slow download that works is never restarted. Sends nothing, stores
// nothing, logs nothing.
//
// One error is restarted: a LIVE playlist on the engine's own HLS player that dies with that player's error while it
// was playing (the player gives up for good, for example when a stall made it fall out of the live window). It is
// restarted 3, 12 or 24 seconds after the error, sharing the same limit of MAX_TRIES. Never a plain file on an error.
//
// The decision (OrbisStall.arm / step / errorDelayMs / errorDue / restartPlan / afterMetadata) is pure, so it can be
// tested without a browser.

"use strict";

var OrbisStall = {
  FIRST_STALL_MS: 12000,
  MAX_TRIES: 3,
  // HTMLMediaElement.HAVE_FUTURE_DATA: at or above it the element has data to play on.
  HAVE_FUTURE_DATA: 3,
  // The engine's own HLS player reports any player error to the page as MEDIA_ERR_NETWORK (2), with no message, once
  // data has arrived (HLSDecoder.cpp, HLSResourceCallbacksSupport::OnError -> HTMLMediaElement::NetworkError). Before
  // any data it is 4 (MEDIA_ERR_SRC_NOT_SUPPORTED), as for any address that cannot be loaded; that one is not retried.
  HLS_PLAYER_ERROR: 2,
  // Wait after such an error before restart number tries + 1 (the count is shared with the stall restarts).
  ERROR_DELAYS_MS: [3000, 12000, 24000],

  /** Per-element state. tries counts restarts of both kinds for the whole page load and never goes down. */
  newTrack: function () {
    return { armed: false, since: null, time: null, bufEnd: null, tries: 0, pending: false, onMeta: null, live: false };
  },

  /** How long the stall must have lasted, unbroken, before restart number tries + 1. */
  stallNeededMs: function (tries) {
    return OrbisStall.FIRST_STALL_MS * Math.pow(2, tries);
  },

  /**
   * Whether the element is one this script may act on for a stall. s is a snapshot of the element:
   * { src, paused, ended, online, error, errorCode, live, readyState, currentTime, duration, bufferedEnd }.
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
      t.bufEnd = s.bufferedEnd;
    }
    return true;
  },

  /**
   * Bytes arrived (the element fired progress). A slow download that still moves is not a stall: the clock counts
   * only time with no download progress, so restarting never throws away a download that is working.
   */
  downloaded: function (t, now) {
    if (t.armed) t.since = now;
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
      t.bufEnd = null;
      return "stop";
    }
    // The end of the buffered data moved: the download is alive, so the clock starts again.
    if (t.bufEnd !== null && s.bufferedEnd !== t.bufEnd) t.since = now;
    // time and bufEnd are null right after this script's own load() or seek: take the new values as the reference.
    t.time = s.currentTime;
    t.bufEnd = s.bufferedEnd;
    if (now - t.since < OrbisStall.stallNeededMs(t.tries)) return "wait";
    t.tries += 1;
    t.since = now;
    t.time = null;
    t.bufEnd = null;
    return "act";
  },

  /**
   * Whether the element plays a live playlist on the engine's own HLS player, as far as a page can know: an http(s)
   * address whose path ends in .m3u8 (the engine picks that player by the playlist's type, which a page cannot see),
   * and live (duration Infinity). Both are needed: an endless http audio stream (internet radio) also has duration
   * Infinity, but it is a plain file, and an error there is not retried.
   */
  isLivePlaylist: function (src, live) {
    if (typeof src !== "string" || !/^https?:/i.test(src)) return false;
    return /\.m3u8$/i.test(src.split(/[?#]/)[0]) && live === true;
  },

  /** On an error event: how long to wait before an error restart, or null for never. */
  errorDelayMs: function (t, s) {
    if (t.tries >= OrbisStall.MAX_TRIES) return null;
    if (!OrbisStall.isLivePlaylist(s.src, s.live)) return null;
    if (s.errorCode !== OrbisStall.HLS_PLAYER_ERROR) return null;
    if (s.paused || s.ended) return null;
    return OrbisStall.ERROR_DELAYS_MS[t.tries];
  },

  /**
   * When the wait after an error is over. "act" (restart now; the try is counted), "offline" (wait until the device
   * is online), or "drop": the page replaced the source or cleared the error itself, the user paused, or the tries
   * are used up.
   */
  errorDue: function (t, plan, s) {
    if (t.tries >= OrbisStall.MAX_TRIES) return "drop";
    if (s.src !== plan.src || s.errorCode !== OrbisStall.HLS_PLAYER_ERROR) return "drop";
    if (s.paused || s.ended) return "drop";
    if (s.online !== true) return "offline";
    t.tries += 1;
    t.armed = false;
    t.since = null;
    t.time = null;
    t.bufEnd = null;
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
  // Elements whose error restart came due while offline, with their plan.
  var waitingOnline = new Map();

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
      errorCode: el.error ? el.error.code : 0,
      live: t.live || el.duration === Infinity,
      readyState: el.readyState,
      currentTime: el.currentTime,
      duration: el.duration,
      bufferedEnd: bufferedEnd(el),
    };
  }

  function bufferedEnd(el) {
    var b = el.buffered;
    return b && b.length > 0 ? b.end(b.length - 1) : 0;
  }

  function watch(el) {
    var t = trackOf(el);
    if (!OrbisStall.arm(t, snapshot(el, t), performance.now())) return;
    active.add(el);
    if (timer === null) timer = setInterval(tick, 1000);
  }

  function restart(el, t, plan) {
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
        t.bufEnd = null;
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
        restart(el, t, OrbisStall.restartPlan(snapshot(el, t)));
      }
    });
    if (active.size === 0 && timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  }

  function errorRestartDue(el, plan) {
    var t = trackOf(el);
    var verdict = OrbisStall.errorDue(t, plan, snapshot(el, t));
    if (verdict === "offline") {
      waitingOnline.set(el, plan);
    } else if (verdict === "act") {
      active.delete(el);
      // A live playlist joins the live edge again: no seek back.
      restart(el, t, { src: plan.src, seekTo: null });
    }
  }

  function onError(e) {
    var el = e.target;
    if (!isMedia(el)) return;
    var t = trackOf(el);
    var s = snapshot(el, t);
    var delay = OrbisStall.errorDelayMs(t, s);
    if (delay === null) return;
    var plan = { src: s.src };
    setTimeout(function () {
      errorRestartDue(el, plan);
    }, delay);
  }

  function onOnline() {
    scan();
    waitingOnline.forEach(function (plan, el) {
      waitingOnline.delete(el);
      setTimeout(function () {
        errorRestartDue(el, plan);
      }, OrbisStall.ERROR_DELAYS_MS[0]);
    });
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
  document.addEventListener("error", onError, true);
  document.addEventListener(
    "durationchange",
    function (e) {
      // Remembered so that "live" is still known when an error comes.
      if (isMedia(e.target)) trackOf(e.target).live = e.target.duration === Infinity;
    },
    true,
  );
  document.addEventListener(
    "progress",
    function (e) {
      if (isMedia(e.target) && tracks.has(e.target)) OrbisStall.downloaded(tracks.get(e.target), performance.now());
    },
    true,
  );
  document.addEventListener(
    "play",
    function (e) {
      // The user or the page started playback itself: this script's restart no longer owns the pause.
      if (isMedia(e.target) && tracks.has(e.target)) tracks.get(e.target).pending = false;
    },
    true,
  );
  window.addEventListener("online", onOnline);
  scan();
})();
