// Restarts a video or audio element that has stopped loading for a long time, where the browser itself plays the
// file (a plain media file, or a playlist given straight to the element). The engine has no time limit for a response
// that goes silent in the middle, so without this such a video can hang until the user reloads the page.
//
// Never touches a source a page's own player script feeds (a blob: address from Media Source): those players have
// their own recovery. Never acts on a paused or ended element, on one that reports an error (a server that answers
// with errors is not asked again), or while the device is offline. At most MAX_TRIES restarts per element per page
// load, after 12, 24 and 48 seconds of unbroken stall. Time while bytes still arrive (a progress event, or the end of
// the buffered data moving) does not count: a slow download that works is never restarted. Stores nothing, logs
// nothing, and sends nothing except the two-value signal below, to the Orbis app only.
//
// One error is handled: a LIVE playlist on the engine's own HLS player that dies with that player's error while it
// was playing (the player gives up for good, for example when a stall made it fall out of the live window). That opens
// a 30-second reconnect window: a reload 3 seconds after the error, then each next one 1 second after the previous one
// FAILED (never two at once). Back after 10 seconds of playing. If it is not back after 30 seconds, the script stops and
// raises "dropped" once (streamSignal). After that only the user pressing play opens a fresh window, or coming back
// online while the page is visible within 5 minutes of the drop. Never for a plain file on an error.
//
// The decisions (OrbisStall.arm / step for stalls, onError / onMetadata / onDownload / winStep / reopen for the
// window) are pure, so they can be tested without a browser.

"use strict";

