/* SPIKE live12-4s, never merged. Watches media-segment responses through webRequest.filterResponseData. When one that
 * has started goes silent for T ms, it fetches the rest of the same address once, on a new credential-less connection,
 * with Range + If-Range, and writes whichever source is ahead into the same response, then ends it.
 * Logs counters only: no address, no host. */
"use strict";

const NATIVE = "orbisSegRepl";
const C = Object.create(null);
let dirty = false;
function bump(key, n = 1) {
  C[key] = (C[key] || 0) + n;
  dirty = true;
}

let cfg = { enabled: false, tMs: 3000, perMinute: 6, maxZombies: 2, errorBackoffMs: 60000 };
let guard = null;
const live = new Map(); // requestId -> watch
const zombieHost = new Map(); // requestId -> host of an original left open after the replacement won
let tickTimer = null;

function applyConfig(c) {
  c = c || {};
  cfg = {
    enabled: c.enabled !== false,
    tMs: Number.isFinite(c.tMs) && c.tMs >= 250 ? c.tMs : 3000,
    perMinute: Number.isFinite(c.perMinute) ? c.perMinute : 6,
    maxZombies: Number.isFinite(c.maxZombies) ? c.maxZombies : 2,
    errorBackoffMs: Number.isFinite(c.errorBackoffMs) ? c.errorBackoffMs : 60000,
    shortFetch: c.shortFetch !== false,
  };
  C.cfg_enabled = cfg.enabled ? 1 : 0;
  C.cfg_short_fetch = cfg.shortFetch ? 1 : 0;
  C.cfg_t_ms = cfg.tMs;
  dirty = true;
  if (!cfg.enabled) return;
  guard = new SegCore.HostGuard({ perMinute: cfg.perMinute, errorBackoffMs: cfg.errorBackoffMs, maxZombies: cfg.maxZombies });
  const filter = { urls: ["<all_urls>"], types: ["xmlhttprequest", "media", "other"] };
  browser.webRequest.onHeadersReceived.addListener(onHeadersReceived, filter, ["blocking", "responseHeaders"]);
  browser.webRequest.onCompleted.addListener(onEnded, filter);
  browser.webRequest.onErrorOccurred.addListener(onEnded, filter);
}

function onEnded(d) {
  const host = zombieHost.get(d.requestId);
  if (host !== undefined) {
    zombieHost.delete(d.requestId);
    guard.endRequest(host, d.requestId);
    bump("zombie_ended");
  }
}

function onHeadersReceived(d) {
  const now = Date.now();
  if (d.statusCode >= 400) {
    guard.recordError(SegCore.hostOf(d.url), now);
    bump("error_status_seen");
    return;
  }
  const r = SegCore.planFromResponse(d);
  if (r.skip) {
    if (r.skip !== "not_media" && r.skip !== "status" && r.skip !== "method") bump("skip_" + r.skip);
    return;
  }
  let filter;
  try {
    filter = browser.webRequest.filterResponseData(d.requestId);
  } catch (e) {
    bump("filter_unavailable");
    return;
  }
  bump("watched");
  bump("watched_type_" + d.type);
  const w = {
    id: d.requestId,
    plan: r.plan,
    filter,
    origRecv: 0,
    last: now,
    started: false,
    fired: false,
    origStopped: false,
    repl: null,
    done: false,
  };
  w.splice = new SegCore.Splice(r.plan, {
    write: (b) => filter.write(b),
    close: () => filter.close(),
  });
  filter.onstart = () => {
    w.started = true;
    w.last = Date.now();
  };
  filter.ondata = (e) => {
    const chunk = new Uint8Array(e.data);
    const off = w.origRecv;
    w.origRecv += chunk.byteLength;
    w.last = Date.now();
    if (w.repl && w.repl.active && !w.repl.firstByte) {
      // The original came back before the replacement delivered anything: keep the original, drop the replacement.
      stopReplacement(w);
      bump("orig_resumed_first");
    }
    if (w.repl) bump("bytes_orig_after_fire", chunk.byteLength);
    if (!safeOffer(w, chunk, off)) return;
    if (w.splice.closed) finish(w, w.repl ? "orig_after_fire" : "orig");
  };
  filter.onstop = () => {
    w.origStopped = true;
    if (w.done) return;
    if (w.splice.closed) return finish(w, "orig");
    if (w.repl && w.repl.active) return; // the original ended short; the replacement may still complete it
    // Fable's ruling (b): a body that ends short of its declared length is treated like a hang (same guards).
    if (cfg.shortFetch && !w.fired && w.started) {
      bump("short_seen");
      if (fire(w, Date.now())) return;
    }
    w.splice.closeShort();
    bump("short_stop");
    finish(w, "orig_short");
  };
  filter.onerror = () => {
    bump("filter_error");
    if (w.repl && w.repl.active) stopReplacement(w);
    w.done = true;
    live.delete(w.id);
  };
  live.set(w.id, w);
  ensureTick();
}

