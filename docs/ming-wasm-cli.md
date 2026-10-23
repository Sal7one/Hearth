# ming-wasm native CLI proof

Status: **desktop-only proof, not the shipped desktop engine.** The shipped local
engine is the shared JNI in [ming-3.0.md](ming-3.0.md); the CLI remains a fallback
because upstream publishes no release artifact.

## Pins

| Piece | Pin |
|---|---|
| ming-wasm | `7252aa537ab2ef4c4c2c58088116ecb754f654d6` (v0.1.0, MIT) |
| fusor-ai crates | `fusor-ai-*` 0.1.2, `rayon 1.12.0`, `tokenizers 0.22.2` |
| Rust | 1.99.0 (1.90 fails on `stdarch_neon_dotprod`) |
| Bundle | `fusorlabs/ming-3.0-tts-0.5b-web` @ `62588575d91dceb33822fe1e3925e748393c6b3e`, 850 MB, 15 parts |
| Checkpoint | `inclusionAI/Ming-3.0-tts-0.5B` @ `e5bd8da31ecf1b9276cdbdda821fa891068f1368`, MIT |

There is **no upstream release or tag**: both documented download URLs 404. The
CLI is built from source with `RUSTFLAGS="-C target-cpu=apple-m1"`.

## Measured on the M4 Pro

- English S1 sentence: 5.30 s audio in 2.10 s, RTF **0.397**, 30.5 ms/frame,
  first audio 0.36–0.53 s, bundle load 0.67 s, peak RSS 1.006 GB.
- "Hello from Hearth.": 1.20 s audio, peak −14.15 dBFS, RMS −28.66 dBFS,
  97.4% non-zero samples; PCM16 mono 44.1 kHz.
- Deterministic for the same text/seed; missing bundle and empty text exit 1;
  unknown `--voice` is silently accepted, so Hearth must validate voice ids.

## Limits

- No `--language` flag: only the seven built-in voices, English/Chinese.
- No C ABI; the CLI is a child process with ~0.7 s load and ~1 GB RSS per call.
- Not wired into the app: the JNI path has the same model and a real C ABI, and is
  already integrated on desktop.

The CLI was verified with a real 850 MB bundle; no model weights are committed.
