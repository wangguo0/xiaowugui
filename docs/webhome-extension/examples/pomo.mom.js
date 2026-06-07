// WebHome extension example for https://pomo.mom/
// Purpose:
// 1. Keep the original site usable.
// 2. Route resource clicks to native App playback/push.
// 3. Add explicit "App播放" buttons beside Pomo download items.
(function () {
  const CONFIG = {
    resourceSelector: ".x-dbjs-download-link,.x-dbjs-download-btn",
    copySelector: ".x-dbjs-copy-btn",
    itemSelector: ".download-item",
    titleSelector: ".x-dbjs-title",
    buttonClass: "fm-pomo-play",
    scanDelay: 120
  };

  const PAN_TYPES = [
    ["quark", /pan\.quark\.cn/i],
    ["aliyun", /aliyundrive\.com|alipan\.com/i],
    ["baidu", /pan\.baidu\.com/i],
    ["uc", /drive\.uc\.cn/i],
    ["xunlei", /pan\.xunlei\.com/i],
    ["tianyi", /cloud\.189\.cn/i],
    ["123", /123pan\.|123684\.|123685\.|123912\.|123592\.|123865\./i],
    ["115", /115\.com|115cdn\.com/i],
    ["mobile", /yun\.139\.com|caiyun\.139\.com/i]
  ];

  function log() {
    const args = Array.prototype.slice.call(arguments);
    if (typeof GM_log === "function") GM_log.apply(null, args);
    else console.log.apply(console, ["[fm-pomo]"].concat(args));
  }

  function toast(message) {
    try {
      if (window.fm && fm.ext && fm.ext.toast) return fm.ext.toast(message);
    } catch (e) {
      // ignore
    }
    return Promise.resolve();
  }

  function whenFm() {
    if (window.fm) return Promise.resolve(window.fm);
    return new Promise((resolve) => {
      window.addEventListener("fmsdk", () => resolve(window.fm), { once: true });
    });
  }

  function ready(fn) {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", fn, { once: true });
    } else {
      fn();
    }
  }

  function pageTitle() {
    const detailTitle = document.querySelector(CONFIG.titleSelector);
    const text = detailTitle && detailTitle.textContent ? detailTitle.textContent.trim() : "";
    return text || document.title.replace(/\s*-\s*4K.*$/i, "").trim() || location.href;
  }

  function resourceTitle(element) {
    const item = element.closest(CONFIG.itemSelector);
    const link = item && item.querySelector(".x-dbjs-download-link");
    const text = (link && link.textContent ? link.textContent : element.textContent || "").trim();
    return text || pageTitle();
  }

  function getUrl(element) {
    if (!element) return "";
    const value = element.getAttribute("data-url") || element.getAttribute("href") || "";
    if (!value || value === "#" || /^javascript:/i.test(value)) return "";
    return value.trim();
  }

  function classify(url) {
    if (/^magnet:/i.test(url)) return "magnet";
    if (/^ed2k:/i.test(url)) return "ed2k";
    if (/^thunder:/i.test(url)) return "thunder";
    for (const item of PAN_TYPES) {
      if (item[1].test(url)) return item[0];
    }
    if (/\.(m3u8|mp4|mkv|flv|mov|avi|webm)(\?|#|$)/i.test(url)) return "media";
    if (/^https?:/i.test(url)) return "http";
    return "unknown";
  }

  function isPushType(type) {
    return [
      "quark",
      "aliyun",
      "baidu",
      "uc",
      "xunlei",
      "tianyi",
      "123",
      "115",
      "mobile",
      "magnet",
      "ed2k",
      "thunder"
    ].indexOf(type) >= 0;
  }

  async function route(url, title) {
    const sdk = await whenFm();
    const type = classify(url);
    const finalTitle = title || pageTitle();
    log("route", type, finalTitle, url);

    if (isPushType(type)) {
      return sdk.pan.play({ type, url, title: finalTitle });
    }
    if (type === "media") {
      return sdk.play(url, finalTitle, {
        headers: { Referer: location.href },
        credentials: "include"
      });
    }
    return sdk.pan.play({ type: "http", url, title: finalTitle });
  }

  function onClick(event) {
    const copy = event.target.closest(CONFIG.copySelector);
    if (copy) return;

    const target = event.target.closest(CONFIG.resourceSelector + ",." + CONFIG.buttonClass);
    if (!target) return;

    const item = target.closest(CONFIG.itemSelector);
    const source = target.classList.contains(CONFIG.buttonClass) && item
      ? item.querySelector(CONFIG.resourceSelector)
      : target;
    const url = getUrl(target) || getUrl(source);
    if (!url) return;

    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();

    route(url, resourceTitle(source || target)).catch((error) => {
      log("route error", error && (error.stack || error.message) || error);
      toast("调用原生播放失败");
    });
  }

  function injectButtons() {
    document.querySelectorAll(CONFIG.itemSelector).forEach((item) => {
      if (item.dataset.fmPomoReady === "1") return;
      const source = item.querySelector(CONFIG.resourceSelector);
      const url = getUrl(source);
      if (!url) return;

      item.dataset.fmPomoReady = "1";
      const info = item.querySelector(".download-info") || item;
      const button = document.createElement("button");
      button.type = "button";
      button.className = CONFIG.buttonClass;
      button.textContent = "App播放";
      button.setAttribute("data-url", url);
      button.setAttribute("title", "使用 App 原生播放/解析");
      info.appendChild(button);
    });
  }

  function expandDownloadPanels() {
    document.querySelectorAll(".x-dbjs-accordion-content").forEach((content) => {
      const inner = content.querySelector(".x-dbjs-accordion-inner");
      if (inner && content.style.maxHeight === "0px") {
        content.style.maxHeight = inner.scrollHeight + "px";
      }
    });
    document.querySelectorAll(".x-dbjs-accordion-icon").forEach((icon) => {
      if (icon.textContent === "▶") icon.textContent = "▼";
    });
  }

  function scan() {
    injectButtons();
  }

  function scheduleScan() {
    clearTimeout(scheduleScan.timer);
    scheduleScan.timer = setTimeout(scan, CONFIG.scanDelay);
  }

  function installObserver() {
    const observer = new MutationObserver(scheduleScan);
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }

  function installStyle() {
    const css = `
      .${CONFIG.buttonClass} {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-height: 34px;
        padding: 0 12px;
        margin-left: 8px;
        border: 1px solid #0f766e;
        border-radius: 6px;
        background: #0f766e;
        color: #fff;
        font-size: 13px;
        font-weight: 700;
        cursor: pointer;
        white-space: nowrap;
      }
      .${CONFIG.buttonClass}:active {
        transform: translateY(1px);
      }
      .dark .${CONFIG.buttonClass} {
        border-color: #14b8a6;
        background: #0d9488;
      }
    `;
    if (typeof GM_addStyle === "function") GM_addStyle(css);
    else {
      const style = document.createElement("style");
      style.textContent = css;
      (document.head || document.documentElement).appendChild(style);
    }
  }

  ready(() => {
    installStyle();
    document.addEventListener("click", onClick, true);
    installObserver();
    expandDownloadPanels();
    scan();
    log("ready", location.href);
  });

  window.addEventListener("fmurlchange", () => {
    delete scheduleScan.timer;
    scheduleScan();
  });
})();
