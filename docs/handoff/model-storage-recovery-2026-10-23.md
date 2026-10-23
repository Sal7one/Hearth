# Android model storage recovery and automatic manga pages

Android candidate: 0.29.0 / code 97, preserving `com.sal7one.transiber.qa` and the
existing debug/test signer. RC tag `v0.29.0-rc.2`. Owner device-tests;
no adb, GitHub Actions dispatch, or `gh run` commands.

## Storage regression

`ModelPathSnapshots.checkedRoot` compared canonical containment successfully,
then incorrectly required the installer path to start with the lexical
`Context.filesDir` path. Android may expose `/data/user/0/...` but canonicalize
the same private storage to `/data/data/...`. Registry imports publish canonical
paths, so a successful model install could throw during source-binding recording.
Startup bootstrap saved that same error, producing many false missing-model
warnings. The shared pinned file hashes were not the cause.

The checker now accepts the exact lexical private root and its canonical root,
requires canonical containment, rejects traversal and rejects symbolic links
inside either representation. It does not allow arbitrary external aliases.
Incomplete/error snapshots are recaptured by the existing launch bootstrap after
this correction. Existing model copies need not be removed or downloaded again.

Regression proof: `systemAliasForThePrivateRootAcceptsCanonicalInstallerPaths`
failed against the old checker, then passed with the fix, including the nested
symlink rejection. Existing missing, wrong-size, unreadable, tree and traversal
tests remain. Metadata timestamps alone no longer count as corrupt/missing files
or offer a forced re-import. Pinned native initialization still verifies content.

## Benchmark discovery

The runner used strict `LocalSpeechModels.list()` and read every registered
Whisper header in one `map`. One malformed package or missing registry file could
throw and leave the whole candidate set empty. It now uses the existing isolated
package inspection and per-model `BenchmarkDiscovery`. Real errors are retained
and shown in a review dialog; healthy candidates remain selectable. The catalogue
mapping also uses isolated inspection. Cancellation still propagates. Native
load/inference failures continue to be recorded per result row.
The global Review action opens file diagnostics directly, and OCR repair entries
lead to the Camera model browser rather than incorrectly choosing Speech.

Owner acceptance: update over RC1 without uninstalling; open Models and Model Lab,
check existing downloads appear; refresh once; run one known installed CPU model,
then a batch. Check a deliberately damaged unrelated install does not hide healthy
candidates. Repair only entries with actual per-file errors. CPU/GPU inference,
background-service behavior and quality remain phone checks.

## Manga work in the same candidate

See [automatic page adapter details](../manga-ocr-adapters.md) and
[full Koharu integration boundary/status](../koharu-integration.md).
The compact detector + shared page processing works on the Mac with real weights;
Android device inference is not claimed. Manual reader choices remain intact.
The full Koharu backend is explicitly pending rather than advertised as included.

Validation evidence is staged under ignored `build/model-recovery/evidence`;
native/model inference research stays under ignored `build/ocr-koharu`.

## Local verification

- Required root `test` and Play/FOSS QA Kotlin compilation passed; 747 app tests
  passed in each of Play/FOSS Debug, Release and QA configurations.
- All six required desktop suites passed. Reading-controller tests cover errors,
  crop-to-page coordinates, shared translation settings, no unintended voice,
  and rapid replacement waiting for the complete native cancellation chain.
- Common/speech/OCR C++ host sanitizer suites passed, plus actual detector
  inference under ASan/UBSan with blank-page and cancellation checks.
- All-ABI common JNI `externalNativeBuildDebug` passed; all ten Play/FOSS QA APKs
  passed `verify-release.py`, including signatures, current identity, ABI/ELF,
  page alignment, permissions and pinned runtime contents. Both ARM64 variants
  were compared with RC1: same package/signer, code 96 → 97.
- Desktop native OCR helper built, with eight expected JNI exports. Real
  pinned-weight detector + Manga OCR inference passed on Mac ARM64. The packaged
  launcher self-check also loaded both OCR adapters and rejected retired handles.
- Desktop page renders were inspected headlessly, including Arabic fitting and
  original-image mode. The staged Mac app is at
  `desktopApp/build/manga-reading/binaries/main/app/Hearth.app`; it was not launched
  over the owner's running app. Windows/native phone execution remains untested.
- Python build/package host tests passed (44 Android scripts, 22 desktop scripts).

The initial failing alias regression is deliberately included in evidence as the
before-fix reproduction, separately named from final passing checks.
