// Orbis reader view: tells the app whether this page looks like an article, and parses it with Mozilla Readability when
// the app asks. Runs only in the top frame of http(s) pages; sends nothing anywhere but to the Orbis app.
(() => {
  let port;
  try {
    port = browser.runtime.connectNative("orbisReader");
  } catch (e) {
    return;
  }
  const report = () => {
    let value = false;
    try {
      value = isProbablyReaderable(document);
    } catch (e) {}
    port.postMessage({ type: "readerable", url: location.href, value });
  };
  report();
  port.onMessage.addListener((msg) => {
    if (!msg || msg.type !== "parse") return;
    let article = null;
    try {
      const a = new Readability(document.cloneNode(true)).parse();
      if (a) {
        article = {
          title: a.title || document.title || "",
          byline: a.byline || "",
          siteName: a.siteName || "",
          content: a.content || "",
          length: a.length || 0,
        };
      }
    } catch (e) {}
    port.postMessage({ type: "article", url: location.href, article });
  });
})();
