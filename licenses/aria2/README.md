# Hearth aria2 runtime and corresponding sources

Hearth builds aria2 as a separate Android native executable. It uses aria2 for
HTTP and HTTPS model transfers, with TLS verification enabled. It does not ship
Sealplus, yt-dlp, BitTorrent, Metalink, WebSocket, or a video download wrapper.
The Apache-2.0 Hearth application owns its queue, policy, pinned model hashes,
archive validation and installation.

The aria2 sources are GPL-2.0-or-later. This executable statically links OpenSSL
3.5.9, whose Apache-2.0 terms are compatible with GPL-3.0. Accordingly, the linked
executable is distributed under GPL-3.0-or-later. Original notices remain inside
both source archives; GPL-3.0 is included beside this document. Hearth's own
build scripts and ABI verifier retain the project's Apache-2.0 license.

The Android NDK notices accompany the runtime as `NDK-NOTICE.txt` and
`NDK-TOOLCHAIN-NOTICE.txt`. They are copied from the specified NDK, kept in the
corresponding-source bundle under `licenses/aria2`, and shipped with the Play
APK under `assets/downloads`. FOSS APKs contain no aria2 runtime or downloader
assets.

## Exact source revisions

| Component | Git revision | Canonical source archive SHA-256 |
| --- | --- | --- |
| aria2 1.37.0 | `02f2d0d8472b3c38c29b4dba8c75ebd5fdd2899a` | `8a3d395acc53951ce4e020186a2040dbf66c7a266f985b16cabf0f704e77b1df` |
| OpenSSL 3.5.9 | `45e844fa2a14ec92d146bd8f5778ac130b6625fb` | `2611c027f97f06f32d5195a38788ed7885e293adaa01e5ceb164a3896632b2b0` |

The canonical archive command is:

```sh
git -c tar.umask=0002 archive --format=tar --prefix=NAME/ REVISION
```

The source bundle includes these exact tar files in `source-archives`, plus the
build script at its original `scripts/downloads` path, its `scripts/native_abis.py`
helper, this document and the required licenses. The script hashes an archive
before extracting it and retains its path and link checks. No Git checkout is
needed to use a received source bundle.

## Rebuild without Git or network access

Install the host build tools and NDK in advance. The build requires Python 3.12
or later, Perl, make, autoconf, automake, gettext/autopoint, libtool, and Android
NDK **27.0.12077973** for your host. The actual target is Android API 28.
The source-only operation needs Python; it can use the notices already included
in a received bundle without an installed NDK.

Extract `aria2-corresponding-source.tar.gz` into an empty directory, enter that
directory, then run:

```sh
python3 scripts/downloads/build-aria2-android.py --source-only
python3 scripts/downloads/build-aria2-android.py \
  --abis arm64-v8a,x86_64 --ndk /absolute/path/to/android-ndk-r27
```

Both source archives are verified and extracted locally. The script does not
invoke Git or download sources when the bundle's `source-archives` are present.
Source-only mode produces a new deterministic bundle and
`build/aria2-source-manifest.json`; it neither compiles nor rewrites a binary
build identity. Full mode compiles both executables, verifies ELF machine/type
and native dependency constraints, and stages them in:

```text
app/build/generated/aria2/jniLibs/arm64-v8a/libhearth_aria2c.so
app/build/generated/aria2/jniLibs/x86_64/libhearth_aria2c.so
app/build/generated/aria2/assets/downloads/aria2-runtime.json
```

The binary manifest records each executable's size and SHA-256, source/archive
pins, script/helper hashes, toolchain version, and the corresponding-source
bundle's SHA-256. A cached build is reused only when its requested binaries and
corresponding-source bundle still match those records. Executable bit-for-bit
identity across different host/tool installations is not asserted.

When building from the Hearth repository without bundled archives, the script
fetches the two exact revisions from their upstream Git repositories, rejects
modified tracked sources, and checks that their canonical archives match the
same hashes. Generated sources and binaries stay under ignored build folders.

Distribute `build/aria2-corresponding-source.tar.gz` with every release containing
the runtime. Include its SHA-256 in the release checksums. The receiving source
bundle is self-contained; a link to a mutable upstream branch is unnecessary.

The APK's executable ABI, 16 KiB alignment, manifest pins and license assets are
checked by Hearth's release verifier. Android device execution and performance
still require the owner's device tests. Desktop can use an independently
installed aria2 executable; this source bundle describes the bundled Android
runtime.
