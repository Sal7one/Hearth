"use strict";
if (!globalThis.HearthWebsite && typeof importScripts === "function") importScripts("core.js");
const api = globalThis.browser || globalThis.chrome;
const core = globalThis.HearthWebsite;
const running = new Map();
const extensionBase = api.runtime.getURL("");
// Chromium can keep local credentials inaccessible to content-script contexts.
api.storage.local.setAccessLevel?.({ accessLevel: "TRUSTED_CONTEXTS" }).catch(() => {});

function delay(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new Error("Translation stopped."));
    const timer = setTimeout(done, ms);
    function done() { signal.removeEventListener("abort", abort); resolve(); }
    function abort() { clearTimeout(timer); signal.removeEventListener("abort", abort); reject(new Error("Translation stopped.")); }
    signal.addEventListener("abort", abort, { once: true });
  });
}
async function request(config, path, method, body, signal) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) throw new Error("Translation stopped.");
  signal?.addEventListener("abort", abort, { once: true });
  const timer = setTimeout(abort, 10_000);
  try {
    const response = await fetch(config.url + path, {
      method, headers: { Authorization: `Bearer ${config.token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}), signal: controller.signal,
      credentials: "omit", redirect: "error", cache: "no-store", referrerPolicy: "no-referrer"
    });
    const raw = await response.text();
    if (raw.length > 1_000_000) throw new Error("Hearth response exceeded the website integration limit.");
    let value;
    try { value = JSON.parse(raw); } catch { throw new Error(`Hearth returned invalid JSON (HTTP ${response.status}).`); }
    if (!response.ok) throw new Error(value.error || `Hearth rejected the request (HTTP ${response.status}).`);
    return value;
  } catch (error) {
    if (controller.signal.aborted) throw new Error(signal?.aborted ? "Translation stopped." : "Hearth request timed out after 10 seconds.");
    throw error;
  } finally {
    clearTimeout(timer); signal?.removeEventListener("abort", abort);
  }
}
async function cancelRun(run) {
  if (!run) return;
  run.controller.abort();
  if (run.sessionId) {
    await request(run.config, `/v1/sessions/${run.sessionId}`, "DELETE");
  }
}
async function translateOne(run, text) {
  const snapshot = await request(run.config, "/v1/sessions", "POST", {
    text, sourceLanguage: run.config.sourceLanguage, targetLanguage: run.config.targetLanguage,
    translationEnabled: true, voiceEnabled: false
  }, run.controller.signal);
  const id = core.safeSessionId(snapshot.id); run.sessionId = id;
  const start = Date.now(); let state = snapshot, failure = null;
  try {
    while (!state.terminal) {
      if (Date.now() - start >= 60_000) throw new Error("This text took more than 60 seconds. Choose a faster translation model in Hearth.");
      await delay(250, run.controller.signal);
      state = await request(run.config, `/v1/sessions/${id}`, "GET", null, run.controller.signal);
    }
    return core.completedTranslation(state);
  } catch (error) { failure = error; throw error; }
  finally {
    // A timeout/cancel must not leave an HTTP-owned model job consuming capacity.
    if (!state.terminal || run.controller.signal.aborted) {
      try { await request(run.config, `/v1/sessions/${id}`, "DELETE"); }
      catch (error) { throw new Error(`${failure ? failure.message + " " : ""}Could not confirm Hearth session cleanup: ${error.message || error}`); }
    }
    if (run.sessionId === id) run.sessionId = null;
  }
}
async function handle(message, sender) {
  if (!message || typeof message.type !== "string") throw new Error("Invalid Hearth extension request.");
  const trustedPopup = !sender.tab && typeof sender.url === "string" && sender.url.startsWith(extensionBase);
  if (message.type === "start") {
    if (!trustedPopup || !Number.isInteger(message.tabId)) throw new Error("Start translation from the Hearth extension button.");
    const config = core.validateConfiguration((await api.storage.local.get("configuration")).configuration || {});
    await api.tabs.sendMessage(message.tabId, { type: "hearth-stop" }).catch(() => {});
    await cancelRun(running.get(message.tabId));
    const run = { id: crypto.randomUUID(), config, controller: new AbortController(), sessionId: null, busy: false };
    running.set(message.tabId, run);
    try {
      await request(config, "/v1/capabilities", "GET", null, run.controller.signal);
      await api.scripting.executeScript({ target: { tabId: message.tabId }, files: ["core.js", "content.js"] });
      await api.tabs.sendMessage(message.tabId, { type: "hearth-start", runId: run.id });
      return { ok: true };
    } catch (error) {
      if (running.get(message.tabId) === run) running.delete(message.tabId);
      await cancelRun(run); throw error;
    }
  }
  if (message.type === "stop") {
    const tabId = trustedPopup ? message.tabId : sender.tab?.id;
    const run = running.get(tabId);
    if (run && !trustedPopup && message.runId !== run.id) throw new Error("This translation session has expired.");
    if (trustedPopup) await api.tabs.sendMessage(tabId, { type: "hearth-stop" }).catch(() => {});
    await cancelRun(run);
    if (running.get(tabId) === run) running.delete(tabId);
    return { ok: true };
  }
  if (message.type === "translate") {
    const run = running.get(sender.tab?.id);
    if (!run || message.runId !== run.id || run.controller.signal.aborted) throw new Error("Translation session ended. Start again from the extension button.");
    if (sender.frameId !== 0 || run.busy) throw new Error("Another website translation batch is already running.");
    if (!Array.isArray(message.texts) || message.texts.length < 1 || message.texts.length > core.limits.batchNodes || message.texts.some(value => typeof value !== "string" || !core.meaningfulText(value)) || message.texts.reduce((total, value) => total + value.length, 0) > core.limits.batchCharacters) throw new Error("Website translation batch exceeded its bounds.");
    run.busy = true;
    try {
      const translations = [];
      for (const text of message.texts) translations.push(await translateOne(run, text));
      return { ok: true, translations };
    } finally { run.busy = false; }
  }
  throw new Error("Unknown Hearth extension request.");
}
api.runtime.onMessage.addListener((message, sender, respond) => {
  handle(message, sender).then(respond, error => respond({ ok: false, error: String(error.message || error).slice(0, 8192) }));
  return true;
});
api.tabs.onRemoved.addListener(tabId => { const run = running.get(tabId); running.delete(tabId); void cancelRun(run).catch(() => {}); });
api.tabs.onUpdated.addListener((tabId, change) => {
  if (change.status === "loading") { const run = running.get(tabId); running.delete(tabId); void cancelRun(run).catch(() => {}); }
});