var OrbisStall = {
  FIRST_STALL_MS: 12000,
  MAX_TRIES: 3,
  // HTMLMediaElement.HAVE_FUTURE_DATA: at or above it the element has data to play on.
  HAVE_FUTURE_DATA: 3,
  // The engine's own HLS player reports any player error to the page as MEDIA_ERR_NETWORK (2), with no message, once
  // data has arrived (HLSDecoder.cpp, HLSResourceCallbacksSupport::OnError -> HTMLMediaElement::NetworkError). Before
  // any data it is 4 (MEDIA_ERR_SRC_NOT_SUPPORTED), as for any address that cannot be loaded. Only code 2 opens a
  // window; inside an open window any code means "still dead".
  HLS_PLAYER_ERROR: 2,
  // The reconnect window (times are window time, which stands still while the device is offline).
  WINDOW_MS: 30000,
  FIRST_ATTEMPT_MS: 3000,
  RETRY_GAP_MS: 1000,
  HANG_MS: 6000,
  // Seconds of playing (currentTime moving) after which the stream counts as back.
  BACK_AFTER_S: 10,
  // A larger jump of currentTime between two looks is a jump (to the live edge), not playing.
  MAX_PLAY_STEP_S: 2.5,
  // After "dropped", coming back online reopens a window only within this time, and only with the page visible.
  ONLINE_REOPEN_MS: 5 * 60 * 1000,

  /** Per-element state. tries counts stall restarts for the whole page load and never goes down. */
  newTrack: function () {
    return {
      armed: false,
      since: null,
      time: null,
      bufEnd: null,
      tries: 0,
      pending: false,
      onMeta: null,
      live: false,
      win: null,
      dropped: false,
      lastSignal: null,
      droppedAt: null,
      onlineMayReopen: false,
    };
  },

  /** The app's native-messaging name for this extension (StallExtension.NATIVE_APP). */
  NATIVE_APP: "orbisStall",

  /**
   * Tells the Orbis app that this page's live stream could not be reconnected ("dropped") or plays again ("playing"),
   * so it can show or clear its own message. The message is exactly { type: value }: no address, no title, nothing
   * about the element. Any other value sends nothing. It goes to the Orbis app only, through GeckoView's native
   * messaging (one short-lived port per signal, as the compat script does); never to the page or a server.
   */
  streamSignal: function (value) {
    if (value !== "dropped" && value !== "playing") return;
    try {
      browser.runtime.connectNative(OrbisStall.NATIVE_APP).postMessage({ type: value });
    } catch (e) {
      // No messaging (outside Orbis): nothing to tell.
    }
  },

  /** How long the stall must have lasted, unbroken, before restart number tries + 1. */
  stallNeededMs: function (tries) {
    return OrbisStall.FIRST_STALL_MS * Math.pow(2, tries);
  },

  /**
   * Whether the element is one this script may act on for a stall. s is a snapshot of the element:
   * { src, paused, ended, online, connected, error, errorCode, live, readyState, currentTime, duration, bufferedEnd }.
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
    if (t.win) return false;
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
    if (t.win || t.tries >= OrbisStall.MAX_TRIES || !OrbisStall.eligible(s) || progressed) {
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

  newWindow: function (src, now) {
    return {
      src: src,
      elapsed: 0,
      last: now,
      nextAt: OrbisStall.FIRST_ATTEMPT_MS,
      attempt: null,
      played: 0,
      lastCur: null,
      bufEnd: null,
    };
  },

  /** Window time runs only while online. */
  winClock: function (w, now, online) {
    if (online === true) w.elapsed += Math.max(0, now - w.last);
    w.last = now;
  },

  /** The attempt in flight (or the stream that was playing again) failed: the next comes RETRY_GAP_MS after now. */
  winFail: function (w) {
    w.attempt = null;
    w.played = 0;
    w.lastCur = null;
    w.bufEnd = null;
    w.nextAt = w.elapsed + OrbisStall.RETRY_GAP_MS;
  },

  /**
   * An error event. Returns "open" (a window opened), "failed" (inside a window: the attempt failed, whatever the
   * code), or "none".
   */
  onError: function (t, s, now) {
    if (t.win) {
      OrbisStall.winClock(t.win, now, s.online);
      OrbisStall.winFail(t.win);
      return "failed";
    }
    if (t.dropped) return "none";
    if (!OrbisStall.isLivePlaylist(s.src, s.live)) return "none";
    if (s.errorCode !== OrbisStall.HLS_PLAYER_ERROR) return "none";
    if (s.paused || s.ended) return "none";
    t.win = OrbisStall.newWindow(s.src, now);
    t.armed = false;
    t.since = null;
    return "open";
  },

  /** loadedmetadata during an attempt: it loaded; from now on it must play (or it fails by error or by hanging). */
  onMetadata: function (t, now, online) {
    var w = t.win;
    if (!w || !w.attempt) return;
    OrbisStall.winClock(w, now, online);
    w.attempt.loaded = true;
    w.attempt.lastSign = w.elapsed;
  },

  /** progress during an attempt: not hung. */
  onDownload: function (t, now, online) {
    var w = t.win;
    if (!w || !w.attempt) return;
    OrbisStall.winClock(w, now, online);
    w.attempt.lastSign = w.elapsed;
  },

  /**
   * One look (every second) at an element with an open window. Returns:
   * "load" (start an attempt now), "wait", "stop" (the window ends without a signal), "back" (it plays again),
   * or a signal to raise: "dropped" (gave up) or "playing" (back after "dropped" was raised).
   */
  winStep: function (t, s, now) {
    var w = t.win;
    if (!w) return "stop";
    OrbisStall.winClock(w, now, s.online);
    var notLive = w.attempt && w.attempt.loaded && isFinite(s.duration);
    if (s.src !== w.src || s.connected === false || s.paused || s.ended || notLive) {
      t.win = null;
      return "stop";
    }
    var a = w.attempt;
    if (a) {
      var step = w.lastCur === null ? 0 : s.currentTime - w.lastCur;
      var moved = step > 0;
      var grew = w.bufEnd !== null && s.bufferedEnd !== w.bufEnd;
      if (moved || grew) a.lastSign = w.elapsed;
      if (a.loaded && moved && step <= OrbisStall.MAX_PLAY_STEP_S) w.played += step;
      w.lastCur = s.currentTime;
      w.bufEnd = s.bufferedEnd;
      if (w.played >= OrbisStall.BACK_AFTER_S) {
        t.win = null;
        if (t.lastSignal === "dropped") {
          t.lastSignal = "playing";
          return "playing";
        }
        return "back";
      }
      if (w.elapsed - a.lastSign < OrbisStall.HANG_MS) return "wait";
      // Hung: counts as failed.
      OrbisStall.winFail(w);
    }
    if (w.elapsed >= OrbisStall.WINDOW_MS) {
      t.win = null;
      t.dropped = true;
      if (t.lastSignal !== "dropped") {
        t.lastSignal = "dropped";
        t.droppedAt = now;
        t.onlineMayReopen = true;
        return "dropped";
      }
      return "stop";
    }
    if (s.online !== true || w.elapsed < w.nextAt) return "wait";
    w.attempt = { lastSign: w.elapsed, loaded: false };
    w.lastCur = null;
    w.bufEnd = null;
    w.nextAt = null;
    return "load";
  },

  /**
   * After giving up, one fresh window opens when cause is "play" (the user pressed play), at any time; or when cause
   * is "online" (the device came back online), but only if the page is visible right then and no more than
   * ONLINE_REOPEN_MS have passed since "dropped" was raised. A phone that regains its connection in a pocket must not
   * start sound by itself. An "online" that misses either condition ends it: no later "online" reopens; only play does.
   */
  reopen: function (t, s, now, cause) {
    if (!t.dropped || t.win) return false;
    if (cause !== "play" && cause !== "online") return false;
    if (cause === "online") {
      if (!t.onlineMayReopen) return false;
      if (s.visible !== true || t.droppedAt === null || now - t.droppedAt > OrbisStall.ONLINE_REOPEN_MS) {
        t.onlineMayReopen = false;
        return false;
      }
    }
    if (!OrbisStall.isLivePlaylist(s.src, s.live) || s.connected === false) return false;
    t.dropped = false;
    t.onlineMayReopen = false;
    t.win = OrbisStall.newWindow(s.src, now);
    return true;
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
  // Elements with a running stall clock, and elements with an open reconnect window.
  var active = new Set();
  var windows = new Set();
  // Elements that gave up ("dropped"), for the online event.
  var dropped = new Set();
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
      connected: el.isConnected !== false,
      visible: document.visibilityState === "visible",
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

  function startTimer() {
    if (timer === null) timer = setInterval(tick, 1000);
  }

  function watch(el) {
    var t = trackOf(el);
    if (!OrbisStall.arm(t, snapshot(el, t), performance.now())) return;
    active.add(el);
    startTimer();
  }

  function signal(value) {
    try {
      OrbisStall.streamSignal(value);
    } catch (e) {
      // The hook must never break the page.
    }
  }

  function restart(el, t, plan) {
    if (t.onMeta) el.removeEventListener("loadedmetadata", t.onMeta);
    var onMeta = function () {
      el.removeEventListener("loadedmetadata", onMeta);
      if (t.onMeta !== onMeta) return;
      t.onMeta = null;
      t.pending = false;
      OrbisStall.onMetadata(t, performance.now(), navigator.onLine);
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

  function closeWindow(el, t) {
    windows.delete(el);
    if (t.onMeta) el.removeEventListener("loadedmetadata", t.onMeta);
    t.onMeta = null;
    t.pending = false;
  }

  function tick() {
    var now = performance.now();
    active.forEach(function (el) {
      var t = trackOf(el);
      var verdict = el.isConnected === false ? "stop" : OrbisStall.step(t, snapshot(el, t), now);
      if (verdict === "stop") {
        t.armed = false;
        active.delete(el);
      } else if (verdict === "act") {
        restart(el, t, OrbisStall.restartPlan(snapshot(el, t)));
      }
    });
    windows.forEach(function (el) {
      var t = trackOf(el);
      var verdict = OrbisStall.winStep(t, snapshot(el, t), now);
      if (verdict === "load") {
        // A live playlist joins the live edge again: no seek back.
        restart(el, t, { src: t.win.src, seekTo: null });
        return;
      }
      if (verdict === "wait") return;
      closeWindow(el, t);
      if (verdict === "dropped") dropped.add(el);
      if (verdict === "dropped" || verdict === "playing") signal(verdict);
    });
    if (active.size === 0 && windows.size === 0 && timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  }

  function openWindow(el) {
    dropped.delete(el);
    active.delete(el);
    windows.add(el);
    startTimer();
  }

  function onError(e) {
    var el = e.target;
    if (!isMedia(el)) return;
    var t = trackOf(el);
    if (OrbisStall.onError(t, snapshot(el, t), performance.now()) === "open") openWindow(el);
  }

  function reopen(el, cause) {
    var t = trackOf(el);
    if (!OrbisStall.reopen(t, snapshot(el, t), performance.now(), cause)) return;
    // The element is still paused by this script's last load(); that pause is not the user's.
    if (el.paused) t.pending = true;
    openWindow(el);
  }

  function onOnline() {
    scan();
    dropped.forEach(function (el) {
      reopen(el, "online");
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
      // Remembered so that "live" is still known after an error or a reload (NaN while loading changes nothing).
      if (!isMedia(e.target)) return;
      var d = e.target.duration;
      if (typeof d === "number" && !isNaN(d)) trackOf(e.target).live = d === Infinity;
    },
    true,
  );
  document.addEventListener(
    "progress",
    function (e) {
      if (!isMedia(e.target) || !tracks.has(e.target)) return;
      var t = tracks.get(e.target);
      OrbisStall.downloaded(t, performance.now());
      OrbisStall.onDownload(t, performance.now(), navigator.onLine);
    },
    true,
  );
  document.addEventListener(
    "play",
    function (e) {
      if (!isMedia(e.target) || !tracks.has(e.target)) return;
      var t = tracks.get(e.target);
      // The user or the page started playback itself: this script's restart no longer owns the pause.
      if (!t.win) t.pending = false;
      // After giving up, the user pressing play opens one fresh window.
      if (t.dropped) reopen(e.target, "play");
    },
    true,
  );
  window.addEventListener("online", onOnline);
  scan();
})();
