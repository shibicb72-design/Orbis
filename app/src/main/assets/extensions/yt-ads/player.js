// Pure helpers for the YouTube ad estimate. No browser APIs, so node can unit-test them.
// The background script loads this first and uses the global OrbisYt.
var OrbisYt = (function () {
  var ID_KEYS = ["slotId", "adBreakId", "breakId", "adVideoId", "externalVideoId", "creativeId", "adId"];
  var PING_ID_KEYS = ["ad_id", "adid", "ad_v", "ad_docid", "ad_cpn", "ad_break_id", "break_id", "breakid", "ai"];

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
    var youtube =
      host === "youtu.be" ||
      host.endsWith(".youtu.be") ||
      host === "youtube.com" ||
      host.endsWith(".youtube.com") ||
      host === "youtube-nocookie.com" ||
      host.endsWith(".youtube-nocookie.com");
    if (!youtube) return null;
    if (host === "youtu.be" || host.endsWith(".youtu.be")) {
      var shortId = (url.pathname.split("/").filter(Boolean)[0] || "");
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

  function isYoutubeHost(raw) {
    var url;
    try {
      url = new URL(String(raw || ""));
    } catch (e) {
      return false;
    }
    var host = url.hostname.toLowerCase().replace(/\.$/, "");
    return (
      host === "youtu.be" ||
      host.endsWith(".youtu.be") ||
      host === "youtube.com" ||
      host.endsWith(".youtube.com") ||
      host === "youtube-nocookie.com" ||
      host.endsWith(".youtube-nocookie.com") ||
      host === "youtubei.googleapis.com" ||
      host.endsWith(".youtubei.googleapis.com")
    );
  }

  // Break-specific endpoints only. Generic ad hosts (/pagead/id, ad_status.js, googlesyndication) are not breaks.
  function isBreakPing(raw) {
    var url;
    try {
      url = new URL(String(raw || ""));
    } catch (e) {
      return false;
    }
    var host = url.hostname.toLowerCase();
    var path = url.pathname.toLowerCase();
    var onYoutube =
      host === "youtube.com" ||
      host.endsWith(".youtube.com") ||
      host === "youtube-nocookie.com" ||
      host.endsWith(".youtube-nocookie.com");
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
      var key = PING_ID_KEYS[i];
      var value = url.searchParams.get(key);
      if (isUsableId(value)) return key + ":" + value.trim().slice(0, 120);
    }
    return null;
  }

  function titleForVideo(cached, videoId) {
    if (!cached || !videoId) return "";
    if (videoIdFromUrl(cached.url || "") !== videoId) return "";
    var title = String(cached.title || "").trim();
    if (!title || title === "null") return "";
    return title.slice(0, 120);
  }

  function findKey(obj, key, depth) {
    if (!obj || typeof obj !== "object" || depth > 12) return null;
    if (!Array.isArray(obj) && Object.prototype.hasOwnProperty.call(obj, key)) {
      var value = obj[key];
      if (typeof value === "string" && isUsableId(value)) return value.trim();
      if (typeof value === "number" && isFinite(value) && value > 0) return String(value);
    }
    var kids = Array.isArray(obj) ? obj : Object.keys(obj).map(function (k) { return obj[k]; });
    for (var i = 0; i < kids.length; i++) {
      var found = findKey(kids[i], key, depth + 1);
      if (found) return found;
    }
    return null;
  }

  function slotId(entry, source, index) {
    if (!entry || typeof entry !== "object") return null;
    for (var i = 0; i < ID_KEYS.length; i++) {
      var value = findKey(entry, ID_KEYS[i], 0);
      if (value) return ID_KEYS[i] + ":" + value.slice(0, 120);
    }
    var kind = findKey(entry, "kind", 0);
    var offset = findKey(entry, "offsetStartMilliseconds", 0) || findKey(entry, "offsetStartMs", 0);
    if (kind || offset) return "place:" + (kind || "ad") + ":" + (offset || "0");
    if (Object.keys(entry).length === 0) return null;
    return "entry:" + source + ":" + index;
  }

  function slotsFromPlayerObject(root) {
    if (!root || typeof root !== "object") return null;
    var player = root.videoDetails || root.adPlacements || root.playerAds || root.adSlots ? root : root.playerResponse;
    if (!player || typeof player !== "object") return null;
    var videoId = player.videoDetails && player.videoDetails.videoId;
    if (!isVideoId(videoId)) {
      var nested = player.currentVideoEndpoint && player.currentVideoEndpoint.watchEndpoint;
      videoId = nested && nested.videoId;
    }
    if (!isVideoId(videoId)) return null;
    var title = (player.videoDetails && player.videoDetails.title) || "";
    var slots = [];
    var seen = {};
    var sources = ["adPlacements", "playerAds", "adSlots"];
    for (var s = 0; s < sources.length; s++) {
      var list = player[sources[s]];
      if (!Array.isArray(list)) continue;
      for (var i = 0; i < list.length; i++) {
        var id = slotId(list[i], sources[s], i);
        if (!id || seen[id]) continue;
        seen[id] = true;
        slots.push(id);
      }
    }
    return { videoId: videoId, title: String(title || "").trim().slice(0, 120), slots: slots };
  }

  function slotsFromPlayer(jsonText) {
    try {
      return slotsFromPlayerObject(JSON.parse(jsonText));
    } catch (e) {
      return null;
    }
  }

  function sliceJsonObject(text, start) {
    var depth = 0;
    var inStr = false;
    var esc = false;
    for (var i = start; i < text.length; i++) {
      var c = text[i];
      if (inStr) {
        if (esc) esc = false;
        else if (c === "\\") esc = true;
        else if (c === "\"") inStr = false;
        continue;
      }
      if (c === "\"") inStr = true;
      else if (c === "{") depth++;
      else if (c === "}") {
        depth--;
        if (depth === 0) return text.slice(start, i + 1);
      }
    }
    return null;
  }

  function extractPlayerJsonFromHtml(text) {
    var marker = "ytInitialPlayerResponse";
    var from = 0;
    var raw = String(text || "");
    while (from < raw.length) {
      var at = raw.indexOf(marker, from);
      if (at < 0) return null;
      var i = at + marker.length;
      while (i < raw.length && raw.charCodeAt(i) <= 32) i++;
      if (raw[i] !== "=") {
        from = at + marker.length;
        continue;
      }
      i++;
      while (i < raw.length && raw.charCodeAt(i) <= 32) i++;
      if (raw[i] !== "{") {
        from = at + marker.length;
        continue;
      }
      return sliceJsonObject(raw, i);
    }
    return null;
  }

  return {
    isVideoId: isVideoId,
    videoIdFromUrl: videoIdFromUrl,
    isYoutubeHost: isYoutubeHost,
    isBreakPing: isBreakPing,
    adIdFromUrl: adIdFromUrl,
    titleForVideo: titleForVideo,
    slotsFromPlayer: slotsFromPlayer,
    extractPlayerJsonFromHtml: extractPlayerJsonFromHtml,
  };
})();
