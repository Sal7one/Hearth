# Conversation mode — UI and delivery plan

Status: original view added in 0.7.0; 0.8.0 adds Face to face, direct swap and cloud text translation.
Requested by the owner on 2026-09-12.

## Face to face (0.8.0)

Home → Face to face opens the split view directly (0.8.1). The conversation screen
also has a visible Face to face button. Options → Open face-to-face view still
switches presentation without recreating the
controller or clearing the current session. My language is at the bottom; their
half is rotated 180 degrees. Each half has one language selector, a large text area
and Speak/Finish button. Tap the colored reading area for this conversation's
history in that language and orientation. Error and pending states are preserved.
Both layouts expose a quick swap arrow. Historical message directions never change.

The settings sheet returns to the original conversation view and holds translation
selection, speech model/connection links, typing, saved conversations, new/rename/
share, text size, original-text visibility, save-history and offline read-aloud.
Keep screen awake is configurable. Dark/light/system styling follows the app theme.

## Implemented first release

`conversation/ConversationScreen.kt` is a separate Home destination. Two explicit
speaker buttons use the existing model capability resolver; language selections
are kept separately from overlay preferences. The foreground screen owns mono
16 kHz microphone capture. It requests only `RECORD_AUDIO`, stops when the app
is backgrounded or the screen leaves composition, and caps each deliberate turn
at 60 seconds. Leaving during work preserves finalized original text and marks
the turn interrupted. There is no automatic recording restart.

Speech runs through `CaptionEngineController` in original-language CC mode, then
the selected conversation translator runs on that turn: an installed ML Kit/GGUF
model, or an explicitly configured Google Cloud, Microsoft Azure, DeepL or
LibreTranslate text connection. Speech and translation providers are independent.
See [connection setup](conversation-cloud-translation.md). The page labels the route. It never treats OpenAI's translation-only
stream as an original transcript or silently opens two paid streams. Automatic-only
adapters without declared recognition-language coverage cannot enable the microphone
buttons; typed translation still works. A supported one-way direction remains usable.

The same `LocalWorkGate` as captions and benchmarks prevents concurrent model
workloads. Cancellation retains the lease until the controller has released native
handles and the translator is closed. Models currently reopen per turn; warming
across turns is deferred until ownership and both-direction runtime support justify it.

`ConversationStore` saves stable turns transactionally in SQLite under
`Context.noBackupFilesDir`: no automatic cloud backup, raw audio or credentials.
Original text is saved as finals arrive; translation updates the same turn identity.
Interrupted pending rows remain interrupted on reopen, never automatically retried.
History supports continue, delete, rename and explicit text sharing. Turning saving
off starts a temporary session without deleting existing history. New conversation,
Type instead, large-text presentation/flip, per-message playback, text size and
automatic speech options are available. Message text does not expire.

System playback accepts only installed offline voices in the actual target language.
There is no default-language fallback. Playback occurs only outside microphone work,
offers Stop, reports voice initialization/selection/utterance errors, and requires
another explicit Speak tap before recording resumes. Auto playback starts off.

`ConversationDataTest` covers wrong-session/deleted-turn late results, interrupted
restoration, preservation of completed pairs, and immutable historic directions
after swapping languages. Device acceptance still needs a real two-person exchange,
rotation/background interruption, process restart, RTL/large-font/TalkBack and
offline voice availability checks. The fuller design below records intended behavior;
it is not a claim that every future optimization or provider route has been shipped.

Let two people take turns speaking different languages on one phone. Show what
each person said and its translation, keep the conversation available later, and
optionally read translations aloud. Reuse working speech/translation engines and
model downloads. This is an in-app destination, not another overlay settings tab.

## Permission answer

Android requires `RECORD_AUDIO` for device playback capture as well as microphone
capture. Device capture additionally requires the system MediaProjection consent
prompt. Our `CaptionCaptureService.buildPlaybackRecord` uses playback capture;
`buildMicRecord` separately uses `VOICE_RECOGNITION`. Choosing Device audio does
not open the physical microphone.

