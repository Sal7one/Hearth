# Ming local runtime on Android — device proof

Device: **Samsung SM-G781B** (Galaxy S20 FE), Android **13** (SDK 33),
**arm64-v8a**, **8 GB RAM**, 96 GB free, CPU advertises `asimddp` (dotprod).
Owner-authorized test; adb root is denied on this production build (`adbd cannot run
as root`, `su` permission denied), so the proof runs as the shell user through
`app_process`, not as root.

## Build

```bash
bash scripts/desktop/build-ming-android.sh     # scalar and +dotprod, 16 KB pages
```

Both `.so` files export the three `Java_com_sal7one_common_1jni_MingNative_*`
symbols and depend only on libc/libm/libdl. The 850,115,730-byte bundle was pushed
to `/data/local/tmp/ming-bundle`; no weights are committed.

## Results (real 850 MB bundle)

| Variant | Text | Audio | Wall | RTF | First audio | Load | Peak RSS |
|---|---|---:|---:|---:|---:|---:|---:|
| `+dotprod`, 4 threads, quality | English 3.6 s | 3.600 s | 9.77 s | **2.120** | 1.608 s | 1.953 s | 1,045,028 kB |
| `+dotprod`, 4 threads, repeat | English 4.0 s | 4.000 s | 10.37 s | **2.062** | 1.640 s | 1.929 s | — |
| `+dotprod`, 4 threads, Chinese `灵小柔` | Chinese 3.6 s | 3.600 s | 9.79 s | **2.127** | 1.468 s | 1.947 s | — |
| `+dotprod`, 4 threads, 300-char cap | English 18.0 s | 18.000 s | 38.48 s | 2.019 | 1.816 s | 1.932 s | — |
| `+dotprod`, 8 threads, quality | English 3.6 s | 3.600 s | 35.40 s | 9.264 | 5.952 s | 1.941 s | — |
| `+dotprod`, 8 threads, fast | English 3.7 s | 3.700 s | 32.99 s | 8.349 | 6.456 s | 1.939 s | — |
| scalar, 8 threads, quality | English 3.6 s | 3.600 s | 125.07 s | 34.097 | 20.672 s | 2.163 s | — |

All WAVs are mono **44.1 kHz PCM16**; non-zero sample ratios 97.8–99.5%, peaks
10,918–15,039 (no clipping). `ffmpeg` resampled the English WAV to 16 kHz and
**Whisper tiny** transcribed it as:

> Hello from the phone, the museum opens at 9 in the morning.

for the spoken “Hello from the phone. The museum opens at nine in the morning.” —
so the output is intelligible, not merely non-silent.

## Conclusions

- Use the **`+dotprod`** build on `asimddp` devices and **4 threads**; 8 threads
  regress badly on this phone, and scalar is unusable (RTF 34).
- ~1.02 GB peak RSS is acceptable on an 8 GB device but needs an app-level RAM
  gate and foreground/work-manager behavior.
- Load is ~1.9 s per one-shot call; RTF ~2.1 means a 3.6 s utterance takes ~10 s.
  This is usable for read-aloud/pre-generation, not real-time conversation.
- **Not yet verified:** `System.loadLibrary` inside the app APK, JNI on an app
  process under SELinux, LMKD behavior with the full UI resident, audio listening
  by a human, thermal soak beyond two repeated runs.
