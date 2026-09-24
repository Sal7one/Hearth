# Phone benchmark smoke check · 2026-09-23

Physical device: Samsung SM-S908E, Android 16 / API 36. App: Hearth 0.22.3
Play QA. These are short foreground runs of the production adapters, using
the built-in six-sentence FLEURS quick set. Speech also included two digital
silence clips. The English audio was peak-normalized by the documented common
policy before inference; an initial run without that policy incorrectly scored
all six low-level recordings as empty, so it is excluded below.
These runs used the older benchmark timing protocol, which included a model
reset between clips and 20 ms feed frames. Newer 50 ms capture-frame runs
exclude reset time and must be compared in a separate cohort. The older
Nemotron warm pass below already processed the Russian corpus in about half
its audio duration; a 24-second total over several clips was not a 24-second
live caption delay.

| Installed option | Direction | Accuracy signals | Load + verify | First full pass | Warm full-pass median |
| --- | --- | --- | ---: | ---: | ---: |
| Moonshine Tiny English INT8 | English captions | WER 18.52%; CER 8.76%; 0/2 silence false positives | 0.52 s | 1.42 s | 1.33 s |
| Whisper Tiny multilingual Q5_1 | English captions | WER 10.19%; CER 2.02%; 0/2 silence false positives | 0.26 s | 14.50 s | 16.43 s |
| Qwen3-ASR 0.6B INT8 | Russian captions, forced language | WER 17.50%; CER 5.27%; 0/2 silence false positives | 5.62 s | 15.72 s | 17.62 s |
| Nemotron 3.5 ASR 0.6B Q8_0 | Russian captions, forced language | WER 12.50%; CER 4.62%; 0/2 silence false positives | 1.47 s | 24.58 s | 24.10 s |
| ML Kit installed packs | English → Arabic text | WER 89.04%; CER 54.13%; chrF++ 38.21 | 0.06 s | 0.75 s | 0.39 s |
| Hy-MT2 1.8B Q4_K_M | English → Arabic text | WER 76.71%; CER 54.99%; chrF++ 40.35 | 2.05 s | 44.44 s | 53.01 s |

The later [MiLMMT Q4 phone check](translation-models-jun-sep-2026.md#samsung-s22-ultra-q4-phone-check)
used a corrected Android native library and the same English→Arabic quick set:
22.72 s warm with chrF++ 40.45%. It also measured Russian→Arabic at 25.75 s
warm. MiLMMT is an experimental option, not a replacement for the small direct
Marian pair.

The translation scores compare each output with one publisher reference; a
different valid phrasing can score poorly. They require bilingual review before
ranking quality. The Q4 warm pass is about 8.8 seconds per sentence here,
making this particular phone/runtime combination unsuitable as a fast live
translation default. ML Kit is much faster in this sample, but that alone does
not establish adequate meaning preservation. The Q4 process remained stable;
during the run Android reported approximately 1.5 GiB PSS and 1.6 GiB RSS.
Whisper Tiny Q5_1 reduced error on this English set relative to Moonshine Tiny,
but its 16.43-second warm pass was more than 12 times slower; the two use
different batch and utterance-windowed routes, so this is a user-visible
speed/quality comparison rather than a claim of equivalent streaming behavior.
For forced Russian, Nemotron had lower reference error while Qwen completed the
warm quick set faster. Both stayed below a 1.0 replay real-time factor (0.51
and 0.37 respectively), but these are isolated segments and do not measure a
live streaming session with audio acquisition, finality, translation or UI delay.

Models → Speech and Models → Translation opened on this device; the family and
quantization choices rendered and the active Hy-MT2 Q4 choice remained visible.
The pinned 30 MiB Whisper Tiny Q5_1 artifact downloaded on unmetered Wi-Fi,
passed SHA-256 verification, installed, and could be selected. Its original
remains at `Downloads/Hearth/models/ggml-tiny-q5_1.bin`. The previous Cloud
BYOK speech choice was restored after the check. This smoke check does not
verify other translation directions, thermal endurance, or
concurrent ASR and translation. The phone APK used for these runs preceded the
final documentation edit. Download-state behavior was exercised only through a
complete transfer, not a paused or interrupted network connection.
