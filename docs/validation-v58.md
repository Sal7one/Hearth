# 0.22.16 QA validation — screen reading speed

Positioned screen reading groups adjacent OCR lines before sending them to the
selected translator. A four-line block now takes one translation request instead
of four, while distant bubbles keep separate geometry. The transparent overlay
retains earlier labels as new results arrive. Continuous scroll checks sample
the 64×128 grid directly from the RGBA capture plane instead of allocating and
scaling a full-screen Bitmap four times a second.

Host checks on 24 September 2026: `OcrPageTranslationTest` covers horizontal
and Japanese vertical grouping, separated bubbles and the translation-call
count. `ReadingMotionTest` covers padded RGBA rows and invalid strides.
`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa` passed (350 app unit tests per
variant, six variants, zero failures). `python3 scripts/verify-release.py`
passed for both QA APKs: Play 44,862,222 bytes and FOSS 36,918,209 bytes,
including native 16 KiB alignment and the FOSS no-network-permission check.

Phone check for the owner: choose **Screen & manga → Japanese → Meiki**, open a
page with several lines in two separated bubbles, then scroll to another page.
Confirm translations appear over the correct blocks without flashing all
earlier labels and that scrolling clears old placements. Compare the time from
settling to the last translated bubble against 0.22.15 using the same page and
translator. Separately choose **Manga bubble**, draw one crop, and note its OCR
time; this patch does not change its autoregressive ONNX decoder. No phone
latency result is claimed by these host checks.
