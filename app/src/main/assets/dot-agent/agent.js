/*
 * Dot Agent — the bridge between a page and Dot's on-device assistant.
 * The assistant can only see and do what is exposed here: read the visible page, read the main
 * text, and click / type / scroll on elements it was shown. Nothing is sent anywhere else.
 */
(() => {
  if (window.top !== window) return;

  let port;
  try {
    port = browser.runtime.connectNative("dot");
  } catch (e) {
    return;
  }

  const clean = (t) => (t || "").replace(/\s+/g, " ").trim();

  const isVisible = (el) => {
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const s = getComputedStyle(el);
    return s.visibility !== "hidden" && s.display !== "none" && Number(s.opacity) > 0.05;
  };

  const inViewport = (el) => {
    const r = el.getBoundingClientRect();
    return r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
  };

  const labelOf = (el) =>
    clean(
      el.getAttribute("aria-label") ||
        el.innerText ||
        el.value ||
        el.getAttribute("placeholder") ||
        el.getAttribute("title") ||
        el.getAttribute("alt") ||
        (el.querySelector && el.querySelector("img[alt]") && el.querySelector("img[alt]").alt) ||
        el.getAttribute("name") ||
        ""
    );

  let counter = 0;
  const idFor = (el) => {
    let id = el.getAttribute("data-dot-id");
    if (!id) {
      id = String(++counter);
      el.setAttribute("data-dot-id", id);
    }
    return Number(id);
  };

  const INTERACTIVE =
    "a[href], button, input:not([type=hidden]), textarea, select, summary, [role=button], [role=link]," +
    " [role=tab], [role=menuitem], [role=checkbox], [role=switch], [role=searchbox], [contenteditable=true], [onclick]";

  /** Visible links, buttons and fields, numbered so the assistant can refer to them. */
  function elements(onlyViewport) {
    const out = [];
    for (const el of document.querySelectorAll(INTERACTIVE)) {
      if (!isVisible(el)) continue;
      const view = inViewport(el);
      if (onlyViewport && !view) continue;
      const tag = el.tagName.toLowerCase();
      const role = el.getAttribute("role");
      let kind = "button";
      if (tag === "a" || role === "link") kind = "link";
      else if (tag === "input" || tag === "textarea" || el.isContentEditable || role === "searchbox") {
        const type = (el.getAttribute("type") || "text").toLowerCase();
        kind = ["checkbox", "radio"].includes(type) ? "checkbox" : ["submit", "button", "reset", "image"].includes(type) ? "button" : "field";
      } else if (tag === "select") kind = "select";
      const item = { id: idFor(el), kind, text: labelOf(el).slice(0, 90) };
      if (kind === "field") {
        item.type = (el.getAttribute("type") || (tag === "textarea" ? "textarea" : "text")).toLowerCase();
        if (el.value) item.value = String(el.value).slice(0, 60);
      }
      if (kind === "link" && el.href && !el.href.startsWith("javascript:")) item.href = el.href;
      if (kind === "select") item.options = [...el.options].slice(0, 12).map((o) => clean(o.text));
      if (!view) item.offscreen = true;
      out.push(item);
      if (out.length >= 90) break;
    }
    return out;
  }

  /** The text you can see right now. */
  function viewportText(limit) {
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        const p = node.parentElement;
        if (!p || !clean(node.textContent)) return NodeFilter.FILTER_REJECT;
        if (["SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE"].includes(p.tagName)) return NodeFilter.FILTER_REJECT;
        if (!isVisible(p) || !inViewport(p)) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    const parts = [];
    let size = 0;
    let lastBlock = null;
    while (walker.nextNode() && size < limit) {
      const node = walker.currentNode;
      const block = node.parentElement.closest("p, li, h1, h2, h3, h4, h5, h6, td, th, div, section, article, figcaption, blockquote, pre");
      const text = clean(node.textContent);
      if (block !== lastBlock && parts.length) parts.push("\n");
      else if (parts.length) parts.push(" ");
      parts.push(text);
      size += text.length;
      lastBlock = block;
    }
    return parts.join("").replace(/\n\s*\n+/g, "\n").slice(0, limit);
  }

  /** The main readable text: the article or main column, without navigation and chrome. */
  function readable(limit) {
    const candidates = [...document.querySelectorAll("article, main, [role=main], #content, .content, #main")];
    let root = candidates.sort((a, b) => (b.innerText || "").length - (a.innerText || "").length)[0];
    if (!root || (root.innerText || "").length < 500) {
      let best = null;
      let score = 0;
      for (const el of document.querySelectorAll("div, section")) {
        let s = 0;
        for (const p of el.querySelectorAll(":scope > p, :scope > div > p")) s += (p.innerText || "").length;
        if (s > score) {
          score = s;
          best = el;
        }
      }
      root = best && score > 300 ? best : document.body;
    }
    const skip = new Set(["NAV", "FOOTER", "ASIDE", "FORM", "SCRIPT", "STYLE", "NOSCRIPT", "SVG", "IFRAME", "BUTTON"]);
    const blocks = [];
    let size = 0;
    for (const el of root.querySelectorAll("h1, h2, h3, h4, p, li, blockquote, pre, td")) {
      if (size > limit) break;
      if (el.closest("nav, footer, aside, form, [aria-hidden=true], [role=navigation]")) continue;
      if (skip.has(el.tagName)) continue;
      const t = clean(el.innerText);
      if (t.length < 2) continue;
      const prefix = /^H[1-4]$/.test(el.tagName) ? "## " : el.tagName === "LI" ? "- " : "";
      blocks.push(prefix + t);
      size += t.length;
    }
    let text = blocks.join("\n");
    if (text.length < 200) text = clean(root.innerText);
    return text.slice(0, limit);
  }

  const find = (id) => document.querySelector(`[data-dot-id="${id}"]`);

  /** A soft Dot-coloured glow on the element the assistant is acting on. */
  function glow(el) {
    const r = el.getBoundingClientRect();
    const ring = document.createElement("div");
    ring.style.cssText =
      `position:fixed;left:${r.left - 4}px;top:${r.top - 4}px;width:${r.width + 8}px;height:${r.height + 8}px;` +
      "border-radius:10px;pointer-events:none;z-index:2147483647;" +
      "box-shadow:0 0 0 2px #2D8FE0,0 0 18px 4px rgba(106,92,224,.55);transition:opacity .6s ease;opacity:1";
    document.documentElement.appendChild(ring);
    setTimeout(() => (ring.style.opacity = "0"), 700);
    setTimeout(() => ring.remove(), 1400);
  }

  function setValue(el, text) {
    el.focus();
    if (el.isContentEditable) {
      el.textContent = text;
    } else {
      const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(proto, "value").set;
      setter.call(el, text);
    }
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  const handlers = {
    snapshot({ limit }) {
      return {
        url: location.href,
        title: document.title,
        text: viewportText(limit || 2200),
        elements: elements(false),
        scroll: {
          y: Math.round(scrollY),
          max: Math.max(0, Math.round(document.documentElement.scrollHeight - innerHeight)),
        },
      };
    },
    readable({ limit }) {
      return {
        url: location.href,
        title: document.title,
        lang: document.documentElement.lang || "",
        text: readable(limit || 6000),
      };
    },
    click({ id }) {
      const el = find(id);
      if (!el) return { ok: false, error: `There is no element [${id}] on this page any more.` };
      el.scrollIntoView({ block: "center", behavior: "instant" });
      glow(el);
      const label = labelOf(el).slice(0, 60);
      if (el.tagName === "INPUT" && ["checkbox", "radio"].includes(el.type)) el.click();
      else {
        el.focus && el.focus();
        el.click();
      }
      return { ok: true, clicked: label };
    },
    type({ id, text, submit }) {
      const el = find(id);
      if (!el) return { ok: false, error: `There is no field [${id}] on this page any more.` };
      el.scrollIntoView({ block: "center", behavior: "instant" });
      glow(el);
      setValue(el, String(text || ""));
      if (submit !== false) {
        const form = el.form;
        if (form) {
          if (form.requestSubmit) form.requestSubmit();
          else form.submit();
        } else {
          for (const type of ["keydown", "keypress", "keyup"]) {
            el.dispatchEvent(new KeyboardEvent(type, { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true }));
          }
        }
      }
      return { ok: true };
    },
    select({ id, value }) {
      const el = find(id);
      if (!el || el.tagName !== "SELECT") return { ok: false, error: `[${id}] is not a dropdown.` };
      const option = [...el.options].find((o) => clean(o.text).toLowerCase() === String(value).toLowerCase() || o.value === value);
      if (!option) return { ok: false, error: `No option "${value}".` };
      el.value = option.value;
      el.dispatchEvent(new Event("change", { bubbles: true }));
      glow(el);
      return { ok: true };
    },
    scroll({ direction }) {
      const amount = Math.round(innerHeight * 0.8) * (direction === "up" ? -1 : 1);
      scrollBy({ top: amount, behavior: "smooth" });
      return { ok: true };
    },
  };

  port.onMessage.addListener((msg) => {
    if (!msg || typeof msg.id === "undefined") return;
    let result;
    try {
      const handler = handlers[msg.cmd];
      result = handler ? handler(msg.args || {}) : { ok: false, error: `Unknown command ${msg.cmd}` };
    } catch (e) {
      result = { ok: false, error: String(e && e.message ? e.message : e) };
    }
    port.postMessage({ id: msg.id, result });
  });

  port.postMessage({ type: "ready", url: location.href });
})();
