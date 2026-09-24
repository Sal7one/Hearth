# 0.22.11 QA validation — benchmark results table

The benchmark page now shows the latest comparable input/device cohort near the
top. The table contains model, warm inference time, model load/verification time,
and a reference score when one exists. It sorts by warm time, load time,
reference score, or recency. Shorter warm bars are faster; colored rows identify
the measured fastest, balanced, and best-reference models. Previous runs remain
in Saved results, now collapsed to their numeric summary until expanded. A
failed run still shows its actual error in history and cannot win the table.

The time is accelerated replay compute, not microphone-to-caption latency.
Speech WER/CER are error rates (lower is better); translation chrF is a reference
score (higher is better). Runs without a reference display a dash and sort last
by reference score. Different input hashes, devices, and app builds are never
combined into a leaderboard.

Build checks passed on 24 September 2026:

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa --offline --quiet`
- `python3 scripts/verify-release.py` — Play QA 44,768,734 bytes; FOSS QA
  36,844,197 bytes; 16 KiB alignment and flavor permissions passed.
- The table-order unit test covers all four sort modes, missing reference
  scores, and translation chrF ranking.

The owner checks the layout and interactions on a phone; no device was driven
by the coding agent.

Owner device checks:

1. Open Benchmark with no results: the empty table should be concise.
2. Run two installed models on the same built-in speech set; check time order,
   load order, and that the bars/fastest highlight follow the measured values.
3. Sort by reference score and recency. A missing reference must not look like
   an accuracy win. Try a translation pair and confirm chrF sorts high first.
4. Expand an old run to view transcript, sample times, runtime details, and any
   engine error. Export still includes full history.
5. Check English, Arabic RTL, Chinese, dark mode, and large system text.
