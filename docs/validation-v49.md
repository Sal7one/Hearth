# 0.22.7 QA validation

The local benchmark now offers speed, balanced and quality **trial** pairs for
the selected spoken and translation languages. Suggestions come only from the
pinned installable catalog. Play can queue the suggested publisher or Marian
artifacts through the existing verified downloader; FOSS shows local model
browsing without network actions. An installed pair can run the built-in speech
and translation sets in one tap. The saved-result summary compares successful
models only on the same phone/app build, language direction and exact input.
It excludes empty speech and silence false positives; reference-based labels
need reference scores, and a middle choice needs three scored models.

Validation: `./gradlew test :app:compilePlayQaKotlin
:app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa --offline`
passed, including the new preset and comparison tests. `git diff --check` and
`python3 scripts/verify-release.py` passed; the verifier checked both flavors'
permissions, native library inventory and 16 KiB alignment. There are no native
changes. No phone or paid-provider test was performed.

| QA artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Play | 44,727,850 | `4b5c63c3db6bac1881d5f261a1cf89da97d80751fc126828bf25130eb0625ed5` |
| FOSS | 36,799,561 | `9ce8e214a5a9d20430b2e5952e8a29fb25d5fa677bfc5384b8ed017fc63cd7c0` |

Owner device check: on the S25, open Settings → Local benchmark. For Russian
→ Arabic choose each trial chip; download missing speech/translation models in
Play and return. Once installed, run **Test speech + translation** for each
preset. Confirm original speech and translated outputs are saved separately,
the fastest/middle/reference labels appear only after comparable runs, and
Stop leaves only already-completed results. Inspect the actual Arabic against
the reference; the six-sentence score is not a quality verdict for streams.
