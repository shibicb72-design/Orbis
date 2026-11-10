// Observes YouTube ad slots and reports them to Orbis. Passes every response body through unchanged.
// Does not block, redirect, or rewrite a request, and does not run in private tabs.
//
// GeckoView 159 (Firefox 159) exposes webRequest.filterResponseData to manifest v2 extensions that have the
// webRequestBlocking permission. Checked in this engine's omni.ja (ext-webRequest.js): manifest v3 would also need
// webRequestFilterResponse; this extension is manifest v2, so webRequestBlocking is enough. If the method is missing
// or throws, this script does nothing to the request and the blocked-ping fallback still runs.
(() => {
  const BLOCKED = {
    NS_ERROR_ABORT: true,
    NS_ERROR_BLOCKED_BY_CONTENT_BLOCKING: true,
    NS_ERROR_BLOCKED_BY_POLICY: true,
    NS_ERROR_TRACKING_URI: true,
  };
  const CAP = 4 * 1024 * 1024;

  const titles = new Map();

  function isBlocked(error) {
    const e = String(error || "").toUpperCase();
    if (!e || e.startsWith("NS_ERROR_NET_") || e.startsWith("NS_ERROR_CONNECTION_") || e.startsWith("NS_ERROR_OFFLINE")) {
      return false;
    }
    return !!(BLOCKED[e] || e.startsWith("NS_ERROR_BLOCKED") || e.indexOf("CONTENT_BLOCKING") !== -1 || e.indexOf("TRACKING_URI") !== -1);
  }

  let port = null;
  function send(message) {
    try {
      if (!port) {
        port = browser.runtime.connectNative("orbisYoutubeAds");
        port.onDisconnect.addListener(() => {
          port = null;
        });
      }
      port.postMessage(message);
    } catch (e) {
      port = null;
    }
  }

  browser.runtime.onMessage.addListener((msg, sender) => {
    if (!msg || msg.type !== "title" || !sender.tab || sender.tab.incognito) return;
    const tabId = sender.tab.id;
    const url = msg.url || "";
    if (!OrbisYt.videoIdFromUrl(url)) {
      titles.delete(tabId);
      return;
    }
    titles.set(tabId, { url: url, title: msg.title || "" });
  });

  try {
    browser.tabs.onRemoved.addListener((tabId) => {
      titles.delete(tabId);
    });
  } catch (e) {}

  function onError(details) {
    try {
      if (!details || !details.url || details.incognito || details.type === "main_frame") return;
      if (!isBlocked(details.error) || !OrbisYt.isBreakPing(details.url) || !OrbisYt.adIdFromUrl(details.url)) return;
      const page = details.documentUrl || details.originUrl || "";
      const videoId = OrbisYt.videoIdFromUrl(page);
      if (!videoId) return;
      send({
        type: "blocked-ad",
        url: details.url,
        documentUrl: page,
        title: OrbisYt.titleForVideo(titles.get(details.tabId), videoId),
        error: details.error || "",
        incognito: false,
        at: Date.now(),
      });
    } catch (e) {}
  }

  function responseKind(details) {
    if (!details || !details.url || details.incognito) return null;
    let url;
    try {
      url = new URL(details.url);
    } catch (e) {
      return null;
    }
    if (!OrbisYt.isYoutubeHost(details.url)) return null;
    const path = url.pathname.toLowerCase();
    if (path.indexOf("/youtubei/v1/player") !== -1) return "player";
    if (details.type === "main_frame" && OrbisYt.videoIdFromUrl(details.url)) return "html";
    return null;
  }

  function reportPlayer(kind, details, body) {
    const parsed = OrbisYt.slotsFromPlayer(body);
    if (!parsed) return;
    const page = details.documentUrl || details.originUrl || (kind === "html" ? details.url : "") || "";
    const pageVideo = OrbisYt.videoIdFromUrl(page);
    if (kind === "html" && pageVideo !== parsed.videoId) return;
    if (page && !OrbisYt.isYoutubeHost(page) && !OrbisYt.videoIdFromUrl(page)) return;
    const cached = OrbisYt.titleForVideo(titles.get(details.tabId), parsed.videoId);
    send({
      type: "player-ads",
      videoId: parsed.videoId,
      title: parsed.title || cached || "",
      pageUrl: pageVideo === parsed.videoId ? page : "",
      slots: parsed.slots,
      incognito: false,
      at: Date.now(),
    });
  }

  function onBeforeRequest(details) {
    const kind = responseKind(details);
    if (!kind) return;
    if (!browser.webRequest || typeof browser.webRequest.filterResponseData !== "function") return;
    let filter;
    try {
      filter = browser.webRequest.filterResponseData(details.requestId);
    } catch (e) {
      return;
    }
    const state = { text: "", json: null, ignore: false, done: false };
    const decoder = new TextDecoder("utf-8");
    filter.ondata = (event) => {
      if (state.done) return;
      try {
        filter.write(event.data);
      } catch (e) {
        state.done = true;
        try { filter.disconnect(); } catch (e2) {}
        return;
      }
      if (state.ignore) return;
      try {
        state.text += decoder.decode(event.data, { stream: true });
        if (kind === "html") {
          const extracted = OrbisYt.extractPlayerJsonFromHtml(state.text);
          if (extracted) {
            state.json = extracted;
            state.text = "";
            state.ignore = true;
          } else if (state.text.indexOf("ytInitialPlayerResponse") === -1 && state.text.length > 256 * 1024) {
            state.text = state.text.slice(-128);
          } else if (state.text.length > CAP) {
            state.ignore = true;
            state.text = "";
          }
        } else if (state.text.length > CAP) {
          state.ignore = true;
          state.text = "";
        }
      } catch (e) {
        state.ignore = true;
        state.text = "";
      }
    };
    filter.onerror = () => {
      if (state.done) return;
      state.done = true;
      try { filter.disconnect(); } catch (e) {}
    };
    filter.onstop = () => {
      if (state.done) return;
      state.done = true;
      try { filter.close(); } catch (e) {}
      try {
        if (!state.ignore) state.text += decoder.decode();
        if (kind === "html" && !state.json) state.json = OrbisYt.extractPlayerJsonFromHtml(state.text);
        const body = kind === "html" ? state.json : state.text;
        if (body) reportPlayer(kind, details, body);
      } catch (e) {}
    };
  }

  const pagePatterns = [
    "*://*.youtube.com/*",
    "*://youtube.com/*",
    "*://*.youtube-nocookie.com/*",
    "*://*.youtu.be/*",
    "*://youtu.be/*",
    "*://youtubei.googleapis.com/*",
    "*://*.youtubei.googleapis.com/*",
  ];
  // Narrow on purpose: a blocking listener on every media request would sit on the playback path. Only the player
  // call and the watch document can carry ad slots.
  const slotPatterns = [
    "*://*.youtube.com/youtubei/v1/player*",
    "*://youtube.com/youtubei/v1/player*",
    "*://*.youtube-nocookie.com/youtubei/v1/player*",
    "*://youtubei.googleapis.com/youtubei/v1/player*",
    "*://*.youtubei.googleapis.com/youtubei/v1/player*",
    "*://*.youtube.com/watch*",
    "*://youtube.com/watch*",
    "*://*.youtube.com/shorts/*",
    "*://*.youtube.com/embed/*",
    "*://*.youtube.com/live/*",
    "*://*.youtube.com/v/*",
    "*://*.youtube-nocookie.com/watch*",
    "*://*.youtube-nocookie.com/embed/*",
    "*://*.youtube-nocookie.com/shorts/*",
    "*://*.youtube-nocookie.com/live/*",
    "*://youtu.be/*",
    "*://*.youtu.be/*",
  ];
  const breakPatterns = pagePatterns.concat([
    "*://*.doubleclick.net/*adview*",
    "*://*.doubleclick.net/*interaction*",
    "*://*.doubleclick.net/*get_midroll*",
    "*://*.doubleclick.net/*ad_break*",
    "*://*.googlesyndication.com/*adview*",
    "*://*.googlesyndication.com/*interaction*",
    "*://*.googleadservices.com/*adview*",
    "*://*.googleadservices.com/*interaction*",
  ]);
  try {
    browser.webRequest.onErrorOccurred.addListener(onError, { urls: breakPatterns });
  } catch (e) {}
  try {
    browser.webRequest.onBeforeRequest.addListener(onBeforeRequest, { urls: slotPatterns }, ["blocking"]);
  } catch (e) {}
})();
