# 0.5.4 / versionCode 15 — direct model-family chips

Models → Speech now exposes Nemotron, Qwen3-ASR, Moonshine, Whisper and Vosk as
wrapping Material FilterChips. The initial selection follows the active local
engine; an active-engine label stays separate from the family being browsed.
Whisper/Vosk import no longer depends on choosing that engine on Home first.

Shared translation setup exposes ML Kit (play only), HY-MT1.5, Hy-MT2 and
TranslateGemma as equivalent chips. Models → Translation also exposes Marian's
legacy English → Arabic import as a chip. Only the browsed family's setup appears.
Opening a family does not switch the active translator. Returning to the active
family restores its configured quantization rather than always choosing Q4.

Existing Material chip selection semantics and minimum touch targets are retained;
rows wrap with 8.dp horizontal and 4.dp vertical gaps. No engine/JNI, language
capability, integrity-check or download behavior changed. No new tests were added
for this UI-only change; required regression and release checks still apply.

Validation: `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa` PASS in 2m7s (268 tasks; 97 executed,
171 up-to-date). App: 129 tests per six variants; common JNI: 63 per two variants;
zero failures/errors/skips. Final APKs pass `scripts/verify-release.py` including
native hashes/dependencies, 16KB alignment and permissions. Play 32,840,586 bytes;
foss 24,475,275 bytes.

Authorized Samsung SM-S908E / Android 16 check: installed 0.5.4; Speech opened
with Nemotron checked. Tapped Whisper directly, confirmed only Whisper setup,
and opened/cancelled its system file picker without changing Home's active engine.
Translation opened with ML Kit checked. HY-MT1.5 showed only its Q4/Q6/Q8 choices
and import controls while ML Kit remained active. Marian showed its legacy folder
import instead of the GGUF controls. Both pages were visually inspected and expose
checked chip states in the accessibility hierarchy. No weights, keys, languages
or active engines were changed; capture stayed stopped.
