// Orbis Force Dark: inverts a light page's colours (keeping pictures and videos natural) unless the page is already dark.
(() => {
  const STYLE_ID = "orbis-force-dark";
  const CSS =
    "html{background:#fff !important;filter:invert(0.92) hue-rotate(180deg) !important}" +
    "img,video,picture,canvas,embed,object,iframe,[style*='background-image']{filter:invert(1) hue-rotate(180deg) !important}";

  // Relative luminance 0..1 of a computed colour, or null when it is transparent.
  function luminance(colour) {
    const m = colour && colour.match(/[\d.]+/g);
    if (!m || m.length < 3) return null;
    const [r, g, b] = m.slice(0, 3).map(Number);
    const a = m.length > 3 ? Number(m[3]) : 1;
    if (a === 0) return null;
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  }

  function pageIsDark() {
    for (const el of [document.body, document.documentElement]) {
      if (!el) continue;
      const l = luminance(getComputedStyle(el).backgroundColor);
      if (l !== null) return l < 0.4;
    }
    return false; // no background set: the browser's default white
  }

  function apply() {
    if (document.getElementById(STYLE_ID) || pageIsDark()) return;
    const style = document.createElement("style");
    style.id = STYLE_ID;
    style.textContent = CSS;
    (document.head || document.documentElement).appendChild(style);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", apply, { once: true });
  } else {
    apply();
  }
})();
