// Tells Orbis when the top page says this browser is unsupported (see compat/UnsupportedBrowserClassifier.kt).
// Top frame only. Reads the page a few times right after it loads and sends at most one message, to the Orbis app
// only. No network, no storage, no changes to the page, nothing on the page's window.
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
    document.removeEventListener("pointerdown", stop, true);
    document.removeEventListener("keydown", stop, true);
    document.removeEventListener("scroll", stop, true);
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

  document.addEventListener("pointerdown", stop, true);
  document.addEventListener("keydown", stop, true);
  document.addEventListener("scroll", stop, true);

  if (check()) return;
  timers.push(setTimeout(check, 1500));
  timers.push(
    setTimeout(() => {
      if (!check()) send({ type: "clean" });
    }, 3000),
  );
})();
