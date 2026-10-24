# Jisr compact Arabic dialect translation

Jisr is available through the shared translator interface on ARM64 CPU. Speech,
typed text and OCR can use it without changing their independent STT/TTS choices.
TVs cannot install or run it. FOSS supports offline import.

| Direction | INT8 required files | FP32 required files |
| --- | ---: | ---: |
| English → Arabic dialects | 54,619,924 bytes | 198,431,639 bytes |
| Arabic dialects → English | 54,611,494 bytes | 198,405,789 bytes |

These totals include the model, tokenizer, vocabulary, config, LICENSE and NOTICE.
ZIP size, staging copies and runtime memory are separate. Precisions are variants
of the same two directional checkpoints, not additional trained families.

The forward default is Modern Standard Arabic. Details exposes the publisher's
14 exact target tags, including Saudi `ars`, stored in DataStore and portable
setup JSON. Reverse input always uses `>>eng<<`; a turn freezes the dialect
before inference. Installing a package does not activate translation.

The production tokenizer is Hugging Face tokenizers 0.22.2, built from locked Rust
sources and accessed through a narrow owned-buffer C ABI. It implements actual
ByteLevel BPE and added special tokens. Serialized 256-token truncation is disabled;
the native session rejects inputs over 1022 tokens. Greedy generation uses a
20-second deadline, at most 255 generated tokens and no-repeat 3-grams. Reaching a
limit returns an error, never a partial success.

The original Marian loader would erase Jisr's trained, nonzero ID0 decoder-start
embedding and remove a vocabulary row. The converter uses CT2's BartLoader with
the correct Marian normalization flags, retains all token IDs and does not append
source EOS. This is why a generic Marian/OPUS conversion cannot substitute for
the reviewed export.

[Real source/native checks](../research/jisr-native-parity-2026-10-20.json) matched
all 21 FP32 outputs to Transformers with the same greedy policy and all 20 tokenizer
vectors, including Arabic diacritics, emoji, whitespace and input beyond 256 tokens.
INT8 matched 15/21 FP32 outputs; one Palestinian sample repeated a word. These are
host parity examples, not corpus quality scores or S25 benchmarks. Publisher
beam 4 scores do not apply to Hearth's greedy runtime.

The original safetensors packages are pinned in the
[source card](../handoff/models/translation.json). They cannot be imported directly
as runtime models. Build each conversion using its verified source folder:

```sh
python3 scripts/translation/jisr/export.py --ct2-source research/CTranslate2 \
  --source /path/to/pinned/en-ar --output /path/to/new/jisr-en-ar-int8 \
  --direction en-ar --precision int8
```

Use `ar-en` for the reverse package and `float32` for the fidelity variant. The
script refuses an existing output folder, verifies every original source hash,
loads only safetensors/built-in Transformers code, and creates the root manifest
`hearth-translation.json`. The six installed file sizes/hashes are recorded per
conversion in the card. To repackage an already converted folder without Python
model dependencies:

```sh
python3 scripts/translation/ct2/package.py ct2-jisr-50m-en-ar-int8 \
  /path/to/converted/files /path/to/new-package.zip
```

All four converted packages now support **Download** on Android ARM64 and macOS
Apple silicon, using the original public [v0.27.3 model assets](https://github.com/Sal7one/Hearth/releases/tag/v0.27.3).
No upstream training URL is substituted for a converted runtime file. Each transfer
checks the pinned ZIP size and SHA-256, then its root manifest and all six runtime
components before atomic installation. Desktop selection retains every component
pin for verification at inference time. The queue reserves space for both the ZIP
and expanded runtime during installation.

**Import** remains available on both platforms, including Android FOSS. The ZIPs
contain a flat root manifest; import checks the exact file inventory, SHA-256,
archive bounds and atomic replacement. Runtime models and original weights are
excluded from Git and the SDK. License: Apache-2.0.
