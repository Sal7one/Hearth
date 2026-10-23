(function () {
  "use strict";
  if (globalThis.__hearthWebsiteController) return;
  const api = globalThis.browser || globalThis.chrome;
  const core = globalThis.HearthWebsite;
  const records = new Map();
  let active = false, runId = null, generation = 0, working = false, timer = null, observer = null;
  let panel = null, label = null, translatedCount = 0;
  let walker = null, scanMore = false, rescan = false;

  function status(text, error = false) {
    if (!panel) {
      panel = document.createElement("div");
      panel.setAttribute("data-hearth-website-ui", "");
      // A small, isolated toolbar. The rest of the page receives clicks normally.
      panel.style.cssText = "position:fixed;bottom:18px;right:18px;z-index:2147483647;pointer-events:none";
      const shadow = panel.attachShadow({ mode: "closed" });
      const style = document.createElement("style");
      style.textContent = ":host{all:initial}section{font:13px system-ui,sans-serif;color:#f7f9fc;background:#18232e;border:1px solid #45606d;border-radius:14px;box-shadow:0 4px 20px #0005;padding:10px 12px;max-width:340px;display:flex;gap:12px;align-items:center;pointer-events:auto}span{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.45}button{font:inherit;cursor:pointer;color:#10242b;background:#69e0d9;border:0;border-radius:8px;padding:7px 10px;flex-shrink:0}";
      const section = document.createElement("section");
      section.setAttribute("aria-label", "Hearth website translation");
      label = document.createElement("span"); label.setAttribute("role", "status");
      const stop = document.createElement("button"); stop.textContent = "Stop & restore";
      stop.addEventListener("click", () => stopTranslation(true));
      section.append(label, stop); shadow.append(style, section); document.documentElement.append(panel);
    }
    label.textContent = error ? `Hearth: ${text}` : text;
  }
  function schedule() {
    if (!active || timer || working) return;
    timer = setTimeout(() => { timer = null; void processVisible(); }, 350);
  }
  function changed(mutations) {
    if (Array.isArray(mutations) && mutations.every(change =>
      change.type === "characterData" && records.get(change.target)?.replacement === change.target.data ||
      change.target.nodeType === Node.ELEMENT_NODE && change.target.hasAttribute("data-hearth-website-ui"))) return;
    // Mutations must not reset a long-page cursor on every advertisement/ticker
    // update. Finish the bounded scan, then revisit any changed earlier content.
    if (Array.isArray(mutations)) rescan = true;
    else { walker = null; rescan = false; } // A user scroll/resize changes visibility.
    schedule();
  }
  function visible(node) {
    const element = node.parentElement;
    if (!element || core.blockedAncestor(element)) return false;
    const range = document.createRange();
    range.selectNodeContents(node);
    if (!Array.from(range.getClientRects()).some(rect => core.visibleRect(rect, { width: innerWidth, height: innerHeight }))) return false;
    for (let ancestor = element; ancestor; ancestor = ancestor.parentElement) {
      const style = getComputedStyle(ancestor);
      if (style.display === "none" || style.visibility !== "visible" || style.opacity === "0") return false;
    }
    return true;
  }
  function collect() {
    // Prune detached nodes so infinite-scroll pages do not retain removed DOM.
    for (const [node, saved] of records) {
      if (!node.isConnected || node.data !== saved.replacement) records.delete(node);
    }
    if (records.size >= core.limits.retainedNodes) throw new Error("Reached 5,000 translated text blocks. Stop & restore before translating more of this page.");
    walker ||= document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT);
    const candidates = []; let scanned = 0, characters = 0;
    scanMore = true;
    while (scanned++ < core.limits.scannedNodes) {
      if (!walker.nextNode()) { walker = null; scanMore = rescan; rescan = false; break; }
      const node = walker.currentNode;
      if (records.has(node) || !core.meaningfulText(node.data) || !visible(node)) continue;
      const text = node.data.trim();
      if (characters + text.length > core.limits.batchCharacters) continue;
      candidates.push({ node, text, original: node.data }); characters += text.length;
      if (candidates.length >= core.limits.batchNodes || records.size + candidates.length >= core.limits.retainedNodes) break;
    }
    return core.makeBatch(candidates);
  }
  async function processVisible() {
    if (!active || working) return;
    const ownGeneration = generation;
    working = true;
    try {
      const batch = collect();
      if (!batch.length) {
        status(`Hearth · ${translatedCount} blocks translated\nWatching visible text and scrolling`);
        return;
      }
      status(`Hearth · translating ${batch.length} visible blocks…`);
      const result = await api.runtime.sendMessage({ type: "translate", runId, texts: batch.map(item => item.text) });
      if (!active || generation !== ownGeneration) return;
      if (!result?.ok) throw new Error(result?.error || "Hearth extension background connection was lost. Start again.");
      if (!Array.isArray(result.translations) || result.translations.length !== batch.length) throw new Error("Hearth returned an incomplete translation batch.");
      batch.forEach((item, index) => {
        // A scroll, website update or detached element invalidates its snapshot.
        // Never replace elements, innerHTML, handlers, form values or URLs.
        if (visible(item.node) && core.applyText(item.node, item.original, result.translations[index], records)) translatedCount++;
      });
    } catch (error) {
      if (active && generation === ownGeneration) {
        active = false; observer?.disconnect();
        status(String(error.message || error), true);
        void api.runtime.sendMessage({ type: "stop", runId }).catch(() => {});
      }
    } finally {
      working = false;
      if (active && (scanMore || generation !== ownGeneration)) schedule();
    }
  }
  function stopTranslation(notify) {
    const oldRun = runId;
    generation++; active = false; runId = null;
    if (timer) clearTimeout(timer);
    timer = null; observer?.disconnect(); observer = null; walker = null; scanMore = false; rescan = false;
    window.removeEventListener("scroll", changed, true);
    window.removeEventListener("resize", changed);
    core.restore(records);
    panel?.remove(); panel = null; label = null;
    if (notify && oldRun) {
      const stoppedGeneration = generation;
      api.runtime.sendMessage({ type: "stop", runId: oldRun }).then(response => {
        if (!response?.ok && generation === stoppedGeneration && !active) status(`Page restored. ${response?.error || "Could not confirm Hearth session cleanup."}`, true);
      }).catch(error => {
        if (generation === stoppedGeneration && !active) status(`Page restored. ${error.message || error}`, true);
      });
    }
  }
  function startTranslation(id) {
    stopTranslation(false);
    runId = id; active = true; translatedCount = 0;
    observer = new MutationObserver(changed);
    observer.observe(document.documentElement, { subtree: true, childList: true, characterData: true });
    window.addEventListener("scroll", changed, { capture: true, passive: true });
    window.addEventListener("resize", changed, { passive: true });
    status("Hearth · reading visible text…"); schedule();
  }
  api.runtime.onMessage.addListener((message, _sender, respond) => {
    if (message.type === "hearth-start") { startTranslation(message.runId); respond({ ok: true }); }
    else if (message.type === "hearth-stop") { stopTranslation(false); respond({ ok: true }); }
  });
  window.addEventListener("pagehide", () => stopTranslation(true), { once: true });
  globalThis.__hearthWebsiteController = { stop: stopTranslation };
})();
