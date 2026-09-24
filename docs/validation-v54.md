# 0.22.12 QA validation — benchmark model download progress

Benchmark preset cards now read the shared downloader's saved records while the
page is visible. A card shows its current transfer phase, transferred/expected
MiB and percentage, and a progress bar. Marian and pivot routes aggregate all
of their required files. On completion the page refreshes installed candidates
and offers the test action. Paused and failed jobs show Resume/Retry and preserve
the actual downloader error; an active job offers View downloads rather than a
second Download button.

The downloader also reuses a matching model's existing Waiting, Downloading,
Verifying, Installing, Paused, Failed, Complete or Installed record. A changed
pinned URL can still create a new job; ordinary direct-file downloads retain
their repeat-download behavior. Model originals can be explicitly restarted or
removed from Downloads.

Build checks passed on 24 September 2026:

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa --offline --quiet`
- `python3 scripts/verify-release.py` — Play QA 44,782,278 bytes; FOSS QA
  36,851,993 bytes; both 16 KiB aligned with expected flavor permissions.
- JVM tests cover reuse of active/completed/failed model records, repeatable
  direct-file downloads, Marian part aggregation and visible failure details.

No model was downloaded or phone driven by the coding agent. Owner checks:

1. Tap Download model in Benchmark. Stay on the page: phase, MiB, percentage
   and bar should advance, with View downloads instead of Download.
2. Tap rapidly or revisit Benchmark; confirm there is only one record for the
   same model in Downloads. Repeat after the download finishes.
3. Pause in Downloads, return to Benchmark, then Resume. Check the same record
   continues. For a real failure, Retry should retain the original error until
   a new attempt starts.
4. Test a Marian pair or pivot route: the card should count all required files,
   then switch to Select for test after installation.
5. Check FOSS: no downloader controls or network permission. Check English,
   Arabic RTL and Chinese labels.
