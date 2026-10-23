"use strict";
const api = globalThis.browser || globalThis.chrome;
const $ = id => document.getElementById(id);
$("origin").value = api.runtime.getURL("").replace(/\/$/, "");
function show(message, error = false) { $("status").textContent = message; $("status").className = error ? "error" : ""; }
async function currentTab() {
  const [tab] = await api.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id || !/^https?:\/\//.test(tab.url || "")) throw new Error("Open an ordinary HTTP or HTTPS website first. Browser settings and protected pages cannot be translated.");
  return tab.id;
}
async function action(type) {
  $("start").disabled = true; $("stop").disabled = true;
  try {
    const tabId = await currentTab();
    if (type === "start") {
      const configuration = HearthWebsite.validateConfiguration({ url: $("url").value, token: $("token").value, sourceLanguage: $("source").value, targetLanguage: $("target").value });
      await api.storage.local.set({ configuration });
      show("Connecting to Hearth…");
    }
    const response = await api.runtime.sendMessage({ type, tabId });
    if (!response?.ok) throw new Error(response?.error || "Hearth extension connection failed.");
    show(type === "start" ? "Running on this tab. Scroll to translate new visible text. The page toolbar shows engine errors." : "Stopped. Original text restored.");
  } catch (error) {
    show(String(error.message || error), true);
  } finally { $("start").disabled = false; $("stop").disabled = false; }
}
$("start").addEventListener("click", () => action("start"));
$("stop").addEventListener("click", () => action("stop"));
api.storage.local.get("configuration").then(({ configuration }) => {
  if (!configuration) return;
  $("url").value = configuration.url || ""; $("token").value = configuration.token || "";
  $("source").value = configuration.sourceLanguage || "en"; $("target").value = configuration.targetLanguage || "ar";
}).catch(error => show(String(error.message || error), true));
