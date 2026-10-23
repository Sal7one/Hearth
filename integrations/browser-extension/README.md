# Hearth website translation

An unpacked browser extension for Chrome/Edge 121+ and Firefox 121+. It translates
visible DOM text using Hearth desktop's existing translation route. Links stay
links, forms stay editable, and page controls keep receiving mouse/keyboard input.
It changes individual text-node values, never page HTML, URLs or event handlers.
Stop restores the original values wherever the website has not since changed them.

## Start

1. Chrome/Edge: open the extensions manager, enable Developer mode, choose **Load
   unpacked**, and select this directory. Firefox: open `about:debugging`, choose
   **This Firefox → Load Temporary Add-on**, then select `manifest.json` here.
   Temporary Firefox installs disappear when Firefox closes.
2. In Hearth, configure and test a translation engine. Open **Integrations**.
3. Open the Hearth extension button. Under **Pair this extension in Hearth**, copy
   its exact extension origin under **Translate websites** and choose **Save browser
   access**. Start the server, then copy its current API address and token into the
   extension. No website origin needs access to Hearth.
4. Choose source/output language codes, such as `en` and `ar`, then **Start on this
   tab**. Choose an explicit source for translation models that cannot detect it.
   Scroll to process new visible text. The page toolbar shows actual engine errors.
5. **Stop & restore** stops scheduling, restores originals, and requests cancellation
   of an active HTTP-owned translation. Navigating away or closing the tab also
   cancels that tab's work. A new page requires explicit activation again.

The desktop API port/token may change when its server restarts. Replace them in
the extension. Browser settings pages, extension stores and other protected pages
cannot be injected. Firefox's internal extension UUID can differ across profiles;
pair the origin shown in that profile. The extension is a development artifact,
not a store-signed or browser-device-verified release.

## Processing and privacy

- Only the explicitly activated tab's top-level page is scanned. No all-sites
  content-script permission, iframe access, background browsing history collection,
  remote script, telemetry or model downloader is included.
- Only a numeric `http://127.0.0.1:PORT` endpoint is accepted. Credentials, paths,
  queries, redirects and remote hosts are rejected. The API token stays in the
  extension's local profile storage/background context, never sent to the website.
  Chrome storage access is restricted to trusted extension contexts where supported.
  No provider API key is used by this extension.
- Visible text goes to Hearth; **the translation route selected in Hearth still
  determines whether it runs locally or sends text to a configured cloud provider**.
  Starting this extension is explicit consent to process this tab's visible text.
- Form/button/input/select/password/editable content, code/preformatted blocks,
  hidden/`translate="no"` regions, media, SVG, canvas and the toolbar are excluded.
  Normal page content can still contain sensitive text: start only on pages you
  want translated. No background access to other tabs is granted.
- At most four text nodes/3,000 characters per batch, one sequential inference per
  batch. Each block is at most 1,200 characters. Up to 4,000 nodes are inspected
  per scan slice and 5,000 translated nodes retained per tab. Removed DOM nodes
  are pruned. Large pages continue in bounded slices; scans settle when idle.
- Mutation/scroll activity is debounced. A stale reply cannot replace website
  content that changed during translation. Nodes are never hidden, screenshot,
  intercepted or made noninteractive. The small toolbar alone handles its buttons.
- Each HTTP request has a 10-second transport deadline; an individual text session
  has a 60-second inference deadline, followed by authenticated cancellation.
  Failures are visible, and already translated text remains until Stop & restore.
  Browser background suspension ends the current run; explicitly Start again.

The extension handles real website text across language families supported by
Hearth's chosen translator. It does not OCR images or claim frame-rate manga
translation. Captured images use Hearth's separate image-reading service, whose
current Manga OCR reader is Japanese. Screenshot permission and native overlay
pass-through are separate desktop features.

## Validate

No npm install or build is needed:

```sh
node --test integrations/browser-extension/test/*.test.cjs
node --check integrations/browser-extension/background.js
node --check integrations/browser-extension/content.js
node --check integrations/browser-extension/popup.js
```

Tests exercise bounds, excluded ancestors, visibility, exact-node replacement,
safe restoration, language/URL validation, authenticated background API dispatch,
page/tab/frame isolation, cancellation and actual error propagation. They do
not claim installation or live browser inference. Owner acceptance: load the
extension, pair its origin, translate an English page into Arabic, verify links
and a form still work, scroll dynamic content, then Stop & restore. Repeat with a
deliberately invalid translation setup to check the actual Hearth error is shown.

The dual MV3 background fields follow [Mozilla's cross-browser background
documentation](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/background).
Current-tab injection uses [Chrome's activeTab
permission](https://developer.chrome.com/docs/extensions/develop/concepts/activeTab)
and [`scripting.executeScript`](https://developer.chrome.com/docs/extensions/reference/api/scripting).
The packaged icon reuses Hearth's existing first-party desktop `hearth.png`.
