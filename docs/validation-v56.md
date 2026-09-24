# 0.22.14 QA validation — local benchmark clarity and four-language sweep

The benchmark now feeds local speech backends in the overlay capture path's
50 ms frames and measures inference separately from between-clip reset. The
saved result records timing protocol 2; protocol 1 results remain visible but
cannot compete for the same Best speed or Best quality badges. The speech
table leads with processed-audio speed (`2.0×` means two seconds of this test
audio per second of replay), translation shows time per sentence, and the
reference number names errors or similarity. Neither is a live-caption delay
or a human translation-quality judgment.

Each installed model now offers **Test supported languages** for all bundled
routes that its capability list explicitly advertises. The run saves separate
results for Arabic, English, Russian and Chinese speech, or for its supported
built-in translation directions. Tap a saved route chip to inspect its result.
An unknown or auto-only language capability is not silently treated as support
for all four.
The optional **Long clip · 7 clips** speech set adds the existing distinct
publisher recording for each language (about 14–17 seconds), while the
four-language sweep stays on the quicker six-clip set.

The checked-in FLEURS quick set was audited against publisher test metadata at
the pinned revision. `python3 scripts/benchmark/verify_fleurs_pack.py` passes:
28 bundled WAV and PCM hashes, all publisher speech transcripts, 36 aligned
translation references, and complete quick-set coverage. The four local
`test.tsv` hashes also matched files fetched directly from the pinned
publisher revision. Arabic audio is the Egyptian locale `ar_eg`, English is
`en_us`, Russian is `ru_ru`, and Chinese is `cmn_hans_cn`. Six read sentences
per language are a quick comparison, not a dialect or long-session test.

Host checks passed on 24 September 2026: `./gradlew test
:app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa
:app:assembleFossQa --offline` (BUILD SUCCESSFUL),
`python3 scripts/benchmark/verify_fleurs_pack.py`, and
`python3 scripts/verify-release.py` (Play QA 44,835,550 bytes; FOSS QA
36,887,741 bytes; packaged native alignment and flavor permissions PASS).

Owner phone checks:

1. Install Play QA or FOSS QA, open Compare local models, choose Nemotron, and
   tap **Test supported languages**. Confirm four progress steps and saved
   AR/EN/RU/ZH chips; each chip opens that language's scores and transcripts.
2. Force Russian in the overlay, then compare its perceived responsiveness with
   the Russian benchmark's **Audio speed**. Above `1×` means the model kept up
   with this short corpus in accelerated replay; it does not promise that the
   on-screen translation has the same delay.
3. Compare two installed speech models on the same language. Confirm **Best
   speed** and **Best quality** badges use only matching device, input, and
   timing protocol. An older result should ask for a rerun.
4. Test a model with only English support. Its per-model sweep should offer
   one supported language, not four. Try a translation model to see only its
   published bundled directions.
5. On one language, switch from **Quick · 6 clips** to **Long clip · 7 clips**.
   Confirm the extra longer recording appears in the saved sample details
   and that this result does not rank against a six-clip run.

No device was driven by the coding agent.
