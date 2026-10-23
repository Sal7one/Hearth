"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("../core.js");

const config = { url: "http://127.0.0.1:41455/", token: "example-local-token-123456789", sourceLanguage: "en", targetLanguage: "ar" };
function element(tagName, attributes = {}, parentElement = null) {
  return { tagName, parentElement, isContentEditable: false, hasAttribute: name => Object.hasOwn(attributes, name), getAttribute: name => attributes[name] ?? null };
}

test("configuration pins numeric loopback, explicit port and language without URL secrets", () => {
  assert.equal(core.validateConfiguration(config).url, "http://127.0.0.1:41455");
  for (const url of ["https://127.0.0.1:41455", "http://example.com:41455", "http://127.0.0.1.example.com:41455", "http://localhost:41455", "http://127.0.0.1", "http://127.0.0.1:41455/v1", "http://user:pass@127.0.0.1:41455", "http://127.0.0.1:41455/?token=secret", "http://127.0.0.1:41455/#secret"]) {
    assert.throws(() => core.validateConfiguration({ ...config, url }), Error, url);
  }
  assert.throws(() => core.validateConfiguration({ ...config, targetLanguage: "auto" }));
  assert.throws(() => core.validateConfiguration({ ...config, targetLanguage: "detect" }));
  assert.throws(() => core.validateConfiguration({ ...config, token: "short" }));
  assert.throws(() => core.validateConfiguration({ ...config, sourceLanguage: "en\nAuthorization" }));
  assert.equal(core.validateConfiguration({ ...config, sourceLanguage: "auto" }).sourceLanguage, "auto");
});

test("form values, passwords, code, hidden/editable regions and extension UI are excluded through ancestry", () => {
  for (const tag of ["SCRIPT", "STYLE", "NOSCRIPT", "CODE", "PRE", "INPUT", "TEXTAREA", "SELECT", "BUTTON", "FORM", "SVG", "CANVAS", "IFRAME"]) {
    assert.equal(core.blockedAncestor(element("SPAN", {}, element(tag))), true, tag);
  }
  for (const attributes of [{ hidden: "" }, { "aria-hidden": "true" }, { translate: "no" }, { "data-hearth-website-ui": "" }]) {
    assert.equal(core.blockedAncestor(element("SPAN", {}, element("DIV", attributes))), true);
  }
  const editable = element("DIV"); editable.isContentEditable = true;
  assert.equal(core.blockedAncestor(element("SPAN", {}, editable)), true);
  assert.equal(core.blockedAncestor(element("SPAN", {}, element("A", { href: "/article" }))), false);
  assert.equal(core.blockedAncestor(element("P", {}, element("ARTICLE"))), false);
});

test("viewport visibility rejects offscreen and zero-area text", () => {
  const viewport = { width: 800, height: 600 };
  assert.equal(core.visibleRect({ left: 0, top: 0, right: 100, bottom: 20, width: 100, height: 20 }, viewport), true);
  assert.equal(core.visibleRect({ left: -90, top: 500, right: 10, bottom: 620, width: 100, height: 120 }, viewport), true);
  for (const rect of [
    { left: 0, top: 600, right: 100, bottom: 620, width: 100, height: 20 },
    { left: 800, top: 0, right: 900, bottom: 20, width: 100, height: 20 },
    { left: 0, top: -30, right: 100, bottom: 0, width: 100, height: 30 },
    { left: 0, top: 0, right: 0, bottom: 20, width: 0, height: 20 }
  ]) assert.equal(core.visibleRect(rect, viewport), false);
});

test("visible batches bound nodes and characters and retain exact node identities", () => {
  const candidates = Array.from({ length: 1000 }, (_, id) => ({ id, text: "English paragraph ".repeat(30) }));
  const batch = core.makeBatch(candidates);
  assert.equal(batch.length, 4);
  assert.equal(batch[0], candidates[0]);
  const large = core.makeBatch(Array.from({ length: 10 }, (_, id) => ({ id, text: "a".repeat(1200) })));
  assert.equal(large.length, 2);
  assert.ok(large.reduce((sum, item) => sum + item.text.length, 0) <= 3000);
  assert.equal(core.meaningfulText("1234 · <>"), false);
  assert.equal(core.meaningfulText("Русский текст"), true);
  assert.equal(core.meaningfulText("中文翻译"), true);
  assert.equal(core.meaningfulText("اللغة العربية"), true);
  assert.equal(core.meaningfulText("a".repeat(1201)), false);
});

test("translated text edits retain anchors, attributes, handlers and whitespace; no HTML is evaluated", () => {
  const parent = { href: "/safe", onclick: () => "clicked" };
  const node = { data: " \nOriginal article\t", isConnected: true, parentElement: parent };
  const records = new Map();
  assert.equal(core.applyText(node, node.data, "<script>untrusted text</script>", records), true);
  assert.equal(node.data, " \n<script>untrusted text</script>\t");
  assert.equal(node.parentElement, parent);
  assert.equal(parent.href, "/safe"); assert.equal(parent.onclick(), "clicked");
  core.restore(records);
  assert.equal(node.data, " \nOriginal article\t"); assert.equal(records.size, 0);
});

test("DOM changes during inference and after translation are never overwritten", () => {
  const records = new Map();
  const node = { data: "Site updated this text", isConnected: true };
  assert.equal(core.applyText(node, "Old content", "stale", records), false);
  node.isConnected = false;
  assert.equal(core.applyText(node, node.data, "detached", records), false);
  node.isConnected = true;
  assert.equal(core.applyText(node, node.data, "ترجمة", records), true);
  node.data = "New live content";
  core.restore(records);
  assert.equal(node.data, "New live content");
  assert.equal(records.size, 0);
});

test("only successful, actual translated segments are accepted; engine failures stay visible", () => {
  assert.equal(core.completedTranslation({ status: "completed", segments: [{ translation: "الأول", final: true }, { translation: "الثاني", final: true }] }), "الأول\nالثاني");
  assert.throws(() => core.completedTranslation({ status: "failed", error: "CTranslate2 cannot translate auto → ar" }), /CTranslate2 cannot translate auto/);
  assert.throws(() => core.completedTranslation({ status: "completed", segments: [{ original: "Hello" }] }), /no translated text/);
  assert.throws(() => core.completedTranslation({ status: "completed", segments: [{ translation: "partial", final: false }] }), /no translated text/);
  assert.throws(() => core.replaceText("original", ""), /empty/);
  assert.throws(() => core.safeSessionId("../../other-host?token=secret"), /invalid session/);
});
