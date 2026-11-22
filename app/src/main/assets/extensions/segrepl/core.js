/* SPIKE live12-4s, never merged. Pure logic of the segment replacement prototype: which responses qualify, the range
 * a replacement asks for, whether its answer continues exactly where the original stopped, and the per-host guards.
 * No browser API in this file, so it runs under node for the host-side checks. */
(function (root) {
  "use strict";

  const MIN_BODY = 16 * 1024;
  const SEGMENT_EXT = /\.(ts|m4s|mp4|m4v|m4a|aac|mp3|webm|cmfv|cmfa|fmp4|mkv|ogg|opus)$/i;
  const PLAYLIST_EXT = /\.(m3u8?|mpd|f4m|ism|isml)$/i;
  const PLAYLIST_TYPE = /mpegurl|dash\+xml|vnd\.ms-sstr|f4m/i;
  const MEDIA_TYPE = /^(video|audio)\//i;
  const GENERIC_TYPE = /^(application\/octet-stream|binary\/octet-stream|application\/mp4)$/i;

  function header(headers, name) {
    const n = name.toLowerCase();
    for (const h of headers || []) {
      if (h.name.toLowerCase() === n) return h.value === undefined ? "" : String(h.value);
    }
    return null;
  }

  function urlPath(url) {
    try {
      return new URL(url).pathname;
    } catch (e) {
      return "";
    }
  }

  function hostOf(url) {
    try {
      return new URL(url).host;
    } catch (e) {
      return "";
    }
  }

  // Returns { plan } when the response may be watched, or { skip: "<reason>" }. The plan holds what a replacement needs.
  function planFromResponse(d) {
    if (d.method !== "GET") return { skip: "method" };
    if (d.statusCode !== 200 && d.statusCode !== 206) return { skip: "status" };
    if (d.incognito) return { skip: "private" };
    if (typeof d.tabId === "number" && d.tabId < 0) return { skip: "no_tab" };
    const headers = d.responseHeaders || [];
    const path = urlPath(d.url);
    const ctype = ((header(headers, "content-type") || "").split(";")[0] || "").trim();
    if (PLAYLIST_EXT.test(path) || PLAYLIST_TYPE.test(ctype)) return { skip: "playlist" };
    const mediaByType = MEDIA_TYPE.test(ctype);
    const mediaByPath = SEGMENT_EXT.test(path) && (ctype === "" || GENERIC_TYPE.test(ctype) || mediaByType);
    if (!mediaByType && !mediaByPath) return { skip: "not_media" };

    const enc = (header(headers, "content-encoding") || "").trim().toLowerCase();
    if (enc && enc !== "identity") return { skip: "encoded" };
    const ranges = (header(headers, "accept-ranges") || "").toLowerCase();
    if (!ranges.split(",").map((s) => s.trim()).includes("bytes")) return { skip: "no_ranges" };

    const etagRaw = header(headers, "etag");
    const etag = etagRaw && !/^\s*W\//.test(etagRaw) ? etagRaw.trim() : null;
    const lastModified = header(headers, "last-modified");
    const validator = etag || (lastModified ? lastModified.trim() : null);
    if (!validator) return { skip: etagRaw ? "weak_etag_only" : "no_validator" };

    const lenRaw = header(headers, "content-length");
    if (lenRaw === null || !/^\d+$/.test(lenRaw.trim())) return { skip: "no_length" };
    const length = Number(lenRaw.trim());
    if (length < MIN_BODY) return { skip: "small" };

    let start = 0;
    let end = length - 1;
    let total = length;
    if (d.statusCode === 206) {
      const cr = parseContentRange(header(headers, "content-range"));
      if (!cr || cr.end - cr.start + 1 !== length) return { skip: "bad_content_range" };
      start = cr.start;
      end = cr.end;
      total = cr.total; // may be null ("*")
    }
    return {
      plan: { url: d.url, host: hostOf(d.url), start, end, total, length, etag, validator },
    };
  }

  function parseContentRange(v) {
    if (!v) return null;
    const m = /^\s*bytes\s+(\d+)-(\d+)\/(\d+|\*)\s*$/i.exec(v);
    if (!m) return null;
    const start = Number(m[1]);
    const end = Number(m[2]);
    if (end < start) return null;
    return { start, end, total: m[3] === "*" ? null : Number(m[3]) };
  }

  // The only headers the prototype adds; everything else is what the engine sends for a credential-less fetch.
  function replacementRequest(plan, written) {
    const from = plan.start + written;
    return {
      from,
      headers: { Range: "bytes=" + from + "-" + plan.end, "If-Range": plan.validator },
    };
  }

  // A replacement answer is accepted only as a 206 that starts at exactly `from`, ends where the original ends, has the
  // same total and (when the original had one) the same strong ETag.
  function acceptReplacement(plan, from, status, getHeader) {
    if (status !== 206) return "status_" + status;
    const cr = parseContentRange(getHeader("content-range"));
    if (!cr) return "no_content_range";
    if (cr.start !== from || cr.end !== plan.end) return "range_mismatch";
    const expectTotal = plan.total;
    if (expectTotal !== null && cr.total !== null && cr.total !== expectTotal) return "total_mismatch";
    if (plan.etag) {
      const et = getHeader("etag");
      if (et !== null && et.trim() !== plan.etag) return "etag_mismatch";
    }
    const enc = (getHeader("content-encoding") || "").trim().toLowerCase();
    if (enc && enc !== "identity") return "encoded";
    return null;
  }

  // Per-host guards: at most `perMinute` replacements in any 60 s, none while the host answered an error status in the
  // last `errorBackoffMs`, none while `maxZombies` originals we gave up on are still open on that host.
  class HostGuard {
    constructor({ perMinute = 6, errorBackoffMs = 60000, maxZombies = 2 } = {}) {
      this.perMinute = perMinute;
      this.errorBackoffMs = errorBackoffMs;
      this.maxZombies = maxZombies;
      this.fired = new Map(); // host -> [times]
      this.lastError = new Map(); // host -> time
      this.zombies = new Map(); // host -> Set(requestId)
    }
    check(host, now) {
      const err = this.lastError.get(host);
      if (err !== undefined && now - err < this.errorBackoffMs) return "error_backoff";
      const z = this.zombies.get(host);
      if (z && z.size >= this.maxZombies) return "zombie_guard";
      const times = (this.fired.get(host) || []).filter((t) => now - t < 60000);
      this.fired.set(host, times);
      if (times.length >= this.perMinute) return "rate_limited";
      return null;
    }
    recordFire(host, now) {
      const times = this.fired.get(host) || [];
      times.push(now);
      this.fired.set(host, times);
    }
    recordError(host, now) {
      this.lastError.set(host, now);
    }
    addZombie(host, requestId) {
      if (!this.zombies.has(host)) this.zombies.set(host, new Set());
      this.zombies.get(host).add(requestId);
    }
    endRequest(host, requestId) {
      const z = this.zombies.get(host);
      if (!z) return false;
      const had = z.delete(requestId);
      if (z.size === 0) this.zombies.delete(host);
      return had;
    }
    prune(now) {
      for (const [h, t] of this.lastError) if (now - t >= this.errorBackoffMs) this.lastError.delete(h);
      for (const [h, ts] of this.fired) {
        const keep = ts.filter((t) => now - t < 60000);
        if (keep.length) this.fired.set(h, keep);
        else this.fired.delete(h);
      }
    }
  }

  // One watched response. Both sources (the original and the single replacement) offer contiguous chunks with their
  // body offset; only bytes beyond what was already delivered are written, so whichever source is ahead wins and
  // nothing is written twice. `sink.write(Uint8Array)` and `sink.close()` are the stream filter's.
  class Splice {
    constructor(plan, sink) {
      this.plan = plan;
      this.sink = sink;
      this.written = 0;
      this.dupBytes = 0;
      this.closed = false;
    }
    offer(chunk, offset) {
      if (this.closed) {
        this.dupBytes += chunk.byteLength;
        return false;
      }
      const endOff = offset + chunk.byteLength;
      if (offset > this.written) throw new Error("gap in source");
      if (endOff <= this.written) {
        this.dupBytes += chunk.byteLength;
        return false;
      }
      const skip = this.written - offset;
      this.dupBytes += skip;
      const part = skip > 0 ? chunk.slice(skip) : chunk;
      this.sink.write(part);
      this.written += part.byteLength;
      if (this.written >= this.plan.length) {
        this.closed = true;
        this.sink.close();
      }
      return true;
    }
    complete() {
      return this.written >= this.plan.length;
    }
    closeShort() {
      if (this.closed) return;
      this.closed = true;
      this.sink.close();
    }
  }

  const api = { MIN_BODY, header, hostOf, planFromResponse, parseContentRange, replacementRequest, acceptReplacement, HostGuard, Splice };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.SegCore = api;
})(this);
