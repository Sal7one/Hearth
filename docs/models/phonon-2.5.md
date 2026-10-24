# Phonon 2.5 multilingual speech recognition

Hearth runs the multilingual [FermionResearch/Phonon-2.5 checkpoint](https://huggingface.co/FermionResearch/Phonon-2.5)
through its existing Phonon / sherpa-onnx TDT speech adapter. Phonon 2 remains an
independent English-only selection. Installing or choosing either checkpoint
does not replace the user's translator or voice.

The supported speech languages are English, Spanish, French, German, Italian,
Portuguese, Dutch, Polish, Russian, Ukrainian, Romanian, Czech, Slovak,
Bulgarian, Croatian, Swedish, Danish, Finnish, Estonian, Latvian, Lithuanian,
Hungarian, Slovenian, Maltese and Greek. Arabic and Chinese recognition are
not supported by this checkpoint. Arabic translation output can still come
from an independently selected translator with the appropriate source pair.

The decoder recognizes these languages automatically. A selected spoken
language labels the resulting text for translation and benchmarking; it does
not force decoding. This adapter does not return language-identification
metadata, so **Auto** supports original captions with an unknown source.
Choose the actual spoken language when using a downstream text translator.
English-only Phonon 2 still reports English when its source is set to Auto.

Hearth uses CPU inference for this ONNX package on Android and macOS Apple
silicon. The publisher's optional MLX engine is a separate implementation;
Hearth does not label this adapter as Metal or CUDA accelerated. Android TV
cannot install or run local speech models.

## Source and conversion

The source revision is `96dad4f3b5b784b1aa595213ea7481bf8a7addcd`.
The original `phonon-2.5.bps.tar.zst` is 162,587,031 bytes, SHA-256
`1faa04053a472cabab3bc31d07f30c23f22906f71044ca10eb1f0de36d1f19a9`.
Its decoded Fermion container is 177,593,644 bytes, SHA-256
`17cb62d08e751a41819eb779db089aa7ac4eb2bab433ba5c2991d8cbd3cfefb9`.
The original packed download size is **not** the size of Hearth's ONNX package.

Hearth's [download package](https://github.com/Sal7one/Hearth/releases/download/v0.30.0-models.1/phonon-2.5-hearth-int8.tar.bz2)
is **339,205,640 bytes** (about 339 MB / 324 MiB); its six installed model and
attribution files total **629,548,381 bytes**. The separate
[offline-import ZIP](https://github.com/Sal7one/Hearth/releases/download/v0.30.0-models.1/phonon-2.5-hearth-int8.zip)
is 363,898,653 bytes. Model weights are downloaded separately from the app.

`scripts/speech/phonon-2-export.py --profile phonon-2.5` verifies these inputs,
decodes the five-value/int6/int8/fp16 records, loads all 723 tensors with strict
key checking, and exports the encoder, decoder and joiner with sherpa-onnx
TDT metadata. Base-model configuration and tokenizer files are pinned to NVIDIA
revision `541d1f99c6b0c3cd0b11a95167540bb8edefd82b`. The host-only Python
environment is described by `scripts/speech/requirements-phonon-export.txt`.
No Python dependency or source container runs on the phone.

The runtime roles are `model=tokens.txt`, `encoder=encoder.int8.onnx`,
`decoder=decoder.int8.onnx`, and `frontend=joiner.int8.onnx`. The joiner uses
the existing frontend ABI slot; no native ABI change is required. The flat
offline-import ZIP has its manifest at the archive root. Installers retain
archive limits, path checks, exact component pins and atomic replacement.

Weights are **CC-BY-4.0**. Packages retain the publisher's attribution/NOTICE
and license. Exporter and package code are Apache-2.0. No weights are committed
to this repository or included in the SDK.

## Caption delivery

Recognition produces final text after bounded utterance windows. This integration
does not provide token-by-token partial hypotheses. **Standard**, **Responsive**
and **Eager** timing choices are available directly on Android Home and under
the speech overlay's **CC & translation** options. They use 4,000/600,
2,000/400 and 1,200/320 milliseconds respectively for maximum utterance / ending
silence. Custom timing remains under Advanced.

Timing is persisted per speech profile in DataStore. Applying it to the active
model restarts speech recognition and clears the current caption state. Shorter
windows reduce waiting but can reduce recognition context; phone measurements
determine which setting works best for a particular speaker and device.

The publisher's FLEURS/English leaderboard scores describe its own evaluation.
They are not measured quality scores for Hearth's additional ONNX INT8 export.
Native smoke transcripts establish that the packaged checkpoint runs; they do
not establish multilingual corpus accuracy or S25 performance.

Actual production C ABI tests on this Mac decoded a 7.14-second English clip in
0.194 seconds (host runtime) / 0.217 seconds (desktop runtime), and a 7.62-second
Russian clip in 0.178 / 0.163 seconds. Loading was measured separately. The raw
English clip was below the speech gate, so that no-decode result was retained and
the diagnostic used an explicitly peak-normalized copy; Russian was unchanged.
Auto returned text with an unknown source, while explicit English/Russian source
declarations were preserved. These are short host smoke examples, not Android
measurements. Android's benchmark reader already normalizes quiet recordings
before comparing any model.
