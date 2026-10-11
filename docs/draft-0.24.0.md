# Hearth 0.24.0 draft: independent features and model expansion

Owner request, 2026-10-11: expand real speech, translation, OCR and voice choices;
add standalone read-aloud, voice notes and local document OCR. Draft PR and draft
APK releases only. This is not a public release or a phone benchmark claim.

| Slice | Implementation | Evidence / remaining gate |
| --- | --- | --- |
| Home | Read aloud, Voice notes, Documents cards; all-features picker and settings shortcuts | Stable feature IDs, navigation restore tests; owner UI review |
| Speech | Parakeet TDT v3 and SenseVoice native adapters using the existing sherpa runtime | Publisher pins, production host decodes, four-ABI build/export checks; phone quality/timing open |
| Translation | Five compact Marian directions; English pivot only when explicitly chosen | Production native decode examples and pinned multipart tree hashes; representative language quality open |
| TTS | Independent text/language/voice/speed/gender/loudness, installed offline Android engines, Supertonic and explicit cloud route | Request/ownership tests; no paid requests or new-family phone performance claims |
| Extra voice families | Real Kitten and Kokoro self-hosted adapters; installed Android engine discovery | Server adapters and protocol tests; server voices are not bundled on-phone models |
| Documents | PDF/DOCX/image imports, direct document text, sequential OCR, per-page voice, explicit translation and TXT export | Bounded OOXML/XML tests, Android framework tests; owner SAF/PDF checks |
| OCR choices | Seven additional pinned mobile readers | Publisher dictionaries, real graph and production native fixture checks; multilingual real-document accuracy open |
| New vision OCR family | Experimental GLM-OCR document reader through the existing pinned llama/mtmd runtime | Actual native fixture and cancellation proof; exact two-file pins, four-ABI packaging, 64-bit execution only; no Arabic or phone-speed claim |
| Voice notes | Model-free recording, optional speech/translation, private archive, listen/rename/delete/export and storage/privacy acknowledgement | WAV continuity/recovery, stable text IDs, API28/36 storage tests; microphone/device journey open |
| Accounts / sync | Optional local display-name profile available; no fake authentication | Actual login/sync requires the owner's backend choice/endpoint. Local/FOSS recording remains independent |

Native/Kotlin library contracts remain reusable and independent of feature UI.
A model enters the supported catalog only when its real artifact, loader,
source/language contract, digest and runtime path are known. Larger candidates,
custom runtimes and restrictive weight licenses remain named research items.
No default model is silently replaced by a new experimental option.

FOSS retains no network permissions; client and delegated-download paths remain
disabled by the distribution policy.
Model storage and archive limits remain enforced. Documents/audio are not
implicitly uploaded. Optional cloud actions use explicit saved connections,
with real errors and no paid retry masquerading as local success.

## Validation log

The final combined Gradle gate completed successfully in 14m 15s with one worker:

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin \
  :common-jni:externalNativeBuildDebug :app:assemblePlayQa :app:assembleFossQa \
  --max-workers=1 -Dorg.gradle.jvmargs="-Xmx3g -Dfile.encoding=UTF-8" \
  -Pkotlin.compiler.execution.strategy=in-process
python3 scripts/verify-release.py --build-type qa
```

App suites pass in Play/FOSS Debug, QA and Release (497 cases each); cloud-only
FOSS guards skip eight cases and the offline-only Play guard skips one. Common-jni
passes 110 cases in both Debug and Release. Counts are not inflated across variants.


| Check | Result |
| --- | --- |
| Common native utilities, ASan/UBSan | PASS: 20 executable suites, including the new vision input bounds/alpha fixtures |
| Speech native host suite, ASan/UBSan | PASS: 72 checks |
| Python voice / speech package / ABI suites | PASS: 14 / 8 / 3 tests |
| All four rebuilt speech runtimes | PASS: eight binaries, exact hashes/source snapshot, ELF/export/dependency/alignment checks |
| All four rebuilt translation/vision runtimes | PASS: exact hashes/source snapshot, 11 intended JNI exports, dependency isolation and page alignment |
| Required aggregate Gradle tests and both QA compiles | PASS: full `test` task; 497 app tests per variant, 110 common-jni tests; expected flavor-specific skips |
| externalNativeBuildDebug and both QA APK sets | PASS: Debug native builds for all four ABIs, release-style native builds, Play/FOSS QA minification/lint and ten APKs |
| Packaged APK verifier and SHA256SUMS | PASS: all ten QA APKs; ELF/ABI, source/binary hashes, JNI exports, dependencies, alignment, model dictionaries and network permissions; exact packaged license notices; SHA256SUMS generated |

Actual production host inference exercised every added speech variant, all five
Marian pairs, seven mobile OCR recognizers and the pinned GLM decoder/projector.
These are fixture and compatibility checks, not representative multilingual
accuracy or physical phone performance. GLM's last CPU proof ran under heavy
compilation load (59.6 s model load, 117.0 s inference, 1.62 s in-flight cancel);
its output matched the fixture and cancellation published no text.

The first completed FOSS debug run reported 496 tests, three failures and eight
skips. Its exact failures were `Format specifier '%02$2d'`,
`expected:<[ru, zh]> but was:<[ru, zh, ar, es]>`, and
`expected same:<java.lang.IllegalStateException: Provider CloseStream rejected>
was not:<java.lang.IllegalStateException: Provider CloseStream rejected>`.
The fixes preserve valid zero-padded duration formatting, cover the expanded
pinned pivot matrix, and assert error type/message plus release instead of
coroutine exception object identity. The corrected aggregate rerun passed. The initial failed run is not reported as a pass.

An intermediate build overlapped the final TTS review edit and failed with
`Unresolved reference 'savedVoiceMissing'.` Its new regression tests saw the
old compiled production class. That run is not a pass; the aggregate gate was
restarted only after all production and test sources were frozen. Earlier
superseded integration passes likewise are not reported as successful.

The requested Home/native/notes/document implementation has an independent
ownership review in `docs/evidence/feature-ownership-review-2026-10-11.md`.
Physical device, microphone/Keyguard/SAF/real document journeys, paid provider
requests and real Kitten/Kokoro model synthesis are not performed here.
Account login/sync remains open until an actual backend is supplied.
