# Feature pipeline ownership

Audited for 0.22.3 (2026-09-21). This records code and regression-test evidence;
it is not a claim that every provider/model combination has been device-tested.

| Feature | Configuration snapshot | Runtime ownership / teardown |
|---|---|---|
| Live captions | CaptionConfigStore, speech connection and translator snapshot per generation | Capture service owns recorder/projection and workload lease. Controller retires speech, text bridge and speaker; awaitReleased includes cancelled initialization and translation cleanup. |
| Conversation / face to face | Speech config per turn; shared conversation/typed translator, credentials and languages frozen before the turn | One microphone turn. Recognition drains before translation. Cancellation joins recording and model cleanup before releasing the workload lease. Face to face is a presentation of this same controller. |
| Typed text | Conversation/typed provider and local model choice; newest input revision | One resident translator and conflated pending request. Clear, blank input, invalid setup and backgrounding invalidate old work. Local lease stays held until model close completes. Cloud requests have their own transport owner. |
| Camera / image OCR | OCR profile/source/target and camera/reading translator | Bounded image queue, frame epochs and translation revisions. Joins translation worker before destroying OCR/MT and releasing lease; discarded bitmaps are recycled. |
| Manga / screen reading | Same selection as camera, frozen at overlay start | Reading service owns projection/windows; CameraOcrController owns inference. Movement invalidates old output; setting changes restart through setup. |
| Read aloud | Shared voice default; request chooses system/custom backend | One audible owner across pages; one serialized local voice synthesis at a time. Per-instance ONNX run options/handles; Stop cancels only that player. Speech/MT may coexist intentionally with read aloud. |
| Benchmark | Explicit selected models and directions | LocalWorkGate prevents overlapping heavy inference workloads; cleanup precedes lease release. |
| Model/file downloads | Pinned artifact and destination | Installing does not activate a model. Explicit Use actions in the model/download screens configure the main speech/model default. FOSS has no network permission or delegated network downloads. |

## Selection boundaries

There are three translation groups: captions, conversation/typed text, and camera/reading.
Feature pickers now write only their group's model/provider. Conversation and typed
text deliberately share a choice, as do camera and reading. The settings translation
hub edits conversation/typed text. `Use everywhere` explicitly updates all three.

Existing installations without a group-specific model continue using the previous
main model as a fallback. The first explicit choice pins that group's model. A later
caption-model change no longer replaces a pinned choice. Download/model-library Use
remains an explicit main-default action; use the feature picker to change a pinned
feature choice. Language pickers, labels, controller snapshots and reading shortcut
preflight use the same resolved model.

Connections and voice defaults remain shared libraries. A saved connection change
is used by future snapshots; an in-flight request keeps its original route/credentials.
No feature cancels the whole shared HTTP client or calls CommonJni.cancelAll.

## Fixes and native review

- Removed cross-feature writes to the caption translator from camera/reading and
  conversation/typed pickers.
- Fixed typed blank input retaining native ownership; invalid setup and same-language
  input now retire previous work. A model that finishes loading after Clear does not
  start the obsolete translation.
- Read aloud reports one terminal outcome rather than a failure followed by a null
  completion. Remote voice validation now happens before allocating a client.
- Reviewed speech/router, translation, Paddle/Japanese OCR and Supertonic JNI handle
  tables, cancellation flags, per-session mutexes and ONNX run options. No production
  C++/ABI or vendored runtime change was justified by this pass.
- Added sanitizer-backed LeaseRegistry coverage: cancel one handle, retire while
  inference holds a lease, use and close another handle during that wait, reject stale
  handles and safely move-assign the last lease. Added simultaneous translation-bridge
  coverage for isolated cancellation and stale-result suppression.

## Limits and follow-up device checks

LocalWorkGate rejects competing heavy inference sessions. The reading Start button
explicitly says it stops live audio captions. Both reading and caption entry screens
now wait up to 30 seconds for previous model cleanup before launching the next service.
They show waiting progress and a timeout message in English, Arabic and Chinese. Cancellation of that wait
does not release another owner. Other conflicting starts fail without stealing a lease. It is a memory/lifetime guard, not automatic
scheduling or a promise that all features can run concurrently. Voice has a separate
serialization gate so captions can still be read aloud.

Native host checks do not measure real model throughput, thermal behavior or GPU
execution. Useful owner checks: keep captions running while changing another
feature's translator; stop/restart during local model initialization; clear typed text
mid-translation then start a different feature; switch read-aloud pages; change
camera/manga model and confirm the caption selection is retained. Paid cloud and
full local model quality checks still require actual account/audio/model runs.
