# Local model setup

The app includes native runtimes, not multi-gigabyte model weights. Import the same
verified Hearth speech ZIPs you already have, or prepare a ZIP from the publisher's
files with the included `scripts/speech/make-package.py`. Qwen and Nemotron choices
are under **Models** and **Captions → Caption engine**, not the cloud model list.

## Qwen3-ASR 0.6B

Publisher package (about 1 GB after packaging):
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2

Extract it into a folder containing `conv_frontend.onnx`, `encoder.int8.onnx`,
`decoder.int8.onnx` and `tokenizer/{vocab.json,merges.txt,tokenizer_config.json}`.
Keep the publisher's model card and license in that folder. Then:

```sh
python3 scripts/speech/make-package.py /path/to/model --profile qwen3-asr-0.6b \
  --role frontend=conv_frontend.onnx --role encoder=encoder.int8.onnx \
  --role decoder=decoder.int8.onnx --role tokenizer=tokenizer
cd /path/to/model
zip -0 -r ../qwen3-asr.zip .
```

Import the ZIP in Models → Qwen → Import model ZIP. The `hearth-speech.json` file
must be at the ZIP root. Qwen uses automatic language detection and emits captions
in utterance windows. A compatible Qwen 1.7B package is accepted by the schema, but
no 1.7B phone performance claim is made.

## Nemotron 3.5 ASR 0.6B

Pinned publisher GGUF:
https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/resolve/1c8deaecc64b91f034d73e08dd8b64625eb3395d/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf

Place it in a directory with its publisher model card and OpenMDW-1.1 license.
The profile is `nemotron-3.5-asr-0.6b`; the role is `model=the-downloaded-filename.gguf`:

```sh
python3 scripts/speech/make-package.py /path/to/model --profile nemotron-3.5-asr-0.6b \
  --role model=nemotron-3.5-asr-streaming-0.6b.q8_0.gguf
cd /path/to/model
zip -0 -r ../nemotron.zip .
```

Import in Models → Nemotron. It supports streaming partial and final recognition.
Both recognizers produce **original-language CC**. They do not provide Arabic/English
translation; use cloud translation or an explicitly supported local translation pair.

## Other models and downloads

- Whisper: import a whisper.cpp GGML `.bin` model using **Import Whisper file**.
  Sources and sizes: https://huggingface.co/ggerganov/whisper.cpp
- Vosk: extract a model ZIP on your computer/device, then **Import Vosk / translation
  folder**. Publisher catalogue: https://alphacephei.com/vosk/models
- Marian: import a compatible OPUS-MT folder with `source.spm`, `tokenizer.json`,
  encoder ONNX and merged-decoder ONNX. The translation route must match its language pair.
- Download a prepared speech ZIP or another direct HTTPS file through **Downloads**.
  Export the completed download, then import it in Models. Raw `.tar.bz2` or GGUF
  downloads are not ready-to-import speech ZIPs; package them as shown above.

Package manifests enforce file sizes, SHA-256, role requirements and path containment.
A manifest generated from your own download is an integrity record, not proof that
the publisher is trustworthy. Obtain models from their publishers. Runtime backlog
and compute/audio metrics appear in the overlay; a ratio below 1× is throughput,
not an end-to-end latency measurement.
