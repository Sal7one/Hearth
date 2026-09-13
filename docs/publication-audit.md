# Publication audit — Hearth 0.8.2

Date: 2026-09-13. Scope: this standalone repository and its existing Git history,
Android flavors, credential handling, native supply chain, documentation and builds.
This audit does not publish the repository or certify every provider/model/device.

## Fixed before publication

- Speech key encryption now lets Android Keystore generate the GCM IV. Each provider
  stores its own version/mode. Legacy ciphertext is rewrapped only after a successful
  read, with both existing key types available when the old shared mode was stale.
  Reads never replace key material; deleting one provider never deletes shared keys.
- System TTS in captions and conversation selects only installed offline voices in
  the requested language. Region preference is shared and covered by host tests.
- Caption logs retain timing/state rather than transcripts or provider response bodies.
- Legacy HTTP/WebSocket entry points reject foss calls before making requests and
  disable redirects. Existing provider protocol tests remain in place.
- Vendored libraries are retained byte-for-byte in APK packaging. Verification now
  checks their packaged hashes, speech adapter/pin manifests and packaged provenance,
  alongside native dependencies and 16 KB ELF alignment.
- Added missing ggml CPU copyright notices and clarified source-only SYCL exceptions.
- Removed a tracked Python cache, extended credential/cache ignores, pinned the
  Gradle distribution checksum and GitHub Actions revisions, and added a checksummed
  Gitleaks history scan plus the missing common-native host suite to CI.
- Updated model installation/extension, privacy, security reporting, changelog,
  signing instructions and historical backlog status.

## Verification record

- Gitleaks 8.30.1: zero findings in all 26 commits and in the final source-only
  archive. The initial 25-commit history and staged snapshot also scanned clean.
  No tracked signing keys, local SDK configuration, model weights or APKs; no
  tracked ignored files. Largest historical blob: 20,558,608 bytes (native runtime),
  below GitHub's 100 MiB single-file limit. Git author metadata is retained.
- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin`: PASS.
  App: 173 tests per flavor/build type, with one intentional opposite-flavor skip
  per variant; common-jni: 67 tests each for debug/release. Zero failures/errors.
- Common host suite: all five utility programs PASS (including 12 PCM range checks).
  Speech: 50 checks PASS. Vosk loader: local visibility, calls, absent library and
  absent symbol PASS. Python model packaging: 5 tests PASS.
- Native Android debug build, both QA APKs and both unsigned release APKs: PASS.
  `verify-release.py` passes for QA and release, including exact packaged vendor
  hashes, adapter source pins, license manifests, permission differences and alignment.
- Source-only build from a Git tree archive: PASS, all 271 initial tasks executed,
  with fresh project build directories and explicit ANDROID_HOME. Shared global
  Gradle dependency caches were reused. No parent project or local.properties was
  copied. Follow-up regression tests also passed in this source-only directory.
- Workflow YAML parses and referenced Actions revisions/checksums were resolved
  against upstream repositories. Hosted GitHub Actions execution awaits publication.
- Local Markdown links and `git diff --check`: PASS.
- Samsung SM-S908E / Android 16: 0.8.1 → 0.8.2 upgrade installed successfully;
  saved Nemotron/Russian → Arabic setup and direct Home → Face to face navigation
  survived. Existing provider keys were not replaced or removed. A synthetic fixture
  in a previously empty speech-key slot saved with Android Keystore protection and
  decrypted after app replacement/restart; the fixture was then removed. Legacy
  mixed-mode migration is covered by host tests, not an owner-credential experiment.

## QA artifacts

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| hearth-v20-cloud.apk | 33,330,702 | `977a44b231f393dcfda619567f9cb9c790cc7df206744fcf832211b9fb888905` |
| hearth-v20-offline.apk | 24,977,863 | `c8c1f1d44c3fc051f96cc856718820d2eee9ea53f8f8303d978300ef7bb76762` |

Both final QA APK signatures and ZIP alignment were checked with Android SDK tools.
These are test APKs; release variants remain unsigned.

## Remaining release decisions and limits

- No Git remote is configured and nothing has been pushed or published.
- QA APKs are debug-signed. Both production flavors build unsigned and require a
  maintainer-controlled signing key; see [release instructions](releasing.md).
- Google/Azure/DeepL/LibreTranslate successful paid-account requests are not claimed
  from mock tests or language discovery. Existing device/provider evidence remains
  in the versioned validation notes.
- The broad auditory TalkBack pass and wider handset/thermal coverage remain open.
- Secret scanning finds recognized patterns; it cannot prove that every possible
  private value is absent. Native build pins/integrity are not a complete upstream
  vulnerability or legal review. Model weights retain separate publisher terms.
