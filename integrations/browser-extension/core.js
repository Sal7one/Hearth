/* Portable, dependency-free selection and bounds shared by the extension and Node tests. */
(function (root, factory) {
  const value = factory();
  if (typeof module === "object" && module.exports) module.exports = value;
  else root.HearthWebsite = value;
})(globalThis, function () {
  "use strict";
  const limits = Object.freeze({ batchNodes: 4, batchCharacters: 3000, nodeCharacters: 1200, scannedNodes: 4000, retainedNodes: 5000 });
  const excludedTags = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "CODE", "PRE", "KBD", "SAMP", "INPUT", "TEXTAREA", "SELECT", "OPTION", "BUTTON", "FORM", "SVG", "MATH", "CANVAS", "IFRAME", "VIDEO", "AUDIO"]);
  function validateConfiguration(raw) {
    let address;
    try { address = new URL(String(raw.url || "")); } catch { throw new Error("Copy the API address from Hearth, including its port."); }
    if (address.protocol !== "http:" || address.hostname !== "127.0.0.1" || !address.port || address.username || address.password || address.search || address.hash || !["", "/"].includes(address.pathname)) {
      throw new Error("The API address must be http://127.0.0.1:PORT with no path or credentials.");
    }
    const token = String(raw.token || "").trim();
    if (!/^[A-Za-z0-9._~+/-]{16,512}={0,2}$/.test(token)) throw new Error("Paste the API token copied explicitly from Hearth.");
    const sourceLanguage = String(raw.sourceLanguage || "en").trim();
    const targetLanguage = String(raw.targetLanguage || "ar").trim();
    if (![sourceLanguage, targetLanguage].every(value => /^[A-Za-z][A-Za-z0-9-]{0,34}$/.test(value)) || ["auto", "detect"].includes(targetLanguage.toLowerCase())) {
      throw new Error("Use language codes such as en, ar, ru or zh. The output language must be explicit.");
    }
    if (sourceLanguage === targetLanguage) throw new Error("Choose different source and output languages.");
    return { url: address.origin, token, sourceLanguage, targetLanguage };
  }
  function blockedAncestor(element) {
    for (let current = element, depth = 0; current && depth < 128; current = current.parentElement, depth++) {
      if (excludedTags.has(current.tagName) || current.isContentEditable || current.hasAttribute("hidden") || current.getAttribute("aria-hidden") === "true" || current.getAttribute("translate") === "no" || current.hasAttribute("data-hearth-website-ui")) return true;
    }
    return false;
  }
  function visibleRect(rect, viewport) {
    return rect && rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.right > 0 && rect.top < viewport.height && rect.left < viewport.width;
  }
  function meaningfulText(value) {
    const text = String(value || "").trim();
    return text.length >= 2 && text.length <= limits.nodeCharacters && /\p{L}/u.test(text);
  }
  function makeBatch(candidates) {
    const result = []; let characters = 0;
    for (const candidate of candidates) {
      if (!meaningfulText(candidate.text)) continue;
      const size = candidate.text.trim().length;
      if (characters + size > limits.batchCharacters) continue;
      result.push(candidate); characters += size;
      if (result.length === limits.batchNodes) break;
    }
    return result;
  }
  function replaceText(original, translated) {
    if (typeof translated !== "string" || !translated.trim() || translated.length > 12000) throw new Error("Hearth returned an empty or oversized translation.");
    return original.match(/^\s*/u)[0] + translated.trim() + original.match(/\s*$/u)[0];
  }
  function applyText(node, expected, translated, records) {
    if (!node.isConnected || node.data !== expected) return false;
    const replacement = replaceText(expected, translated);
    records.set(node, { original: expected, replacement });
    node.data = replacement;
    return true;
  }
  function restore(records) {
    for (const [node, record] of records) {
      // A site's own update takes precedence over our earlier snapshot.
      if (node.isConnected && node.data === record.replacement) node.data = record.original;
    }
    records.clear();
  }
  function completedTranslation(snapshot) {
    if (!snapshot || !["completed", "failed", "cancelled"].includes(snapshot.status)) throw new Error("Hearth returned an invalid terminal session.");
    if (snapshot.status !== "completed") throw new Error(snapshot.error || `Translation ${snapshot.status}.`);
    if (!Array.isArray(snapshot.segments)) throw new Error("Hearth returned no caption segments.");
    const translations = snapshot.segments.filter(segment => segment.final !== false).map(segment => segment.translation).filter(value => typeof value === "string" && value.trim());
    const result = translations.join("\n").trim();
    if (!result) throw new Error(snapshot.error || "The selected engine returned no translated text. Check Hearth's translation route.");
    return result;
  }
  function safeSessionId(id) {
    if (typeof id !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(id)) throw new Error("Hearth returned an invalid session ID.");
    return id;
  }
  return Object.freeze({ limits, validateConfiguration, blockedAncestor, visibleRect, meaningfulText, makeBatch, replaceText, applyText, restore, completedTranslation, safeSessionId });
});
