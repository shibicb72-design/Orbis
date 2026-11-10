// Pure helpers for the YouTube ad estimate. No browser APIs, so node can unit-test them.
// The background script loads this first and uses the global OrbisYt.
var OrbisYt = (function () {
  var PING_ID_KEYS = ["ad_id", "adid", "ad_v", "ad_docid", "ad_cpn", "ad_break_id", "break_id", "breakid", "ai"];
  var HOLD_MS = 10000;
  var MARKER = "ytInitialPlayerResponse";

  function isVideoId(value) {
    return typeof value === "string" && /^[A-Za-z0-9_-]{11}$/.test(value);
  }

  function isUsableId(value) {
    if (typeof value !== "string") return false;
    var v = value.trim();
    if (v.length < 1 || v.length > 200) return false;
    if (v === "0" || v.toLowerCase() === "null" || v.toLowerCase() === "undefined") return false;
    return true;
  }

  function videoIdFromUrl(raw) {
    var url;
    try {
      url = new URL(String(raw || ""));
    } catch (e) {
      return null;
    }
    var host = url.hostname.toLowerCase().replace(/\.$/, "");
    if (!isYoutubePageHost(host)) return null;
    if (host === "youtu.be" || host.endsWith(".youtu.be")) {
      var shortId = url.pathname.split("/").filter(Boolean)[0] || "";
      return isVideoId(shortId) ? shortId : null;
    }
    var v = url.searchParams.get("v");
    if (isVideoId(v)) return v;
    var parts = url.pathname.split("/").filter(Boolean);
    var markers = { shorts: 1, embed: 1, live: 1, v: 1 };
    for (var i = 0; i < parts.length; i++) {
      if (markers[parts[i].toLowerCase()] && isVideoId(parts[i + 1])) return parts[i + 1];
    }
    return null;
  }

  function isYoutubePageHost(host) {
    return (
      host === "youtu.be" ||
      host.endsWith(".youtu.be") ||
      host === "youtube.com" ||
      host.endsWith(".youtube.com") ||
      host === "youtube-nocookie.com" ||
      host.endsWith(".youtube-nocookie.com")
    );
  }

  function isYoutubeHost(raw) {
    var url;
    try {
      url = new URL(String(raw || ""));
    } catch (e) {
      return false;
    }
    var host = url.hostname.toLowerCase().replace(/\.$/, "");
    return isYoutubePageHost(host) || host === "youtubei.googleapis.com" || host.endsWith(".youtubei.googleapis.com");
  }

  function exactPath(raw) {
    try {
      var path = new URL(String(raw || "")).pathname;
      if (path.length > 1 && path.charAt(path.length - 1) === "/") path = path.slice(0, -1);
      return path;
    } catch (e) {
      return "";
    }
  }

  // Exact paths only. /youtubei/v1/player/heartbeat is not the player response.
  function classifyRequest(raw, type) {
    if (!isYoutubeHost(raw)) return null;
    var path = exactPath(raw);
    var top = type === "main_frame";
    if (path === "/youtubei/v1/player" && !top) return "player";
    if (path === "/youtubei/v1/get_watch" && !top) return "player";
    // Shorts reel ads are not counted. /youtubei/v1/reel/reel_watch_sequence is left alone.
    if (path === "/watch" && !top) return "player";
    if (path === "/playlist" && !top) return "player";
    if (top && videoIdFromUrl(raw)) return "html";
    return null;
  }

  function isBreakPing(raw) {
    var url;
    try {
      url = new URL(String(raw || ""));
    } catch (e) {
      return false;
    }
    var host = url.hostname.toLowerCase();
    var path = url.pathname.toLowerCase();
    var onYoutube = isYoutubePageHost(host);
    if (path.indexOf("/api/stats/ads") !== -1) return true;
    if (path.indexOf("/pagead/adview") !== -1) return true;
    if (path.indexOf("/pagead/interaction") !== -1) return true;
    if (onYoutube && (path.indexOf("get_midroll") !== -1 || path.indexOf("ad_break") !== -1)) return true;
    return false;
  }

  function adIdFromUrl(raw) {
    var url;
    try {
      url = new URL(String(raw || ""));
    } catch (e) {
      return null;
    }
    for (var i = 0; i < PING_ID_KEYS.length; i++) {
      var value = url.searchParams.get(PING_ID_KEYS[i]);
      if (isUsableId(value)) return PING_ID_KEYS[i] + ":" + value.trim().slice(0, 120);
    }
    return null;
  }

  function titleForVideo(cached, videoId) {
    if (!cached || !videoId) return "";
    if (videoIdFromUrl(cached.url || "") !== videoId) return "";
    var title = String(cached.title || "").trim();
    if (!title || title === "null") return "";
    if (cached.staleTitle && title === String(cached.staleTitle).trim()) return "";
    return title.slice(0, 120);
  }

  // YouTube keeps the previous document title until the new one arrives. staleTitle stays rejected until a
  // different non-empty title arrives, or the video changes again.
  function titleForNavigation(previous, videoId, title) {
    var next = String(title || "");
    var trimmed = next.trim();
    if (!trimmed) return "";
    var stale = previous && String(previous.staleTitle || "").trim();
    if (stale && trimmed === stale) return "";
    if (
      previous &&
      videoId &&
      previous.videoId &&
      previous.videoId !== videoId &&
      trimmed === String(previous.title || "").trim()
    ) {
      return "";
    }
    return next;
  }

  // uBO's stream filter renames both "adPlacements" and "adSlots" to "no_ads". JSON.parse would keep only the last
  // duplicate key, so each key is renamed to no_ads#1, no_ads#2, … before parsing. String values are left alone.
  function aliasNoAds(text) {
    var raw = String(text || "");
    if (raw.indexOf("\"no_ads\"") === -1) return raw;
    var out = "";
    var n = 0;
    var inStr = false;
    var esc = false;
    var seg = 0;
    for (var i = 0; i < raw.length; i++) {
      var c = raw.charAt(i);
      if (inStr) {
        if (esc) esc = false;
        else if (c === "\\") esc = true;
        else if (c === "\"") inStr = false;
        continue;
      }
      if (c === "\"" && raw.substr(i, 8) === "\"no_ads\"") {
        var j = i + 8;
        var k = j;
        while (k < raw.length && raw.charCodeAt(k) <= 32) k++;
        if (raw.charAt(k) === ":") {
          n++;
          out += raw.slice(seg, i) + "\"no_ads#" + n + "\"";
          i = j - 1;
          seg = j;
          continue;
        }
      }
      if (c === "\"") inStr = true;
    }
    return out + raw.slice(seg);
  }

  function findField(obj, key, depth, allowZero) {
    if (!obj || typeof obj !== "object" || depth > 8) return null;
    if (!Array.isArray(obj) && Object.prototype.hasOwnProperty.call(obj, key)) {
      var value = obj[key];
      if (typeof value === "number" && isFinite(value) && (allowZero ? value >= 0 : value > 0)) return String(value);
      if (typeof value === "string") {
        var trimmed = value.trim();
        if (allowZero ? trimmed.length > 0 && trimmed.length <= 80 : isUsableId(trimmed)) return trimmed;
      }
    }
    var kids = Array.isArray(obj) ? obj : Object.keys(obj).map(function (name) { return obj[name]; });
    for (var i = 0; i < kids.length; i++) {
      var found = findField(kids[i], key, depth + 1, allowZero);
      if (found != null) return found;
    }
    return null;
  }

  function hasKey(obj, key, depth) {
    if (!obj || typeof obj !== "object" || depth > 8) return false;
    if (!Array.isArray(obj) && Object.prototype.hasOwnProperty.call(obj, key)) return true;
    var kids = Array.isArray(obj) ? obj : Object.keys(obj).map(function (name) { return obj[name]; });
    for (var i = 0; i < kids.length; i++) {
      if (hasKey(kids[i], key, depth + 1)) return true;
    }
    return false;
  }

  function isForecasting(entry) {
    return hasKey(entry, "clientForecastingAdRenderer", 0);
  }

  function isPlayerBytes(entry) {
    var type = findField(entry, "slotType", 0, false);
    if (type && String(type).indexOf("PLAYER_BYTES") !== -1) return true;
    return hasKey(entry, "playerBytesAdLayoutRenderer", 0);
  }

  function breakId(entry) {
    if (!entry || typeof entry !== "object" || isForecasting(entry)) return null;
    var kind = findField(entry, "kind", 0, false);
    if (kind && kind.indexOf("AD_PLACEMENT") === -1 && kind.indexOf("SLOT_") === -1) kind = null;
    var slotType = findField(entry, "slotType", 0, false);
    var offset = findField(entry, "offsetStartMilliseconds", 0, true);
    if (offset == null) offset = findField(entry, "offsetStartMs", 0, true);
    if (offset == null) offset = findField(entry, "startTimeMilliseconds", 0, true);
    var at = offset == null ? "0" : offset;
    if (isPlayerBytes(entry)) {
      var id = (kind || slotType || "PLAYER_BYTES") + ":" + at;
      if (offset == null) {
        var slotId = findField(entry, "slotId", 0, false);
        if (slotId) id += ":" + slotId;
      }
      return id;
    }
    if (kind) return kind + ":" + at;
    if (hasKey(entry, "adPlacementRenderer", 0) && offset != null) return "break:" + offset;
    return null;
  }

  function dedupe(entries) {
    var out = [];
    var seen = {};
    for (var i = 0; i < entries.length; i++) {
      var id = breakId(entries[i]);
      if (!id || seen[id]) continue;
      seen[id] = true;
      out.push(id);
    }
    return out;
  }

  function adLists(player) {
    var slots = [];
    var placements = [];
    if (Array.isArray(player.adSlots)) slots.push(player.adSlots);
    if (Array.isArray(player.adPlacements)) placements.push(player.adPlacements);
    Object.keys(player).forEach(function (key) {
      if (key.indexOf("no_ads#") !== 0 || !Array.isArray(player[key])) return;
      var list = player[key];
      var asSlots = false;
      var asPlacements = false;
      for (var i = 0; i < list.length; i++) {
        if (list[i] && (list[i].adSlotRenderer || isPlayerBytes(list[i]))) asSlots = true;
        if (list[i] && list[i].adPlacementRenderer) asPlacements = true;
      }
      if (asSlots) slots.push(list);
      else if (asPlacements) placements.push(list);
      else placements.push(list);
    });
    return { slots: slots, placements: placements };
  }

  function breaksFromPlayer(player) {
    var lists = adLists(player);
    var bytes = [];
    lists.slots.forEach(function (list) {
      list.forEach(function (entry) {
        if (isPlayerBytes(entry) && !isForecasting(entry)) bytes.push(entry);
      });
    });
    if (bytes.length) return dedupe(bytes);
    var places = [];
    lists.placements.forEach(function (list) {
      list.forEach(function (entry) {
        if (!isForecasting(entry)) places.push(entry);
      });
    });
    return dedupe(places);
  }

  function videoIdOf(player) {
    var id = player.videoDetails && player.videoDetails.videoId;
    if (isVideoId(id)) return id;
    var nested = player.currentVideoEndpoint && player.currentVideoEndpoint.watchEndpoint;
    id = nested && nested.videoId;
    return isVideoId(id) ? id : null;
  }

  function collectPlayers(root, out) {
    if (!root || typeof root !== "object") return;
    if (Array.isArray(root)) {
      root.forEach(function (item) { collectPlayers(item, out); });
      return;
    }
    if (root.playerResponse && typeof root.playerResponse === "object") {
      collectPlayers(root.playerResponse, out);
      return;
    }
    if (root.videoDetails || root.adPlacements || root.adSlots || Object.keys(root).some(function (key) { return key.indexOf("no_ads#") === 0; })) {
      out.push(root);
    }
  }

  function slotsFromPlayerObject(root) {
    var players = [];
    collectPlayers(root, players);
    if (!players.length) return null;
    var videoId = null;
    var title = "";
    var slots = [];
    var seen = {};
    players.forEach(function (player) {
      var id = videoIdOf(player);
      if (!videoId && id) videoId = id;
      if (!title && player.videoDetails && player.videoDetails.title) title = String(player.videoDetails.title);
      breaksFromPlayer(player).forEach(function (breakId) {
        if (seen[breakId]) return;
        seen[breakId] = true;
        slots.push(breakId);
      });
    });
    if (!videoId) return null;
    return { videoId: videoId, title: title.trim().slice(0, 120), slots: slots };
  }

  function slotsFromPlayer(jsonText) {
    try {
      return slotsFromPlayerObject(JSON.parse(aliasNoAds(jsonText)));
    } catch (e) {
      return null;
    }
  }

  function createHtmlScan() {
    var phase = "seek";
    var tail = "";
    var json = "";
    var depth = 0;
    var inStr = false;
    var esc = false;
    var sawEq = false;

    function tooBig() {
      if (json.length <= 4 * 1024 * 1024) return false;
      phase = "dead";
      json = "";
      return true;
    }

    function feedBody(text) {
      if (phase !== "body" || !text) return;
      if (!inStr && text.indexOf("\"") === -1 && text.indexOf("{") === -1 && text.indexOf("}") === -1) {
        json += text;
        tooBig();
        return;
      }
      var start = 0;
      for (var i = 0; i < text.length; i++) {
        var c = text.charAt(i);
        if (inStr) {
          if (esc) esc = false;
          else if (c === "\\") esc = true;
          else if (c === "\"") inStr = false;
          continue;
        }
        if (c === "\"") {
          inStr = true;
          continue;
        }
        if (c === "{") {
          depth++;
          continue;
        }
        if (c === "}") {
          depth--;
          if (depth === 0) {
            json += text.slice(start, i + 1);
            if (!tooBig()) phase = "done";
            return;
          }
        }
      }
      json += text.slice(start);
      tooBig();
    }

    function feedPre(text) {
      for (var i = 0; i < text.length; i++) {
        if (phase === "body" || phase === "done" || phase === "dead") {
          if (phase === "body") feedBody(text.slice(i));
          return;
        }
        if (phase !== "pre") return;
        var code = text.charCodeAt(i);
        if (code <= 32) continue;
        var c = text.charAt(i);
        if (!sawEq && c === "=") {
          sawEq = true;
          continue;
        }
        if (sawEq && c === "{") {
          phase = "body";
          json = "{";
          depth = 1;
          inStr = false;
          esc = false;
          feedBody(text.slice(i + 1));
          return;
        }
        phase = "seek";
        sawEq = false;
        tail = "";
        seek(text.slice(i));
        return;
      }
    }

    function seek(chunk) {
      tail += chunk;
      var at = tail.indexOf(MARKER);
      if (at < 0) {
        var keep = MARKER.length - 1;
        if (tail.length > keep) tail = tail.slice(tail.length - keep);
        return;
      }
      var rest = tail.slice(at + MARKER.length);
      tail = "";
      phase = "pre";
      sawEq = false;
      feedPre(rest);
    }

    return {
      push: function (chunk) {
        if (phase === "done" || phase === "dead") return;
        var text = String(chunk || "");
        if (phase === "seek") seek(text);
        else if (phase === "pre") feedPre(text);
        else if (phase === "body") feedBody(text);
      },
      jsonText: function () {
        return phase === "done" ? json : null;
      },
    };
  }

  function extractPlayerJsonFromHtml(text) {
    var scan = createHtmlScan();
    scan.push(text);
    return scan.jsonText();
  }

  // Holds a parsed player response until the tab's top-level URL is that video, for up to 10 seconds.
  function createPageTracker() {
    var tabs = new Map();
    var held = [];
    var sent = new Map();
    var seq = 1;

    function slotMap(list) {
      var out = {};
      (list || []).forEach(function (id) { if (id) out[id] = true; });
      return out;
    }

    function hold(tabId, videoId, parsed, now) {
      var existing = null;
      for (var i = 0; i < held.length; i++) {
        if (held[i].tabId === tabId && held[i].videoId === videoId) existing = held[i];
      }
      if (!existing) {
        existing = { tabId: tabId, videoId: videoId, title: "", slots: {}, at: now };
        held.push(existing);
      }
      Object.keys(slotMap(parsed.slots)).forEach(function (id) { existing.slots[id] = true; });
      if (parsed.title) existing.title = parsed.title;
    }

    function emit(tabId, videoId, parsed, top, now) {
      var key = String(tabId);
      var prev = sent.get(key);
      var incoming = slotMap(parsed.slots);
      var novel = {};
      if (prev && prev.viewToken === top.viewToken && prev.videoId === videoId) {
        Object.keys(incoming).forEach(function (id) {
          if (!prev.slots[id]) novel[id] = true;
        });
        if (!Object.keys(novel).length) return [];
        Object.keys(novel).forEach(function (id) { prev.slots[id] = true; });
      } else {
        novel = incoming;
        sent.set(key, { videoId: videoId, viewToken: top.viewToken, slots: Object.assign({}, novel) });
      }
      return [{
        type: "player-ads",
        videoId: videoId,
        title: parsed.title || top.title || "",
        pageUrl: top.url,
        slots: Object.keys(novel),
        viewToken: top.viewToken,
        at: now,
        incognito: false,
      }];
    }

    function flush(tabId, now) {
      var top = tabs.get(tabId);
      if (!top || !top.youtube || !top.videoId) return [];
      var out = [];
      var keep = [];
      held.forEach(function (item) {
        if (item.tabId !== tabId) {
          keep.push(item);
          return;
        }
        if (now - item.at > HOLD_MS) return;
        if (item.videoId !== top.videoId) {
          keep.push(item);
          return;
        }
        out = out.concat(emit(tabId, item.videoId, { title: item.title, slots: Object.keys(item.slots) }, top, now));
      });
      held = keep;
      return out;
    }

    return {
      HOLD_MS: HOLD_MS,
      top: function (tabId) { return tabs.get(tabId) || null; },
      setTopUrl: function (tabId, url, title, now) {
        var prev = tabs.get(tabId);
        var videoId = videoIdFromUrl(url);
        var youtube = isYoutubeHost(url) && isYoutubePageHost((function () {
          try { return new URL(url).hostname.toLowerCase(); } catch (e) { return ""; }
        })());
        var token = prev && prev.videoId === videoId && videoId ? prev.viewToken : (youtube && videoId ? "view-" + videoId + "-" + now + "-" + seq++ : null);
        var nextVideo = youtube ? videoId : null;
        var changed = !!(prev && nextVideo && prev.videoId && prev.videoId !== nextVideo);
        var storedTitle = titleForNavigation(prev, nextVideo, title || "");
        var staleTitle = "";
        if (String(storedTitle || "").trim()) staleTitle = "";
        else if (changed) staleTitle = String(prev.title || "").trim() || String(prev.staleTitle || "").trim();
        else if (prev) staleTitle = String(prev.staleTitle || "").trim();
        tabs.set(tabId, {
          url: url || "",
          title: storedTitle,
          staleTitle: staleTitle,
          videoId: nextVideo,
          viewToken: token,
          youtube: !!youtube,
        });
        if (!youtube) {
          held = held.filter(function (item) { return item.tabId !== tabId; });
          return [];
        }
        var out = [];
        if (videoId && (!prev || prev.videoId !== videoId || !prev.youtube)) {
          out.push({
            type: "view-start",
            videoId: videoId,
            pageUrl: url,
            title: storedTitle,
            viewToken: token,
            at: now,
            incognito: false,
          });
        }
        return out.concat(flush(tabId, now));
      },
      pushParsed: function (tabId, parsed, isTopFrame, now) {
        if (!parsed) return [];
        var top = tabs.get(tabId);
        if (top && !top.youtube) return [];
        if (!top && !isTopFrame) return [];
        var videoId = parsed.videoId;
        if (!videoId) return [];
        var body = { videoId: videoId, title: parsed.title || "", slots: parsed.slots || [] };
        if (top && top.youtube && top.videoId === videoId) return emit(tabId, videoId, body, top, now);
        hold(tabId, videoId, body, now);
        return [];
      },
      tick: function (now) {
        held = held.filter(function (item) { return now - item.at <= HOLD_MS; });
        return [];
      },
      removeTab: function (tabId) {
        tabs.delete(tabId);
        sent.delete(String(tabId));
        held = held.filter(function (item) { return item.tabId !== tabId; });
      },
    };
  }

  return {
    HOLD_MS: HOLD_MS,
    isVideoId: isVideoId,
    videoIdFromUrl: videoIdFromUrl,
    isYoutubeHost: isYoutubeHost,
    classifyRequest: classifyRequest,
    isBreakPing: isBreakPing,
    adIdFromUrl: adIdFromUrl,
    titleForVideo: titleForVideo,
    titleForNavigation: titleForNavigation,
    aliasNoAds: aliasNoAds,
    slotsFromPlayer: slotsFromPlayer,
    createHtmlScan: createHtmlScan,
    extractPlayerJsonFromHtml: extractPlayerJsonFromHtml,
    createPageTracker: createPageTracker,
  };
})();
