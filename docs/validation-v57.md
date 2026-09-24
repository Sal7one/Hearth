# 0.22.15 QA validation — Saudi speech benchmark

The Arabic speech benchmark now starts with the user's own Saudi recordings.
The in-app recorder captures mono PCM16 at 16 kHz for up to 15 seconds; a
PCM16 WAV can also be imported. Up to six clips and their corrected transcripts
are stored in the app-private `benchmark-saudi` directory. The pack verifies
the stored WAV hash before comparison, and the existing suite reader verifies
both WAV and decoded PCM hashes before native inference. Model comparisons,
the speech + translation preset test, and the per-model language sweep use
this pack for Arabic speech. If it is empty, Arabic speech is skipped in the
sweep rather than silently replaced by the Egyptian FLEURS recordings.

The FLEURS Egyptian clips remain an explicit **Egyptian reference** option.
FLEURS Arabic translation text is a separate written-language reference, not
a Saudi dialect translation quality claim. No SADA audio is bundled: the
[original SADA card](https://huggingface.co/datasets/khaledalganem/sada2022)
lists CC BY-NC-SA 4.0, which does not permit unrestricted commercial reuse.

Host checks: `SaudiBenchmarkPackTest` covers saving, reloading, exact reference
text, stable hashes, silence checks, clearing, and detecting changed audio.
It also caught and fixed `BenchmarkSuite.selected()` incorrectly excluding
personal clips when both publisher and warmup IDs were absent. Device
microphone capture and subjective Saudi dialect accuracy require the owner's
phone check; host tests do not establish either.

Final gates on 24 September 2026: `./gradlew test
:app:compilePlayQaKotlin :app:compileFossQaKotlin assemblePlayQa assembleFossQa`
passed (346 app unit tests per variant, six variants, zero failures).
`python3 scripts/verify-release.py` passed for both QA APKs, including
16 KiB native alignment and the FOSS no-network-permission check.

Device check: Open **Compare local models → Speech → Arabic → My Saudi speech**.
Record a prompt in your own dialect, stop, correct the transcript, save, and
compare Nemotron and Qwen on the saved clip. Confirm the same clip and silence
checks appear in the saved results, and that **Egyptian reference** is a
separate opt-in choice. Then use **Test supported languages** on one model and
confirm Arabic uses the Saudi clip.
