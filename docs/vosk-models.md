# Vosk model downloads

Verified against the [official Vosk model catalog](https://alphacephei.com/vosk/models) on 2026-10-18. These packages use Hearth's existing Vosk speech recognizer and shared optional translation/TTS pipeline. The six small packages are mobile-oriented alternatives; the Arabic MGB-2 package is substantially larger. No Android latency or recognition-quality improvement is claimed from catalog expansion.

| Spoken language | Publisher checkpoint | ZIP bytes | Expanded bytes |
| --- | --- | ---: | ---: |
| English | `small-en-us-0.15` | 41,205,931 | 70,898,967 |
| Spanish | `small-es-0.42` | 39,817,833 | 60,286,598 |
| French | `small-fr-0.22` | 42,233,323 | 68,483,807 |
| German | `small-de-0.15` | 46,499,967 | 95,621,761 |
| Russian | `small-ru-0.22` | 46,236,750 | 91,289,240 |
| Chinese | `small-cn-0.22` | 43,898,754 | 68,292,271 |
| Arabic (MGB-2) | `ar-mgb2-0.4` | 333,241,610 | 698,195,675 |

The catalog lists Apache-2.0 for each selected checkpoint. All seven downloaded `conf/mfcc.conf` files specify a 16 kHz sample frequency. Arabic MGB-2 uses the publisher's broadcast-speech model; it is not a small general-purpose Arabic model or a dialect coverage guarantee.

`VoskDownloads.kt` records SHA-256 digests computed from the complete official HTTPS ZIP downloads, plus independent digests of their expanded model trees. The publisher's versioned URLs are not content-addressed; changed server contents fail verification. Full archive inspection confirmed exact single roots, regular files/directories, acoustic weights, feature configuration and decoding graphs.

Installation first stages the ZIP into private cache and verifies its byte length and SHA-256. It reads central-directory Unix modes, rejects links/special entries, foreign roots, unsafe paths, duplicates, unsupported encodings and excessive entries/expansion, and checks file sizes and CRCs. Only STORE/DEFLATE entries are accepted. Commons reads metadata and the platform ZIP reader decompresses data after their names, methods, types, sizes, CRCs and entry counts agree; extra compression runtimes are not included. Extraction uses the existing bounded `ModelIntegrity.DirectorySink`; `registerPinnedDirectory` publishes only after the complete tree digest matches. Temporary ZIPs are removed on completion or failure. Download space estimates include the temporary private ZIP as well as the expanded tree. Interrupted cache work is replaced on retry.

Downloaded archives and inspection receipts live under ignored `build/model-support/vosk/`; weights are not source artifacts. FOSS remains offline and can use manually imported extracted Vosk folders. Recognizers use each model's fixed spoken language, with no automatic language detection. Verified catalog tree identity supplies the known language for translation; unknown custom Vosk imports retain their existing behavior.

Focused validation:

```sh
HEARTH_VOSK_ARCHIVES="$PWD/build/model-support/vosk" ./gradlew :app:testPlayQaUnitTest \
  --tests '*VoskDownloadsTest' --tests '*VoskPublisherPackageTest'
```

The optional archive test runs every locally supplied official ZIP through the production extraction path and checks both pins. All twelve focused Vosk tests passed with all seven official archives supplied. Normal tests use small synthetic archives to exercise integrity failures, unsafe paths, duplicate entries, links/special types, CRC damage, missing assets, STORE success, unsupported-method rejection and cancellation cleanup.
