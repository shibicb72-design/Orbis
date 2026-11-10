// Reports blocked YouTube ad requests to Orbis. Does not block, redirect, or modify any request, and does not run in
// private tabs (the manifest disallows it; incognito details are dropped here too).
(() => {
  const BLOCKED = {
    NS_ERROR_ABORT: true,
    NS_ERROR_BLOCKED_BY_CONTENT_BLOCKING: true,
    NS_ERROR_BLOCKED_BY_POLICY: true,
    NS_ERROR_TRACKING_URI: true,
  };

  const titles = new Map();

  function isBlocked(error) {
    const e = String(error || "").toUpperCase();
    if (!e || e.startsWith("NS_ERROR_NET_") || e.startsWith("NS_ERROR_CONNECTION_") || e.startsWith("NS_ERROR_OFFLINE")) {
      return false;
    }
    if (BLOCKED[e] || e.startsWith("NS_ERROR_BLOCKED") || e.indexOf("CONTENT_BLOCKING") !== -1 || e.indexOf("TRACKING_URI") !== -1) {
      return true;
    }
    return false;
  }

  function looksLikeAd(url) {
    const u = String(url || "").toLowerCase();
    if (u.indexOf("/pagead/") !== -1 || u.indexOf("/api/stats/ads") !== -1) return true;
    if (u.indexOf("doubleclick.") !== -1 || u.indexOf("googleads") !== -1) return true;
    if (u.indexOf("googleadservices.") !== -1 || u.indexOf("googlesyndication.") !== -1) return true;
    const onYoutube = u.indexOf("youtube.com") !== -1 || u.indexOf("youtube-nocookie.com") !== -1;
    return onYoutube && (u.indexOf("get_midroll") !== -1 || u.indexOf("ad_break") !== -1);
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
    titles.set(sender.tab.id, { url: msg.url || "", title: msg.title || "" });
  });

  function onError(details) {
    if (!details || !details.url || details.incognito || details.type === "main_frame") return;
    if (!isBlocked(details.error) || !looksLikeAd(details.url)) return;
    const known = titles.get(details.tabId) || {};
    send({
      type: "blocked-ad",
      url: details.url,
      documentUrl: details.documentUrl || details.originUrl || known.url || "",
      title: known.title || "",
      error: details.error || "",
      incognito: false,
      at: Date.now(),
    });
  }

  const patterns = [
    "*://*.youtube.com/*",
    "*://youtube.com/*",
    "*://*.youtube-nocookie.com/*",
    "*://*.doubleclick.net/*",
    "*://*.googlesyndication.com/*",
    "*://*.googleadservices.com/*",
    "*://*.googleads.com/*",
  ];
  try {
    browser.webRequest.onErrorOccurred.addListener(onError, { urls: patterns });
  } catch (e) {}
})();