Source: [Android playback capture requirements](https://developer.android.com/media/platform/av-capture#building_a_capture_app).

Improve the existing explanation before the system permission request:
“Android calls this microphone permission. Device audio captures sound played by
apps; your microphone is not used.” Show this in the existing start flow, without
adding another confirmation screen. Keep the required system prompt.

Conversation requests microphone access when the user first taps a Speak button.
It does not request display-over-other-apps or screen-capture access. Typed input,
saved history and text presentation work without microphone permission. Capture
stops when leaving the conversation or locking the phone; returning shows a clear
Resume action. Reuse existing foreground-service infrastructure where needed,
without making notification permission a prerequisite for viewing the screen.

## One screen, two people

Add one **Conversation** entry on Home, beside the existing captions workflow.
Keep captions settings and saved choices intact. No new bottom navigation or
setup wizard. Reopening Conversation restores its last language pair and draft.

```text
‹ Back          Conversation           History

   Me: العربية       ⇄       Them: English
   On device · Ready                   Options

  Me · Arabic → English
  أين محطة القطار؟
  Where is the train station?
                           Play   Show   ⋯

  Them · English → Arabic
  It is around the corner.
  إنها عند الزاوية.
                           Play   Show   ⋯

              [ New messages ↓ ]

  Ready                           Type instead
  [ Speak Arabic ]             [ Speak English ]
  Tap to start; tap again to finish.
```

The paired language chips reuse our native-name language picker. Flags remain
decorative; language names carry meaning. Both languages are explicit for the
first version, so buttons identify the speaker without automatic speaker/language
detection. Models whose adapters only support Auto remain usable when they can
recognize the chosen language; label “Automatic detection” honestly and do not
pretend to pass a language hint. Swap affects future turns only.

Each message shows a speaker/direction label, readable original text and a larger
translation. Give each text block its own writing direction. An unfinished turn
shows live text and a Listening indicator; translation gets a visible pending
state. New turns never expire. Scrolling up holds the reading position, with a
New messages button to return to live text. Returning from Options restores scroll.

Only the selected Speak button becomes **Finish**. The other cannot start a second
recording concurrently. State is explicit: Ready → Listening → Finishing →
Translating → Ready, with Speaking when playback is requested. Finalization may
produce several segments; join them under the same turn without duplicating text.
Allow canceling a pending operation and keep already finalized content.

**Show** opens the translated message in large, uncluttered text for the other
person, with Play and Close. Optional Flip rotates this presentation for someone
opposite the phone; it does not rotate the entire conversation. **Type instead**
uses the selected side's language and the same translation/history path. This
makes the feature useful when someone cannot speak or a street is too noisy.

Options is a small sheet: selected Local/Cloud setup, automatic speech (off by
default), text size and Save history (on, visibly labeled “Saved on this device”).
Use existing model/key screens only when setup is missing or explicitly opened.
Switching mode must not silently replace the user's working cloud configuration.

## What we can reuse, and what is missing

| Existing code | Conversation work |
| --- | --- |
| `CaptionCaptureService`, `CaptionStartActivity` | Separate capture/session ownership from `overlay.show()` and mandatory overlay permission. One capture owner; offer an explicit switch if captions are already running. |
| `CaptionEngineController`, stable `CaptionLine.id`, local translation bridge | Retain source and target separately, attach results to immutable turn IDs, and freeze languages/model choices per turn. Do not route a late translation to the next speaker. |
| `CaptionReading`, 120-line in-memory caption history | Add durable conversation storage; do not treat the bounded overlay tail as the saved archive. |
| `CaptionLanguages`, speech profiles, translation catalog | Check both recognition languages and both translation directions. Reuse small existing capability definitions. |
| `CaptionSpeaker` system/native/cloud implementations | Add truthful readiness, utterance completion/error/cancellation, supported voice checks and protection against recording our own playback. |

One important gap: `OpenAiTranslateClient` currently consumes only
`session.output_transcript.delta` and passes translated output through the generic
STT callbacks. It does not retain an independent original transcript. Whisper's
translation task similarly produces translated text rather than a source/target
pair. A field named `original` is therefore not proof that source words exist.

For full paired Conversation history, use original-language STT followed by text
translation, as the local bridge already does. For the existing live cloud route,
first verify whether source transcription can be obtained and aligned in that
protocol. If it cannot, expose the route as translation-only or let the user
choose a supported paired route. Never invent the original by back-translating,
silently launch a second paid stream, or claim all current routes retain both.
Provider protocol verification belongs to implementation, not this UI proposal.

Changing speaker direction may require reconfiguration in current engines. Keep
one recognizer and translator warm where supported; serialize unavoidable changes
and display Preparing when necessary. Avoid loading two large model pairs at once.
Do not promise latency improvements merely from choosing a language.

## Saved turns and speech playback

Persist conversations and finalized turns transactionally in app-private storage,
using the existing database layer if available, otherwise a small local database.
Store session ID, turn ID/order, speaker, source/target language, original text,
translated text, timestamps, processing status, actual error and route/model IDs.
Original may be absent only for an explicitly labeled translation-only route.
Partial text stays temporary; a finalized original is saved immediately, and the
translation updates that same row. After process death, pending turns become
interrupted rather than silently restarting network requests.

History lists the language pair, date and a short text preview. Open/continue,
rename, delete and explicitly share/export text from that screen. Save-history-off
keeps new turns only for the current session and does not delete prior sessions.
Do not persist raw audio or keys with conversations. Exclude conversation data
from automatic cloud backup if it is described as saved only on this device.

Playback uses translated text and the target language. Replay is per message;
automatic playback is optional and final-only. Stop current playback before a new
turn, and suspend microphone capture while the app speaks. Discard buffered audio
from that playback interval; don't rely on acoustic echo cancellation alone. Resume
listening only on the next explicit Speak tap in v1. Capture resumes safely after
playback failure or cancellation, with a visible Ready state.

Current system TTS can fall back to a default-language voice and does not expose
completion to the caller. Replace that behavior for this flow: report missing
language data, offer voice setup, and keep the translation readable. Offline mode
must select a genuinely installed offline voice. Native voice bundles also need
language validation; passing a language tag alone does not change their language.
Cloud speech remains an explicit BYOK choice in play; foss never delegates online
speech. Use one active utterance, no growing speech queue; Stop is always visible.

## Accessibility and failure behavior

- Tap-to-start/tap-to-finish works without holding or dragging; controls are at
  least 48 dp with clear TalkBack labels including language and current action.
- Support large fonts, RTL and landscape without hiding Speak, Finish or Stop.
  Color, animation and sound are never the only listening/progress indicators.
- Do not announce every partial update. Preserve accessibility focus and offer
  final-text announcements independently from automatic translated speech.
- Translation failure keeps the original and actual error, with Retry translation
  for that turn. Speech-playback failure never removes text or changes its language.
- Show the current local/cloud route and any unsupported direction before recording.
  Let supported one-way translation work, with the unavailable direction explained.

## Delivery slices and acceptance checks

1. **RT-16 — Conversation screen and deliberate turns.** Separate permissions and
   presentation; add the Home entry, language pair, two Speak buttons, live paired
   cards and Type instead. Resolve source/translation provenance for each enabled
   route. Done when an Arabic/English exchange works on a supported setup without
   overlay or screen-capture prompts and without altering captions preferences.
2. **RT-17 — Keep and revisit conversations.** Persist stable turns and translation
   updates, add History/continue/delete/export, hold scroll and Show presentation.
   Done when leaving/reopening or process restart preserves finalized paired text;
   late results cannot update another turn or resurrect a deleted session.
3. **RT-18 — Hear the reply.** Reuse TTS with completion/error events, language-aware
   voice availability, replay/Stop, and optional automatic speech. Done when replies
   play in the target language, the microphone never transcribes our playback, and
   a missing voice leaves usable text and a concrete recovery action.
4. **RT-19 — Finish the accessible flow.** Check TalkBack, font scaling, RTL,
   interrupted capture, unavailable directions and history-off behavior. Verify
   the existing caption overlay still starts, stops and recovers from tap-through.

Run normal repository gates for implementation commits and both flavor/release
checks before APK delivery. Use focused lifecycle/storage checks where they guard
lost or misattributed text and brief real-device exchanges. No benchmark framework,
extra telemetry, JNI rewrite, new model format or navigation framework is required.

Later, only if useful: favorite travel phrases and a hands-free turn detector.
Maps, location permissions, camera/sign recognition and travel-service integrations
are outside this feature. This plan does not block shipping the working captions app.
