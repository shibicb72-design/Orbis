// Tells Orbis when the top page says this browser is unsupported (see compat/UnsupportedBrowserClassifier.kt).
// Top frame only. Reads the page a few times right after it loads and sends exactly one message, to the Orbis app
// only: a sample, or "clean" at 3 s or at the first interaction, so a retry is always resolved. "clean" also says
// whether the page was empty at 3 s ("empty" / "not-empty", nothing else). No network, no storage, no changes to the
// page, nothing on the page's window.
(() => {
  const MAX_TEXT_CONTENT = 20000;
  const MAX_VISIBLE_TEXT = 800;
  const MAX_HEADLINES = 5;
  const MAX_HEADLINE = 200;
  // Same list as BLOCK_SEGMENTS in UnsupportedBrowserClassifier.kt. Only the yes/no answer leaves this script.
  const BLOCK_SEGMENTS = [
    "unsupported",
    "unsupported-browser",
    "browser-not-supported",
    "browser-unsupported",
    "outdated-browser",
    "upgrade-browser",
    "browser-update",
    "incompatible-browser",
  ];

  let done = false;
  const timers = [];

  function stop() {
    done = true;
    for (const t of timers) clearTimeout(t);
    document.removeEventListener("pointerdown", interacted, true);
    document.removeEventListener("keydown", interacted, true);
    document.removeEventListener("scroll", interacted, true);
  }

  // The user started using the page: look one last time, then stop for good. Before 3 s a page is never called empty.
  function interacted() {
    if (done) return;
    if (!check()) send({ type: "clean", page: "not-empty" });
  }

  // "Empty" (for the auto-desktop mobile retry): loaded, and showing fewer than MIN_VISIBLE_TEXT characters of visible
  // text (spaces not counted) and no visible image, video, canvas, frame or form control. Only "empty" or "not-empty"
  // leaves this script.
  const MIN_VISIBLE_TEXT = 20;
  const CONTENT_ELEMENTS = "img, svg, picture, video, canvas, iframe, embed, object, input, select, textarea, button";

  function shows(el) {
    if (typeof el.checkVisibility === "function") {
      if (!el.checkVisibility({ visibilityProperty: true, opacityProperty: true })) return false;
    } else if (!visible(el)) {
      return false;
    }
    const r = el.getBoundingClientRect();
    return r.width > 1 && r.height > 1;
  }

  function isEmpty() {
    if (document.readyState !== "complete") return false;
    const body = document.body;
    if (!body) return false;
    const text = (body.innerText || "").replace(/\s+/g, "");
    if (text.length >= MIN_VISIBLE_TEXT) return false;
    const nodes = document.querySelectorAll(CONTENT_ELEMENTS);
    for (let i = 0; i < nodes.length; i++) {
      if (shows(nodes[i])) return false;
    }
    return true;
  }

  function send(message) {
    stop();
    try {
      const port = browser.runtime.connectNative("orbisCompat");
      port.postMessage(message);
    } catch (e) {}
  }

  function isBlockPath(path) {
    const segments = String(path || "").toLowerCase().split("/");
    for (const seg of segments) {
      const dot = seg.lastIndexOf(".");
      const name = dot >= 0 ? seg.substring(0, dot) : seg;
      if (BLOCK_SEGMENTS.indexOf(name) >= 0) return true;
    }
    return false;
  }

  function visible(el) {
    return el.getClientRects().length > 0;
  }

  function headlines() {
    const out = [];
    const nodes = document.querySelectorAll("h1, h2");
    for (let i = 0; i < nodes.length && out.length < MAX_HEADLINES; i++) {
      if (!visible(nodes[i])) continue;
      const t = (nodes[i].innerText || "").trim();
      if (t) out.push(t.substring(0, MAX_HEADLINE));
    }
    return out;
  }

  // Returns true when it sent.
  function check() {
    if (done) return true;
    const body = document.body;
    if (!body) return false;
    if ((body.textContent || "").length > MAX_TEXT_CONTENT) return false;
    const text = body.innerText || "";
    if (text.length > MAX_VISIBLE_TEXT) return false;
    const title = document.title || "";
    if (!/browser/i.test(title + "\n" + text)) return false;
    send({
      type: "sample",
      title: title,
      headlines: headlines(),
      text: text,
      blockPath: isBlockPath(location.pathname),
    });
    return true;
  }

  document.addEventListener("pointerdown", interacted, true);
  document.addEventListener("keydown", interacted, true);
  document.addEventListener("scroll", interacted, true);

  if (check()) return;
  timers.push(setTimeout(check, 1500));
  timers.push(
    setTimeout(() => {
      if (!check()) send({ type: "clean", page: isEmpty() ? "empty" : "not-empty" });
    }, 3000),
  );
})();
