# Mac host smoke results · 2026-09-23

These are reproducible host checks for the app's pinned short FLEURS suite,
not model rankings or Android performance claims. Hardware: Apple M2 Pro,
macOS 26.3.1. The Mac has Command Line Tools but not Xcode's Metal shader
compiler, so both runtimes used CPU. Speech used 8 threads; translation used 2.
The six Russian source clips and references are from the suite revision
`70bb2e84b976b7e960aa89f1c648e09c59f894dd` (CC BY 4.0).

| Artifact | Host result | Quality / failure signal |
| --- | --- | --- |
| Whisper tiny Q5_1, 32.2 MB; SHA-256 `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7` | 3.54 s median for six speech clips plus 1 s and 2.5 s silence; model load + file verification 74 ms; peak host process RSS 135 MB | Russian WER 45.0%, CER 13.4%; both silence samples produced `[музыка]` (2/2 false positives). Fast replay does not make this a good caption result. |
| Hy-MT2 Q4_K_M, 1,133,080,448 bytes; SHA-256 `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699` | 8.80 s median for six Russian → Arabic translations; model load + file verification 1.71 s; peak host process RSS 2.24 GB | chrF++ 30.6, WER 101.4%, CER 73.8% against the publisher references. This sample is too weak to promote as a reliable live translator. |
| Hy-MT2 Q2_K, 777,483,008 bytes; SHA-256 `dced2d16784aaf339cab86a469ec3a6963111b200d863ed345a2380842b88ce7` | The first Russian → Arabic sample failed after 16.8 s; the host runner stopped the remaining samples immediately. | The adapter hit its 384-token output cap and withheld incomplete text. No accuracy score is reported. |

The translation runs used llama.cpp revision
`64e9bceb2c3a856efed96feda784a50947049feb`, CPU, 2 threads, context 2048,
384 output tokens and the production 20-second decode deadline. Whisper used
vendored whisper.cpp revision `7374f9728b506c5a48e12db421a5be3791cf4826`,
the pinned tiny Q5_1 artifact, forced Russian, greedy batch decoding and the
app's gain normalization / batch no-speech threshold. Each script emits the
full per-sample output JSON under ignored `build/benchmark-results/`; this
document records the summary and exact identities, not model weights.

These results cover one language and six short sentences. FLEURS does not test
Saudi dialects, streams with music/overlap, terminology, or a human judgment of
translation meaning. The numbers therefore identify obvious failures and give
a repeatable baseline; they do not establish a multilingual winner. The
Android quick benchmark uses the production phone adapters and the same samples,
but this host run did not drive a physical Android device. Compare phone runs
only with their device, OS, model hash and backend recorded in the result.
