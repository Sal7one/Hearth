"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const core = require("../core.js");

function fixture(nodes, translate = async texts => texts.map(text => "Translated " + text)) {
  let listener, mutations, timerId = 0, scans = 0;
  const timers = new Map(), requests = [];
  function element(tagName = "DIV") {
    const attributes = {};
    return { tagName, nodeType: 1, style: {}, parentElement: null,
      hasAttribute: key => Object.hasOwn(attributes, key), getAttribute: key => attributes[key] ?? null,
      setAttribute: (key, value) => { attributes[key] = value; },
      append() {}, remove() {}, addEventListener() {},
      attachShadow: () => ({ append() {} }) };
  }
  const body = element("BODY");
  nodes.forEach(node => { node.isConnected = true; node.parentElement = body; });
  const context = vm.createContext({
    HearthWebsite: core, Node: { ELEMENT_NODE: 1 }, NodeFilter: { SHOW_TEXT: 4 },
    document: {
      body, documentElement: body, createElement: element,
      createTreeWalker() {
        scans++; let at = -1;
        return { currentNode: null, nextNode() { this.currentNode = nodes[++at]; return this.currentNode || null; } };
      },
      createRange() {
        let node;
        return { selectNodeContents(value) { node = value; }, getClientRects() {
          return [{ left: 0, right: 100, top: node.visible ? 0 : 900, bottom: node.visible ? 20 : 920, width: 100, height: 20 }];
        } };
      }
    },
    getComputedStyle: element => element.computedStyle || ({ display: "block", visibility: "visible", opacity: "1" }),
    innerWidth: 800, innerHeight: 600,
    window: { addEventListener() {}, removeEventListener() {} },
    MutationObserver: class { constructor(callback) { mutations = callback; } observe() {} disconnect() {} },
    setTimeout(callback) { timers.set(++timerId, callback); return timerId; }, clearTimeout(id) { timers.delete(id); },
    browser: { runtime: {
      onMessage: { addListener(callback) { listener = callback; } },
      async sendMessage(message) {
        requests.push(message);
        if (message.type === "translate") return { ok: true, translations: await translate(message.texts) };
        return { ok: true };
      }
    } }
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../content.js"), "utf8"), context);
  return {
    requests, timers, get scans() { return scans; },
    start(id = "run") { listener({ type: "hearth-start", runId: id }, {}, () => {}); },
    stop() { listener({ type: "hearth-stop" }, {}, () => {}); },
    mutate(changes) { mutations(changes); },
    async tick() {
      const next = timers.entries().next().value;
      if (!next) return false;
      timers.delete(next[0]); next[1]();
      await new Promise(resolve => setImmediate(resolve));
      return true;
    }
  };
}

test("huge-page cursor survives unrelated mutations, translations do not feed back, and idle scans settle", async () => {
  const hidden = Array.from({ length: 4500 }, () => ({ data: "Offscreen paragraph", visible: false }));
  const visible = Array.from({ length: 6 }, (_, id) => ({ data: "Visible paragraph " + id, visible: true }));
  const h = fixture([...hidden, ...visible]);
  h.start();
  await h.tick(); // first bounded 4,000-node slice
  assert.equal(h.requests.length, 0);
  h.mutate([{ type: "characterData", target: hidden[0] }]);
  for (let tries = 0; tries < 20 && await h.tick(); tries++) {
    // These are the mutation records from this extension's own text-node writes.
    const own = visible.filter(node => node.data.startsWith("Translated")).map(target => ({ type: "characterData", target }));
    if (own.length) h.mutate(own);
  }
  const batches = h.requests.filter(message => message.type === "translate");
  assert.equal(batches.flatMap(batch => Array.from(batch.texts)).length, 6);
  assert.ok(visible.every(node => node.data.startsWith("Translated Visible")));
  assert.ok(h.scans <= 2, "one explicit revisit is enough despite own text mutations");
  assert.equal(h.timers.size, 0, "no repeated idle full-page scan");
  h.stop();
  assert.deepEqual(visible.map(node => node.data), Array.from({ length: 6 }, (_, id) => "Visible paragraph " + id));
});

test("restarting during an old in-flight batch does not stall or apply its stale reply", async () => {
  const node = { data: "Original paragraph", visible: true };
  let resolveFirst; let calls = 0;
  const h = fixture([node], texts => {
    if (++calls === 1) return new Promise(resolve => { resolveFirst = resolve; });
    return Promise.resolve(texts.map(() => "Current translation"));
  });
  h.start("old"); await h.tick();
  h.stop(); h.start("new");
  resolveFirst(["Stale translation"]);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(node.data, "Original paragraph");
  for (let tries = 0; tries < 5 && await h.tick(); tries++) {}
  assert.equal(node.data, "Current translation");
  assert.equal(calls, 2);
  h.stop(); assert.equal(node.data, "Original paragraph");
});

test("text inside an invisible ancestor is not sent despite its positive layout rectangle", async () => {
  const node = { data: "Hidden private content", visible: true };
  const h = fixture([node]);
  node.parentElement.computedStyle = { display: "block", visibility: "visible", opacity: "0" };
  h.start(); await h.tick();
  assert.equal(h.requests.filter(message => message.type === "translate").length, 0);
  assert.equal(node.data, "Hidden private content");
  assert.equal(h.timers.size, 0);
  h.stop();
});
