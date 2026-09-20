# Hearth 0.22.2 / code 44 — simple cloud routing and streaming shutdown

21 September 2026. v84 QA. No native, model-weight, endpoint or key-storage changes.

## Reproduced cause

The earlier selection fix covered advanced setup and the cloud connection page,
but `CaptionHome` still copied the engine directly. Nemotron + explicit local
translation → Cloud retained `textTranslationProviderId=local`. Routing correctly
honored that stale override, opening OpenAI's transcription client followed by
Hy-MT2. Auto source transcription does not provide the source metadata this local
bridge requires; the unknown/mixed-source notice follows from that wrong route.
This was reproduced through the 0.22.1 phone UI before installing the fix.

The simple selector now uses `selectCaptionEngine`, including Cloud reselection.
Selecting OpenAI/Soniox explicitly clears the old text override; a translator
chosen deliberately afterward remains supported. Saved local models and the target
are preserved. Existing saved overrides are not silently migrated: reselect Cloud
or Streaming · OpenAI once after updating, or choose its integrated translator.

Explicit speech selection increments a persisted revision, so switching cloud
connections triggers a service restart even if the engine enum remains Cloud.
The cloud mode is saved before the config notification; route, language and client
creation use one mode snapshot for each load.

## Socket lifecycle

The shared streaming engine now retires before closing its socket, cancels queued
work, waits for the sender during release, rejects late callbacks and cancels
pending initialization. A deliberate switch cannot report its own cancelled send
as an active session failure. Genuine send failures stop audio admission, remain
failures after Clear/finalize, and retain the first provider error over generic
send/close fallout. A later provider cause can replace an earlier generic send
rejection. Normal capture end drains the engine's audio queue before invoking the
provider's end-of-input hook. This does not add a new OpenAI protocol or reconnect
policy. The owner's single socket message alone cannot establish the original
server/network cause; future actual provider errors remain visible.

Reference checked: [OpenAI realtime translation guide](https://developers.openai.com/api/docs/guides/realtime-translation).
The dedicated translation session remains separate from the transcription route.
No API model, target validation or billing behavior was changed.

## Validation

Final gates and both QA builds passed:

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin \
  :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

Six app suites: 303 cases each, zero failures/errors; existing skips one per Play
variant and two per FOSS variant. Common JNI debug/release: 74 each, no failures,
errors or skips. Seven new tests cover Auto → Spanish with a stale local override,
reselection/revision persistence, blocked send vs release, late callbacks and
connection acknowledgement, preserved provider errors, terminal send rejection,
and queued audio before end-of-input. No native source changed.

Phone: Samsung SM-S908E / Android 16 (not the owner's reported SM-S938B). The
released 0.22.1 selector reproduced retained Hy-MT2. Upgrading in place, reselecting
Cloud in the simple sheet displayed `OpenAI · cloud translation`, retained Spanish
and survived process restart. A separate Nemotron → Cloud journey also passed.
Original Arabic target and OpenAI selection were restored; keys and history were
untouched. Final QA was installed again after the final build.

Limits: phone validation covers route selection, persistence and UI. Socket races
use fake clients. No paid API request or live Spanish speech translation was run;
the test phone has no configured OpenAI key. FOSS is host-tested and artifact-checked,
not installed over the owner's existing cloud build. APK checks cover permissions,
native hashes/dependencies and 16 KiB alignment.

Evidence: `/tmp/hearth-v44-final-gates.log`, `/tmp/hearth-v44-artifacts.log`,
`/tmp/hearth-v76-before-stale-route.png`, `/tmp/hearth-v84-after-cloud-selection.png`.
Delivery: v84 cloud/offline QA APKs in a GitHub test draft; public 0.22.1 assets are
left intact. The draft is pinned to the matching source commit.
