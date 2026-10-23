# Downloads, imports and transfer comparisons

Android 0.30.0 / code 98 and the matching desktop source share the aria2 process
contract in `hearth-core`. Speech, translation and TTS retain their existing
providers and pipeline stages. Image reading remains an auxiliary feature.

## Queue and controls

Android uses persistent `FileDownloads` operational records and an application
foreground service. Desktop uses an atomic download journal under its data
directory. Both accept large owner-selected catalog batches, preserve interrupted
work, and offer individual/global pause, resume and restart. Desktop restores
interrupted entries as paused; Android also preserves partial files after service
or process interruption. Resume is explicit after a restart.

File concurrency and connections inside one file are separate controls. Balanced
uses two transfers and four aria2 connections per file; Fast uses four transfers
and eight connections per file. Advanced controls expose backend, bandwidth and
retries. Android additionally supports unmetered networks. HTTP uses one connection
per file. More connections are a choice, not a guaranteed speed improvement.

Network slots are released before hashing, archive extraction and model
installation. Android serializes model preparation because multipart packages
share destinations. Desktop has an independent bounded verification lane.
Imports have their own bounded work and do not reserve a transfer slot.
Progress distinguishes waiting, downloading, queued verification, byte hashing,
saving and installation. Installing never silently selects a model.

Android download preferences use DataStore through `UserPreferences`; desktop
preferences use `DesktopSettingsStore`. Queue records and transfer history are
operational journals. FOSS neither packages the downloader executable nor has
network permission; clients and system download delegation retain the BYOK gate.
TVs continue to reject local-model work.

Android 15+ limits background data-sync foreground services. Hearth's timeout
callback preserves the queue, stops the service and tells the owner to reopen
Hearth and resume. It does not attempt to bypass the system limit.
[Android timeout contract](https://developer.android.com/develop/background-work/services/fgs/timeout).

## Backends and model integrity

HTTP resumes contiguous prefixes using exact ranges, lengths and validators.
aria2 preserves its own `.aria2` sparse-piece map. A sparse file's allocated size
is never treated as downloaded bytes or a usable model. Switching a partial file
between incompatible transports requires an explicit restart.

The Play ARM64/x86-64 executable is built from pinned aria2 1.37.0 and OpenSSL
3.5.9 sources using NDK 27/API 28, with static C++/TLS dependencies and 16 KiB ELF
alignment. ARMv7/x86 use HTTP. The package's extracted native directory supplies
the executable; no executable is downloaded into writable app storage. Each app
launch verifies its packaged size/hash before reuse. Android's trust manager
supplies the CA bundle. Native execution on a phone still needs owner testing.

Desktop discovers an installed aria2 or accepts a selected executable. It does
not bundle a Homebrew installation or claim Windows execution validation. The
shared runner checks actual `--version`/HTTPS support with bounded output/time,
disables ambient config/netrc/RPC, supervises cancellation and passes no provider
headers or API keys. Executables are launched with argument arrays, never a shell.

All transports authenticate catalog size and SHA-256 before atomic private
publication. Archive path, count and expansion limits remain in their original
installers. HTTP rejects insecure redirects. Initial aria2 URLs require HTTPS;
an independently installed aria2 can follow a server's downgrade redirect. Its
final pinned digest still authenticates content. Do not describe its CLI flags as
an HTTPS-only redirect guarantee.

aria2 staging needs additional private disk space before export to Downloads/SAF.
HTTP can write directly to a seekable selected destination. Selecting a public
download location does not make that public file the inference model: verified
private copies and originals remain separate facts.

## Magpie and model repair

Magpie needs **three downloads**: generator, codec and a 31,076,864-byte tokenizer
range payload. Benchmark batches previously omitted the third download. It now
uses the same queue, notifications, progress, pause/resume and preparation lane.
Ranges are mapped against the pinned 1,470,208,000-byte source archive; a full
HTTP 200 response or incorrect range is rejected. The concatenated payload and
every extracted tokenizer member are independently authenticated. aria2 does not
download the whole archive for this operation.

Review groups tokenizer dictionaries and weights into one model bundle. A
selected requirement that was never installed is incomplete setup, not a broken
installed copy. Missing/truncated previously recorded files still require repair.
Modification time alone is informational. Native initialization retains content
verification. Invalid saved metadata can be rebuilt from existing private copies;
Android's trusted private-root aliases remain accepted, with nested links and
traversal rejected.

**Restore from saved downloads** requeues complete originals through the normal
verification/installer, including the tokenizer. It does not assume another
network download is required. Missing originals can still be imported or fetched.
Successful installation refreshes model diagnostics independently of the worker;
a diagnostic-refresh failure cannot turn an installed model into failed download.

## Batch imports and comparison results

Speech, Whisper/Vosk, translation GGUF, CT2 and voice selectors accept batches.
Application-owned import jobs survive settings navigation, retain each file's
outcome, continue after a bad file and support cancellation. Content hashes and
package contracts identify models; filenames do not. Private staging remains
bounded and atomic, original read grants are retained when supported, and imports
never silently activate the last model. Unfinished import selections must be
selected again after process termination.

Desktop retains external import provenance and re-verifies every recorded path
at launch, including models not currently selected. Reading profile identity is
also persisted; extra files in a folder cannot silently enable a heavier mode.

Transfer results have searchable/sortable desktop history, Android comparison
rows and CSV export. They measure successful transfer attempts separately from
hashing/install time. Plain HTTP records newly received payload bytes. Resumed
aria2 byte counts can be unknown; they are not invented from sparse file length.
These are network observations, not model-quality or inference-speed scores.
Use Model Lab's existing CPU/GPU inference tables to compare installed models.

## Source and verification

No Sealplus/ytdlp application code or video stack is bundled. Its download design
informed the integration; Hearth invokes aria2 directly. aria2 and OpenSSL retain
their licenses and source pins. Every Android release carries the complete,
hash-bound `aria2-corresponding-source.tar.gz`. See
[rebuild instructions](../licenses/aria2/README.md).

Host coverage includes hundreds-entry queue persistence, independent transfer and
verification lanes, strict HTTP interruption/range recovery, per-file import
continuation, cancellation, deadline handling, Magpie package completeness and
source/path recovery. The opt-in real aria2 test uses an authenticated local TLS
fixture, pauses a transfer, resumes nonzero ranges and verifies the completed
SHA-256. It does not extrapolate a handset or internet throughput gain.
