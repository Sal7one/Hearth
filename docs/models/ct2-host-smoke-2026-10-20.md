# CT2 production-weight host smoke, 2026-10-20

The selected QuickMT English→Arabic and SMaLL-100 INT8 files were downloaded sequentially from their pinned revisions, checked against every exact byte count and SHA-256, and executed through `ct2_smoke` using the production `hearth::Ct2Model` adapter. All downloaded components were deleted after testing. [Machine-readable evidence](ct2-host-smoke-2026-10-20.json) includes the ten verified components, exact inputs/outputs, exit codes, settings and binary hashes.

The host was macOS 26.5 ARM64. These are separate-process wall times including model loading, tokenization and generation, excluding download and checksum work. Both models used CPU Ruy, two threads, greedy decoding and the production bounds. These numbers are not S25 measurements or sustained-overlay benchmarks.

| Model | Input | Actual output | Seconds |
| --- | --- | --- | ---: |
| QuickMT EN→AR | The meeting starts at nine in the morning. | يبدأ الاجتماع في الساعة التاسعة صباحا. | 0.4189 |
| QuickMT EN→AR | Please turn left at the next street. | من فضلك انعطف يساراً في الشارع التالي | 0.3365 |
| QuickMT EN→AR | Dr. Ahmed has 25 books in Riyadh, and Sarah has 10. | الدكتور أحمد لديه 25 كتابا في الرياض، وسارة لديها 10 كتب. | 0.3952 |
| SMaLL EN→AR | The meeting starts at nine in the morning. | ويبدأ الاجتماع في الساعة التاسعة صباحاً. | 0.2970 |
| SMaLL EN→AR | Please turn left at the next street. | يرجى التوجه اليسار في الشارع التالي. | 0.2771 |
| SMaLL EN→AR | Dr. Ahmed has 25 books in Riyadh, and Sarah has 10. | الدكتور أحمد لديه 25 كتابا في الرياض، والسارة لديها 10 كتابا. | 0.3066 |
| SMaLL AR→EN | مرحبا، أريد قراءة هذا المقال باللغة العربية. | Hello, I want to read this article in Arabic. | 0.2638 |
| SMaLL AR→EN | سأعود إلى المنزل بعد انتهاء العمل. | I will return home after the work is over. | 0.2409 |
| SMaLL AR→EN | قَرَأَ أَحْمَدُ ٢٥ كِتابًا في الرياض. | I read 25 books in Riyadh. | 0.2576 |

The last sample loses Ahmed and changes third person to first person. SMaLL's Sarah/number output also shows grammar/name weaknesses. Successful execution is separate from translation quality. Both families remain experimental; no corpus score, official Python output parity, S25 quality, concurrent STT/TTS behavior or memory/thermal result is claimed. A later same-day [reverse-direction receipt](../research/mt-quickmt-cpu-smoke-2026-10-20.json) records all six pinned QuickMT AR→EN files verified and six FLEURS text cases plus empty-input rejection. It preserves official source SentencePiece pieces and exactly one source EOS.

Both models rejected empty input with exit code 1, no stdout, and `CT2 input must contain 1–12000 UTF-8 bytes` on stderr. This run did not deliberately force the 20-second deadline or cancel an active native call; focused session tests cover propagation of deadline errors, suppression of late cancelled output and exact-once close. The upstream native Arabic fixture also passed FLOAT32 and INT8_FLOAT32 again; existing translation host CTest passed 3/3.

## Preprocessing correction

The audit found a QuickMT source-EOS mismatch and corrected it before the recorded final run. The [official QuickMT client at revision 5fe386f1d24a42fce4f7115726fb4b51ee83c054](https://github.com/quickmt/quickmt/blob/5fe386f1d24a42fce4f7115726fb4b51ee83c054/quickmt/translator.py#L339-L350) explicitly appends `</s>` after source SentencePiece pieces and uses `disable_unk=true`. The selected CT2 config's `add_source_eos=false` means CT2 will not append another token. It does not remove the client's responsibility to append one. Hearth now follows that contract, with separate source/target tokenizers and no source BOS.

SMaLL keeps its separate documented contract: `__targetCode__` on the source, SentencePiece pieces, `</s>`, then shared SentencePiece output decoding. All 100 native language tokens exist in the pinned vocabulary, including `tl` and `ns`; Hearth's `fil` alias is mapped back to `tl` only for this adapter. Its unknown-token setting was not changed.

Host and ARM64 Android native binaries were rebuilt; `libtransiber_ct2.so` is SHA-256 `0d28d820928339420e7e8515e7623785322f69c45b6f2f2761f626ed48ebd004`. The protocol remains 1, and source/binary provenance plus the shared checksum inventory were regenerated.

To repeat without changing runtime source, collect the exact six QuickMT EN→AR or four SMaLL components listed in the inventory, verify their sizes and SHA-256, build the host runtime, then run:

```sh
build/ct2-runtime/host/ct2_smoke /path/to/quickmt-en-ar 0 ar 'The meeting starts at nine in the morning.'
build/ct2-runtime/host/ct2_smoke /path/to/small100-int8 1 en 'سأعود إلى المنزل بعد انتهاء العمل.'
```

Mode 0 is the directional QuickMT adapter; mode 1 is SMaLL. Do not use the English→Arabic QuickMT directory for the reverse direction.
