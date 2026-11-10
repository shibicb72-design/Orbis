// Sends the watch page's title to the background script so a blocked ad can be labelled. Nothing leaves the phone.
(() => {
  let last = "";
  let watching = null;

  function publish() {
    const url = String(location.href || "");
    const el = document.querySelector("title");
    const title = (el && el.textContent) || document.title || "";
    const payload = url + "\n" + title;
    if (!title || payload === last) return;
    last = payload;
    try {
      browser.runtime.sendMessage({ type: "title", url, title });
    } catch (e) {}
  }

  function bind() {
    const el = document.querySelector("title");
    if (!el || el === watching) return;
    watching = el;
    try {
      new MutationObserver(publish).observe(el, { subtree: true, childList: true, characterData: true });
    } catch (e) {}
  }

  publish();
  bind();
  const head = document.head || document.documentElement;
  if (head) {
    try {
      new MutationObserver(() => {
        bind();
        publish();
      }).observe(head, { childList: true, subtree: true });
    } catch (e) {}
  }
  document.addEventListener("DOMContentLoaded", () => {
    bind();
    publish();
  });
})();
