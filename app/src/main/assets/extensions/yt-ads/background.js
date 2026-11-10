// Observes YouTube ad breaks and reports them to Orbis. Passes every response body through unchanged.
// Does not block, redirect, or rewrite a request, and does not run in private tabs.
//
// GeckoView 159 exposes webRequest.filterResponseData to this manifest-v2 extension because it has webRequestBlocking
// (checked in omni.ja ext-webRequest.js). If that method is missing or throws, the request is left alone and only the
// break-ping fallback runs.
(() => {
  const BLOCKED = {
    NS_ERROR_ABORT: true,
    NS_ERROR_BLOCKED_BY_CONTENT_BLOCKING: true,
    NS_ERROR_BLOCKED_BY_POLICY: true,
    NS_ERROR_TRACKING_URI: true,
  };
  const CAP = 4 * 1024 * 1024;
  const pages = OrbisYt.createPageTracker();

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

  function deliver(events) {
    (events || []).forEach(send);
  }

  browser.runtime.onMessage.addListener((msg, sender) => {
    if (!msg || msg.type !== "title" || !sender.tab || sender.tab.incognito) return;
    deliver(pages.setTopUrl(sender.tab.id, msg.url || "", msg.title || "", Date.now()));
  });

  try {
    browser.tabs.onRemoved.addListener((tabId) => {
      pages.setTopUrl(tabId, "", "", Date.now());
    });
  } catch (e) {}
  try {
    browser.tabs.onUpdated.addListener((tabId, info, tab) => {
      if (!info || !info.url || (tab && tab.incognito)) return;
      deliver(pages.setTopUrl(tabId, info.url, (tab && tab.title) || "", Date.now()));
    });
  } catch (e) {}

  function onError(details) {
    try {
      if (!details || !details.url || details.incognito || details.type === "main_frame") return;
      if (!isBlocked(details.error) || !OrbisYt.isBreakPing(details.url) || !OrbisYt.adIdFromUrl(details.url)) return;
      const top = pages.top(details.tabId);
      if (!top || !top.youtube || !top.videoId) return;
      send({
        type: "blocked-ad",
        url: details.url,
        documentUrl: top.url,
        title: OrbisYt.titleForVideo(top, top.videoId),
        error: details.error || "",
        incognito: false,
        at: Date.now(),
      });
    } catch (e) {}
  }

  function reportParsed(details, parsed) {
    const topFrame = details.type === "main_frame" || details.frameId === 0;
    const now = Date.now();
    const push = () => deliver(pages.pushParsed(details.tabId, parsed, topFrame, now));
    if (topFrame && pages.top(details.tabId)) {
      push();
      return;
    }
    try {
      browser.tabs.get(details.tabId).then((tab) => {
        if (tab && tab.url) deliver(pages.setTopUrl(details.tabId, tab.url, tab.title || "", Date.now()));
        push();
      }, push);
    } catch (e) {
      push();
    }
  }

  function onBeforeRequest(details) {
    const kind = OrbisYt.classifyRequest(details && details.url, details && details.type);
    if (!kind || !details || details.incognito) return;
    if (!browser.webRequest || typeof browser.webRequest.filterResponseData !== "function") return;
    let filter;
    try {
      filter = browser.webRequest.filterResponseData(details.requestId);
    } catch (e) {
      return;
    }
    if (kind === "html") deliver(pages.setTopUrl(details.tabId, details.url, "", Date.now()));
    const scan = kind === "html" ? OrbisYt.createHtmlScan() : null;
    const state = { text: "", ignore: false, done: false };
    const decoder = new TextDecoder("utf-8");
    const arm = setTimeout(() => deliver(pages.tick(Date.now())), OrbisYt.HOLD_MS + 50);
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
        const text = decoder.decode(event.data, { stream: true });
        if (scan) scan.push(text);
        else if (state.text.length <= CAP) {
          state.text += text;
          if (state.text.length > CAP) {
            state.ignore = true;
            state.text = "";
          }
        }
      } catch (e) {
        state.ignore = true;
        state.text = "";
      }
    };
    filter.onerror = () => {
      if (state.done) return;
      state.done = true;
      clearTimeout(arm);
      try { filter.disconnect(); } catch (e) {}
    };
    filter.onstop = () => {
      if (state.done) return;
      state.done = true;
      try { filter.close(); } catch (e) {}
      try {
        if (!state.ignore && !scan) state.text += decoder.decode();
        const body = scan ? scan.jsonText() : state.text;
        if (!body) return;
        const parsed = OrbisYt.slotsFromPlayer(body);
        if (parsed) reportParsed(details, parsed);
      } catch (e) {}
    };
  }

  const hosts = [
    "*://*.youtube.com",
    "*://youtube.com",
    "*://*.youtube-nocookie.com",
    "*://youtubei.googleapis.com",
    "*://*.youtubei.googleapis.com",
  ];
  const exactPaths = [
    "/youtubei/v1/player",
    "/youtubei/v1/player?*",
    "/youtubei/v1/get_watch",
    "/youtubei/v1/get_watch?*",
    "/youtubei/v1/reel/reel_watch_sequence",
    "/youtubei/v1/reel/reel_watch_sequence?*",
    "/watch",
    "/watch?*",
    "/playlist",
    "/playlist?*",
    "/shorts/*",
    "/embed/*",
    "/live/*",
    "/v/*",
  ];
  const slotPatterns = [];
  hosts.forEach((host) => {
    exactPaths.forEach((path) => slotPatterns.push(host + path));
  });
  slotPatterns.push("*://youtu.be/*", "*://*.youtu.be/*");
  const breakPatterns = hosts.map((host) => host + "/*").concat([
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
