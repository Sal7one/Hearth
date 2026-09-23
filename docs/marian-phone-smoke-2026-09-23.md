# Marian ONNX phone smoke · 2026-09-23

Samsung SM-S908E, Android 16 / API 36, Play QA build. These are short checks of
the app's pinned ONNX Marian adapters, not a controlled end-to-end latency
benchmark or a human translation-quality ranking. All three packages use four exact
publisher files, with a verified tree digest at installation. The originals
stay in `Downloads/Hearth/models`; the runtime uses a separate private copy.

| Pair | Download | In-app load + verify | Six-sentence first pass | Warm pass median | Reference chrF++ |
| --- | ---: | ---: | ---: | ---: | ---: |
| English → Arabic | 235.5 MiB | 1.46 s | 1.79 s | 1.86 s | 42.20% |
| Russian → English | 235.4 MiB | 1.54 s | 2.09 s | 2.29 s | 52.43% |
| Chinese → English | 241.6 MiB | 1.42 s | 1.71 s | 1.84 s | 51.59% |

The benchmark runs the production app translator with the six FLEURS-aligned
text samples, once cold and twice warm, sequentially. The scores compare one
published wording per sample. They do not establish meaning preservation.
For context on the same phone and English→Arabic quick set, installed ML Kit
packs took 0.26 s warm and Hy-MT2 Q4 took 53.01 s warm; those figures also
need bilingual review before choosing a quality winner.

The separate native CLI loaded the same package format through the shared C++
engine. Three manual samples per pair completed in 82–216 ms each after load.
It translated Russian “I said Wednesday, not Tuesday” and Chinese “I'm not
talking about Tuesday, I'm talking about Wednesday” intelligibly. Its English
→ Arabic result for “I did not say Tuesday; I said Wednesday” was “لم أقل
ثلاثاً، بل أربعاً.”: **three/four** instead of the day names. This is a
meaning-changing error; the small model is a responsive option, not an
automatic recommendation for important conversation. Test actual languages,
names, numbers and negations before relying on it.

All three in-app four-file downloads verified and installed. The Chinese
decoder download paused at 30 MiB of 184 MiB; after Resume it progressed
to 66 MiB and finished, without export/re-import. We did not simulate a
corrupt file, low storage or a custom SAF folder on the phone. The app's
range/validator and storage-budget unit tests cover those policies, but do
not replace those device checks.

After fixing caption setup and shortcut preflight to recognize pinned Marian
installs, a live device-audio playback check reached the actual overlay:
Nemotron 3.5 with forced Russian → Marian Russian→English displayed Cyrillic
source captions and English translations. Nemotron 3.5 with forced Chinese →
Marian Chinese→English likewise displayed Chinese source captions followed by
English translations. The source was the six short FLEURS clips from the app's
benchmark assets, played through Brave while Android screen-audio capture ran.
The last visible Chinese pair was “也不是他的对手” / “And he's not his opponent.”
This proves selection, audio capture, ASR, translation, publication, and model
cleanup/restart on this phone. It does **not** measure spoken-phrase-to-visible-
translation delay or establish bilingual accuracy. Android reported about
1.58 GiB process PSS during the Chinese joint session (one snapshot, not a
peak or sustained-memory bound). Some source captions were visibly imperfect.
Switching the same Chinese setup from Nemotron to Qwen3-ASR 0.6B reset its
spoken language to Auto, so Chinese was explicitly reselected. Qwen then
produced translated English in the live overlay too. Clear removed the old
caption while the service stayed active; Stop closed the overlay. The Qwen
live run was also visual smoke, not a controlled timing comparison.

Exact source revisions and links are in [model sources](model-sources.md).
The native CLI and downloaded weights used for this smoke are ignored under
`build/research/` and were not added to the repository.
