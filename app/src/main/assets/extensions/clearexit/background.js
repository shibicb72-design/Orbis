// Clear on exit, site exceptions (candidate A). Orbis enables this extension only while it wipes browsing data:
//  1. "snapshot": reads every cookie of the normal profile, partitioned ones included, and keeps in memory those of the
//     excepted sites;
//  2. Orbis clears all cookies, site data and cache through the engine;
//  3. "restore": writes the kept cookies back.
// Cookie values never leave this script: Orbis only gets counts. Nothing is stored on disk by this script.
(() => {
  const NATIVE_APP = "orbisClearExit";
  const MAX_CONNECT_TRIES = 40;
  let port = null;
  let tries = 0;
  let kept = [];

  function bareHost(value) {
    return String(value || "").toLowerCase().replace(/^\./, "");
  }

  function inDomains(host, domains) {
    return domains.some((d) => host === d || host.endsWith("." + d));
  }

  function hostOfSite(site) {
    try {
      return new URL(site).hostname.toLowerCase();
    } catch (e) {
      return "";
    }
  }

  // A cookie is kept when its own site is excepted, and, for a partitioned cookie, the top-level site it was set under
  // is excepted too: a partition key names the site that embedded it, which is browsing history of its own.
  function keep(cookie, domains) {
    if (!inDomains(bareHost(cookie.domain), domains)) return false;
    const top = cookie.partitionKey && cookie.partitionKey.topLevelSite;
    return !top || inDomains(hostOfSite(top), domains);
  }

  async function snapshot(domains) {
    kept = [];
    const list = domains.map(bareHost).filter((d) => d.length > 0);
    if (list.length === 0) return 0;
    const all = await browser.cookies.getAll({ firstPartyDomain: null, partitionKey: {} });
    kept = all.filter((c) => keep(c, list));
    return kept.length;
  }

  async function restore() {
    let ok = 0;
    let failed = 0;
    const list = kept;
    kept = [];
    for (const c of list) {
      const host = bareHost(c.domain);
      const details = {
        url: (c.secure ? "https://" : "http://") + host + (c.path || "/"),
        name: c.name,
        value: c.value,
        path: c.path,
        secure: c.secure,
        httpOnly: c.httpOnly,
        sameSite: c.sameSite,
        storeId: c.storeId,
      };
      if (!c.hostOnly) details.domain = c.domain;
      if (!c.session && typeof c.expirationDate === "number") details.expirationDate = c.expirationDate;
      if (c.firstPartyDomain) details.firstPartyDomain = c.firstPartyDomain;
      if (c.partitionKey) details.partitionKey = c.partitionKey;
      try {
        await browser.cookies.set(details);
        ok++;
      } catch (e) {
        failed++;
      }
    }
    return { ok, failed };
  }

  async function handle(msg) {
    if (!msg || typeof msg.id !== "number") return;
    try {
      if (msg.type === "snapshot") {
        const count = await snapshot(Array.isArray(msg.domains) ? msg.domains : []);
        port && port.postMessage({ type: "snapshot", id: msg.id, count });
      } else if (msg.type === "restore") {
        const result = await restore();
        port && port.postMessage({ type: "restored", id: msg.id, ok: result.ok, failed: result.failed });
      }
    } catch (e) {
      port && port.postMessage({ type: "error", id: msg.id });
    }
  }

  // Orbis sets its message handler when it enables the extension; a connect that comes too early is retried.
  function connect() {
    if (port || tries >= MAX_CONNECT_TRIES) return;
    tries++;
    try {
      port = browser.runtime.connectNative(NATIVE_APP);
    } catch (e) {
      port = null;
      setTimeout(connect, 250);
      return;
    }
    port.onMessage.addListener(handle);
    port.onDisconnect.addListener(() => {
      port = null;
      setTimeout(connect, 250);
    });
    port.postMessage({ type: "hello" });
  }

  connect();
})();
