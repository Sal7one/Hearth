# Additional real-weight translation checks, 2026-10-20

EuroLLM 1.7B Q4_K_M, Riva-Translate 4B v2 Q4_K_M and QuickMT Arabic→English
were downloaded sequentially from the immutable catalog revisions. Every required
file matched its pinned byte count and SHA-256 before inference. The weights were
rehashed and deleted after the checks. No model default changed.

| Model | Verified payload | Actual checks | Evidence |
| --- | ---: | --- | --- |
| EuroLLM 1.7B | 1,045,156,992 bytes | Six pinned FLEURS sentences per English↔Arabic direction | [Receipt](../research/mt-eurollm-cpu-smoke-2026-10-20.json) |
| Riva-Translate 4B v2 | 2,759,302,720 bytes | Six pinned FLEURS sentences per English↔Arabic direction; five real safety checks | [Corrected receipt](../research/mt-riva-translate-cpu-smoke-2026-10-20.json) |
| QuickMT Arabic→English | 410,561,529 bytes across six files | Six pinned FLEURS sentences; official source-SentencePiece pieces, one EOS/no BOS; empty-input rejection | [Receipt](../research/mt-quickmt-cpu-smoke-2026-10-20.json) |

The GGUF runs used the production `TextModel` via `translation_smoke`, macOS ARM64,
two CPU threads, GPU layers zero, one pass, the production 2048-token context,
384-token output cap and 20-second deadline. The host build links Metal, but model
offload was explicitly zero. QuickMT used the production CT2 CPU/Ruy adapter,
FLOAT32 compute for its stored FP16 weights and separate source/target tokenizers.
Its timings include a new process and model load for each sentence; GGUF sentence
timings exclude the separately recorded load. These are functional fixture checks,
not comparable phone benchmarks or corpus quality scores. No real-weight GPU run
or official Python output parity is claimed.

## Riva prompt correction

The publisher's pinned `tokenizer_config.json` contains quoted `\n` escapes inside
Jinja source. Rendering that template with Jinja produces **actual U+000A line
breaks**. Our prior native adapter instead sent literal backslash+n characters.
The [independent rendering receipt](../research/mt-riva-publisher-prompt-2026-10-20.json)
records the official revision and file/template hashes, exact input, renderer and
complete output: five real newlines and zero literal backslash+n separators.

The native adapter now matches that rendered System/User/Assistant envelope. Only
fixed wrapper markers are parsed as special tokens; source and instruction text
remain literal. A weight-free regression test compares the complete native
envelope with the independently rendered fixture. All three current native
translation/policy CTest targets passed after the change.

The first English→Arabic FLEURS sentence was echoed unchanged by the incorrect
envelope. With the corrected envelope it produced:

> لا داعي للقول، إن كنت تعرف لغة رومانسية، سيكون من الأسهل عليك تعلم البرتغالية.

The [before-fix receipt](../research/mt-riva-translate-before-newline-fix-cpu-smoke-2026-10-20.json)
and both sets of full outputs are retained. This resolves an evidenced formatting
error; it does not establish a broad quality or speed improvement.

The exact real GGUF also rejected missing instructions, input above 12,000 UTF-8
bytes and context overflow without returning text. An active request was still
pending when cancellation was requested after 75 ms. It returned an explicit
cancelled error after 90.3 ms and published no partial text. Clearing the cancel
flag allowed the next request to return `الاجتماع يبدأ في التاسعة.`. The receipt
contains the temporary harness source, binary hash and every result. This run did
not deliberately force the 20-second deadline.

## Quality limitations observed

EuroLLM changed the first Arabic→English sentence to “It is not necessary to know
a romantic language to learn the Portuguese language,” which changes the meaning.
Its insect example also lost the dragonfly term. Corrected Riva likewise mistranslated
insect names, and some other sentences still have factual or terminology errors.
QuickMT's reverse outputs are retained without treating nonempty text as semantic
correctness. These families remain experimental pending broader Arabic/Gulf
comparisons and owner device checks for memory, warm latency, sustained heat and
concurrent STT/translation/TTS behavior.