function safeOffer(w, chunk, off) {
  try {
    w.splice.offer(chunk, off);
    return true;
  } catch (e) {
    bump("write_failed");
    if (w.repl && w.repl.active) stopReplacement(w);
    w.done = true;
    live.delete(w.id);
    return false;
  }
}

function ensureTick() {
  if (tickTimer === null) tickTimer = setInterval(tick, 250);
}

function tick() {
  const now = Date.now();
  for (const w of live.values()) {
    if (w.done || !w.started || w.fired || w.splice.closed) continue;
    if (now - w.last >= cfg.tMs) fire(w, now);
  }
  if (live.size === 0) {
    clearInterval(tickTimer);
    tickTimer = null;
    guard.prune(now);
  }
}

function fire(w, now) {
  w.fired = true; // one replacement per response, whatever happens next
  const host = w.plan.host;
  const why = guard.check(host, now);
  if (why) {
    bump("blocked_" + why);
    return false;
  }
  guard.recordFire(host, now);
  bump(w.origStopped ? "fired_short" : "fired");
  const { from, headers } = SegCore.replacementRequest(w.plan, w.splice.written);
  const ac = new AbortController();
  const repl = { ac, active: true, firstByte: false, from, t0: now };
  w.repl = repl;
  fetch(w.plan.url, {
    method: "GET",
    headers,
    credentials: "omit",
    cache: "no-store",
    redirect: "error",
    referrerPolicy: "no-referrer",
    signal: ac.signal,
  })
    .then(async (res) => {
      if (!repl.active) return;
      if (res.status >= 400) guard.recordError(host, Date.now());
      const reject = SegCore.acceptReplacement(w.plan, from, res.status, (n) => res.headers.get(n));
      if (reject) {
        bump("rejected_" + (reject.startsWith("status_") ? "status" : reject));
        stopReplacement(w);
        if (w.origStopped && !w.splice.closed) {
          w.splice.closeShort();
          finish(w, "orig_short");
        }
        return;
      }
      bump("accepted");
      const reader = res.body.getReader();
      let pos = from - w.plan.start;
      for (;;) {
        const { done, value } = await reader.read();
        if (!repl.active) return;
        if (done) break;
        if (!repl.firstByte) {
          repl.firstByte = true;
          bump("repl_first_byte");
          bump("repl_ttfb_ms_sum", Date.now() - repl.t0);
        }
        bump("bytes_repl", value.byteLength);
        const off = pos;
        pos += value.byteLength;
        if (!safeOffer(w, value, off)) return;
        if (w.splice.closed) return finish(w, "repl");
      }
      repl.active = false;
      if (!w.splice.closed && w.origStopped) {
        w.splice.closeShort();
        finish(w, "repl_short");
      }
    })
    .catch(() => {
      if (!repl.active) return; // we aborted it
      repl.active = false;
      bump("repl_failed");
      if (w.origStopped && !w.splice.closed) {
        w.splice.closeShort();
        finish(w, "orig_short");
      }
    });
  return true;
}

function stopReplacement(w) {
  if (!w.repl || !w.repl.active) return;
  w.repl.active = false;
  try {
    w.repl.ac.abort();
  } catch (e) {
    // ignore
  }
}

function finish(w, winner) {
  if (w.done) return;
  w.done = true;
  live.delete(w.id);
  stopReplacement(w);
  bump("done_" + winner);
  bump("bytes_dup", w.splice.dupBytes);
  if (winner.startsWith("repl") && !w.origStopped) {
    // The interface cannot cancel the original. It stays open until its server sends again (the engine then cancels
    // it because the filter is closed) or closes it. Track it so the zombie guard can stop further replacements.
    zombieHost.set(w.id, w.plan.host);
    guard.addZombie(w.plan.host, w.id);
    bump("zombie_left");
  }
}

setInterval(() => {
  if (!dirty) return;
  dirty = false;
  C.live_now = live.size;
  C.zombies_now = zombieHost.size;
  browser.runtime.sendNativeMessage(NATIVE, { type: "counters", c: C }).catch(() => {});
}, 5000);

browser.runtime
  .sendNativeMessage(NATIVE, { type: "config" })
  .then(applyConfig, () => applyConfig({}));
