// WebHome site enhancement skeleton.
// A complete starting point for adapting one target website:
// - page-type routing (home / list / detail / play)
// - debounced rescan on SPA navigation and DOM mutation
// - mobile / TV adaptive body classes and base styles
// - resource extraction and click routing into the native App
//
// Old-WebView safety: keep syntax at ES2017 level or lower.
// Do NOT use optional chaining (?.), nullish coalescing (??),
// logical assignment (||=), class fields or #private fields.
// One SyntaxError kills the whole extension on old TV boxes.
(function () {
  const CONFIG = {
    name: "fm-site",                       // change per site, used for log prefix and class names
    routes: [
      // First match wins. Adjust regexes for the target site.
      { type: "play", test: /\/(play|player|watch)\// },
      { type: "detail", test: /\/(detail|video|movie|vod|subject)\// },
      { type: "list", test: /\/(list|type|category|search|show)\// },
      { type: "home", test: /^\/$/ }
    ],
    // Narrow resource selectors. Never intercept every <a>.
    resourceSelector: [
      "[data-url]",
      "[data-clipboard-text]",
      "a[href^='magnet:']",
      "a[href^='ed2k:']",
      "a[href^='thunder:']",
      "a[href*='pan.quark.cn']",
      "a[href*='aliyundrive.com']",
      "a[href*='alipan.com']",
      "a[href*='pan.baidu.com']",
      "a[href*='drive.uc.cn']",
      "a[href*='pan.xunlei.com']",
      "a[href*='cloud.189.cn']",
      "a[href*='123pan']"
    ].join(","),
    titleSelector: "h1,h2,.title,.vod-title,.detail-title",
    // Elements that should become focusable on TV (cards, episode links...).
    tvFocusSelector: ".card,.item,.vod-item,.module-item,.play-list a",
    scanDelay: 140
  };

  const state = {
    scanTimer: 0,
    lastPath: ""
  };

  // ---------- shared helpers ----------

  function log() {
    const args = Array.prototype.slice.call(arguments);
    if (typeof GM_log === "function") GM_log.apply(null, args);
    else console.log.apply(console, ["[" + CONFIG.name + "]"].concat(args));
  }

  function whenFm() {
    if (window.fm) return Promise.resolve(window.fm);
    return new Promise(function (resolve) {
      window.addEventListener("fmsdk", function () { resolve(window.fm); }, { once: true });
    });
  }

  function ready(fn) {
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", fn, { once: true });
    else fn();
  }

  function isTv() {
    return !!(window.fongmiClient && window.fongmiClient.isLeanback);
  }

  function cleanText(value) {
    return String(value || "").replace(/\s+/g, " ").trim();
  }

  function absoluteUrl(value) {
    const text = cleanText(value);
    if (!text || text === "#" || /^javascript:/i.test(text)) return "";
    if (/^(magnet:|ed2k:|thunder:)/i.test(text)) return text;
    try {
      return new URL(text, location.href).href;
    } catch (e) {
      return text;
    }
  }

  function classify(url) {
    if (/^magnet:/i.test(url)) return "magnet";
    if (/^ed2k:/i.test(url)) return "ed2k";
    if (/^thunder:/i.test(url)) return "thunder";
    if (/pan\.quark\.cn/i.test(url)) return "quark";
    if (/aliyundrive\.com|alipan\.com/i.test(url)) return "aliyun";
    if (/pan\.baidu\.com/i.test(url)) return "baidu";
    if (/drive\.uc\.cn/i.test(url)) return "uc";
    if (/pan\.xunlei\.com/i.test(url)) return "xunlei";
    if (/cloud\.189\.cn/i.test(url)) return "tianyi";
    if (/123pan\.|123684\.|123685\.|123912\.|123592\.|123865\./i.test(url)) return "123";
    if (/115\.com|115cdn\.com/i.test(url)) return "115";
    if (/yun\.139\.com|caiyun\.139\.com/i.test(url)) return "mobile";
    if (/\.(m3u8|mp4|mkv|flv|mov|avi|webm)(\?|#|$)/i.test(url)) return "media";
    return "http";
  }

  function pageTitle() {
    const el = document.querySelector(CONFIG.titleSelector);
    return cleanText(el && el.textContent) || cleanText(document.title) || location.href;
  }

  function pageType() {
    for (let i = 0; i < CONFIG.routes.length; i++) {
      if (CONFIG.routes[i].test.test(location.pathname)) return CONFIG.routes[i].type;
    }
    return "other";
  }

  // ---------- resource routing ----------

  async function route(url, title) {
    const sdk = await whenFm();
    const type = classify(url);
    log("route", type, title, url);
    if (type === "media") {
      return sdk.play(url, title, { headers: { Referer: location.href }, credentials: "include" });
    }
    return sdk.pan.play({ type: type, url: url, title: title });
  }

  function onClick(event) {
    const el = event.target.closest(CONFIG.resourceSelector);
    if (!el) return;
    const url = absoluteUrl(el.getAttribute("data-url") || el.getAttribute("data-clipboard-text") || el.getAttribute("href"));
    if (!url) return;
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    route(url, pageTitle()).catch(function (error) {
      log("route error", (error && (error.stack || error.message)) || error);
      if (window.fm && window.fm.ext) window.fm.ext.toast("调用原生播放失败");
    });
  }

  // ---------- per-page enhancements ----------

  function enhanceHome() {
    // Example tasks: hide oversized banners on mobile, mark cards focusable for TV.
  }

  function enhanceList() {
    // Example tasks: tighten list layout, ensure pagination stays clickable.
  }

  function enhanceDetail() {
    // Example tasks: group resource links, build a unified play panel,
    // call fm.pan.check() for visible pan links (only when fm.config().driveCheck).
  }

  function enhancePlay() {
    // Example tasks: add an "App播放" entry, collect episode links,
    // hand them to fm.vodInline() (see templates/inline-episodes.js).
  }

  function markTvFocus() {
    if (!isTv()) return;
    document.querySelectorAll(CONFIG.tvFocusSelector).forEach(function (el) {
      if (el.dataset.fmFocusReady === "1") return;
      el.dataset.fmFocusReady = "1";
      // Native <a href> and <button> are already focusable; others need tabindex.
      if (el.tagName !== "A" && el.tagName !== "BUTTON" && !el.hasAttribute("tabindex")) el.setAttribute("tabindex", "0");
    });
  }

  // ---------- scan loop ----------

  function enhance() {
    if (!document.body) return;
    document.body.classList.add(CONFIG.name + "-enhanced");
    document.body.classList.toggle(CONFIG.name + "-tv", isTv());
    document.body.classList.toggle(CONFIG.name + "-mobile", !isTv());
    if (location.pathname !== state.lastPath) state.lastPath = location.pathname;
    const type = pageType();
    if (type === "home") enhanceHome();
    else if (type === "list") enhanceList();
    else if (type === "detail") enhanceDetail();
    else if (type === "play") enhancePlay();
    markTvFocus();
  }

  function scheduleScan() {
    clearTimeout(state.scanTimer);
    state.scanTimer = setTimeout(enhance, CONFIG.scanDelay);
  }

  // ---------- base style ----------

  function injectStyle() {
    // Focus feedback must not change layout: no width/border-width/margin changes.
    const css = ""
      + "body." + CONFIG.name + "-tv " + CONFIG.tvFocusSelector.split(",").map(function (sel) { return sel + ":focus"; }).join(",") + "{"
      + "outline:2px solid rgba(255,255,255,.85);outline-offset:1px;border-radius:6px;"
      + "background:rgba(255,255,255,.08);}"
      + "\nbody." + CONFIG.name + "-enhanced [data-fm-hidden='1']{display:none!important;}";
    if (typeof GM_addStyle === "function") GM_addStyle(css);
  }

  // ---------- boot ----------

  injectStyle();
  ready(function () {
    document.addEventListener("click", onClick, true);
    new MutationObserver(scheduleScan).observe(document.documentElement, { childList: true, subtree: true });
    enhance();
  });
  window.addEventListener("fmurlchange", scheduleScan);
  window.addEventListener("popstate", scheduleScan);
  // Re-apply after App resume: WebView may have dropped focus or viewport vars.
  window.addEventListener("fmresume", scheduleScan);
  log("ready", location.href);
})();
