"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { webcrypto } = require("node:crypto");
const core = require("../core.js");
const extension = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/";
const configuration = { url: "http://127.0.0.1:41455", token: "private-hearth-token-123456789", sourceLanguage: "en", targetLanguage: "ar" };

function harness(session = { id: "session_1", status: "completed", terminal: true, segments: [{ original: "Hello", translation: "مرحبا", final: true }] }) {
  let listener;
  const messages = [], requests = [], scripts = [], storageAccess = [];
  const api = {
    runtime: { getURL: value => extension + value, onMessage: { addListener: value => { listener = value; } } },
    storage: { local: { get: async () => ({ configuration }), setAccessLevel: async value => { storageAccess.push(value); } } },
    scripting: { executeScript: async value => { scripts.push(value); } },
    tabs: {
      sendMessage: async (tabId, message) => { messages.push({ tabId, message }); return { ok: true }; },
      onRemoved: { addListener() {} }, onUpdated: { addListener() {} }
    }
  };
  const context = vm.createContext({
    HearthWebsite: core, browser: api, AbortController, setTimeout, clearTimeout, crypto: webcrypto,
    fetch: async (url, options) => {
      requests.push({ url, ...options });
      const value = url.endsWith("/capabilities") ? { protocolVersion: 1 } : options.method === "DELETE" ? { ...session, terminal: true, status: "cancelled" } : session;
      return { ok: true, status: 200, text: async () => JSON.stringify(value) };
    }
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../background.js"), "utf8"), context);
  const send = (message, sender = { url: extension + "popup.html" }) => new Promise(resolve => listener(message, sender, resolve));
  return { send, requests, messages, scripts, storageAccess, get runId() { return messages.findLast(item => item.message.type === "hearth-start")?.message.runId; } };
}

test("activation injects only the selected tab and typed API uses the saved route without exposing credentials to the page", async () => {
  const h = harness();
  assert.equal((await h.send({ type: "start", tabId: 17 })).ok, true);
  assert.equal(h.scripts.length, 1); assert.equal(h.scripts[0].target.tabId, 17);
  assert.deepEqual(Array.from(h.scripts[0].files), ["core.js", "content.js"]);
  const result = await h.send({ type: "translate", runId: h.runId, texts: ["Hello"] }, { tab: { id: 17 }, frameId: 0, url: "https://example.com/article" });
  assert.equal(result.ok, true); assert.equal(result.translations[0], "مرحبا");
  const typed = h.requests.find(item => item.method === "POST");
  assert.deepEqual(JSON.parse(typed.body), { text: "Hello", sourceLanguage: "en", targetLanguage: "ar", translationEnabled: true, voiceEnabled: false });
  for (const request of h.requests) {
    assert.ok(request.url.startsWith(configuration.url + "/v1/"));
    assert.equal(request.headers.Authorization, "Bearer " + configuration.token);
    assert.equal(request.credentials, "omit"); assert.equal(request.redirect, "error");
    assert.equal(request.referrerPolicy, "no-referrer");
  }
  assert.equal(JSON.stringify(h.messages).includes(configuration.token), false);
  assert.equal(h.storageAccess[0].accessLevel, "TRUSTED_CONTEXTS");
  assert.equal((await h.send({ type: "stop", tabId: 17 })).ok, true);
  assert.equal(h.messages.at(-1).message.type, "hearth-stop");
});

test("web pages, other tabs, other frames and stale or oversized batches cannot use the local API", async () => {
  const h = harness();
  assert.equal((await h.send({ type: "start", tabId: 17 }, { tab: { id: 17 }, url: "https://example.com/" })).ok, false);
  assert.equal(h.requests.length, 0);
  await h.send({ type: "start", tabId: 17 });
  const before = h.requests.length;
  for (const [message, sender] of [
    [{ type: "translate", runId: h.runId, texts: ["Hello"] }, { tab: { id: 18 }, frameId: 0 }],
    [{ type: "translate", runId: h.runId, texts: ["Hello"] }, { tab: { id: 17 }, frameId: 1 }],
    [{ type: "translate", runId: "stale", texts: ["Hello"] }, { tab: { id: 17 }, frameId: 0 }],
    [{ type: "translate", runId: h.runId, texts: Array(5).fill("Hello") }, { tab: { id: 17 }, frameId: 0 }],
    [{ type: "translate", runId: h.runId, texts: ["a".repeat(1201)] }, { tab: { id: 17 }, frameId: 0 }]
  ]) assert.equal((await h.send(message, sender)).ok, false);
  assert.equal(h.requests.length, before);
});

test("actual translation failure propagates and never becomes original-text success", async () => {
  const h = harness({ id: "session_2", status: "failed", terminal: true, error: "CTranslate2 model does not support auto → ar", segments: [{ original: "Hello" }] });
  await h.send({ type: "start", tabId: 17 });
  const response = await h.send({ type: "translate", runId: h.runId, texts: ["Hello"] }, { tab: { id: 17 }, frameId: 0 });
  assert.equal(response.ok, false);
  assert.equal(response.error, "CTranslate2 model does not support auto → ar");
  assert.equal(response.translations, undefined);
});

test("Stop restores page immediately and cancels an in-flight owned session", async () => {
  const h = harness({ id: "session_3", status: "running", terminal: false, segments: [] });
  await h.send({ type: "start", tabId: 17 });
  const pending = h.send({ type: "translate", runId: h.runId, texts: ["Hello"] }, { tab: { id: 17 }, frameId: 0 });
  await new Promise(resolve => setImmediate(resolve));
  const stopped = await h.send({ type: "stop", tabId: 17 });
  assert.equal(stopped.ok, true);
  assert.equal(h.messages.at(-1).message.type, "hearth-stop");
  assert.ok(h.requests.some(item => item.method === "DELETE" && item.url.endsWith("/v1/sessions/session_3")));
  assert.equal((await pending).ok, false);
});
