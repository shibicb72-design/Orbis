// Reads <video> and <audio> addresses for Cast, and pauses them when a cast starts.
// The top frame only. The message goes to the Orbis app and nowhere else.
(() => {
  let port;
  try {
    port = browser.runtime.connectNative("orbisCast");
  } catch (e) {
    return;
  }
  function abs(u) {
    try {
      return new URL(u, document.baseURI).href;
    } catch (e) {
      return "";
    }
  }
  function add(list, u, t) {
    if (!u || list.length >= 8) return;
    const href = abs(u);
    if (!href) return;
    list.push({ src: href, type: t || "" });
  }
  function grab(el) {
    const sources = [];
    add(sources, el.currentSrc, "");
    add(sources, el.src, "");
    const nodes = el.querySelectorAll("source");
    for (let i = 0; i < nodes.length; i++) add(sources, nodes[i].src, nodes[i].type || "");
    const d = el.duration;
    return {
      tag: (el.tagName || "").toLowerCase(),
      sources,
      currentTime: el.currentTime || 0,
      duration: typeof d === "number" && isFinite(d) ? d : 0,
      paused: !!el.paused,
      poster: el.poster ? abs(el.poster) : "",
      title: el.getAttribute("title") || el.getAttribute("aria-label") || "",
    };
  }
  function probe() {
    const els = document.querySelectorAll("video, audio");
    const media = [];
    for (let i = 0; i < els.length && media.length < 12; i++) media.push(grab(els[i]));
    const og = document.querySelector('meta[property="og:title"]');
    const img = document.querySelector('meta[property="og:image"]');
    return {
      type: "probe",
      title: (og && og.content) || document.title || "",
      poster: img && img.content ? abs(img.content) : "",
      href: String(location.href || ""),
      media,
    };
  }
  port.onMessage.addListener((msg) => {
    if (!msg || !msg.type) return;
    if (msg.type === "probe") {
      try {
        var payload = probe();
        payload.token = msg.token || 0;
        port.postMessage(payload);
      } catch (e) {
        port.postMessage({ type: "probe", token: msg.token || 0, title: "", poster: "", href: String(location.href || ""), media: [] });
      }
    } else if (msg.type === "pause") {
      const els = document.querySelectorAll("video, audio");
      for (let i = 0; i < els.length; i++) {
        try {
          els[i].pause();
        } catch (e) {}
      }
    }
  });
})();
