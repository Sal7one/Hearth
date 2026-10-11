#!/usr/bin/env python3
"""Stage the pinned Android runtime, stripped binary hash and its reproducible source record."""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, sys
root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / "scripts"))
from native_abis import ABIS, source_set_digest, update_checksums, verify_elf
abi = sys.argv[1] if len(sys.argv) > 1 else "arm64-v8a"
if abi not in ABIS: raise SystemExit("Unsupported Android ABI")
output = "android" if abi == "arm64-v8a" else f"android-{abi}"
work = root / 'build/translation-runtime'
ndk = Path(os.environ.get('ANDROID_NDK_HOME', Path.home() / 'Library/Android/sdk/ndk/27.0.12077973'))
ndk_revision = next(line.split('=', 1)[1].strip() for line in (ndk/'source.properties').read_text().splitlines() if line.startswith('Pkg.Revision'))
assert ndk_revision == '27.0.12077973', f'Pinned runtime requires NDK 27.0.12077973; found {ndk_revision}'
strip = next(ndk.glob('toolchains/llvm/prebuilt/*/bin/llvm-strip'))
target = root / f'common-jni/src/main/jniLibs/{abi}/libtransiber_translation.so'
target.parent.mkdir(parents=True, exist_ok=True)
verify_elf(abi, (work/output/'libtransiber_translation.so').read_bytes())
shutil.copy2(work/output/'libtransiber_translation.so', target)
subprocess.run([strip, '--strip-unneeded', target], check=True)
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
licenses = root / 'common-jni/src/main/assets/licenses/translation'
licenses.mkdir(parents=True, exist_ok=True)
shutil.copy2(work/'llama.cpp/LICENSE', licenses/'llama.cpp-LICENSE')
# mtmd is linked into the same runtime. Preserve its vendored notices alongside
# llama's own license; copying only the top-level MIT notice loses these terms.
vendor = work/'llama.cpp/vendor'
for source, name in [('hash/xxhash/LICENSE', 'xxhash-LICENSE'),
                     ('hash/sha256/LICENSE', 'sha256-LICENSE'),
                     ('hash/rotate-bits/LICENSE.md', 'rotate-bits-LICENSE')]:
    shutil.copy2(vendor/source, licenses/name)
def preserve_comment(source, marker, name):
    text = (vendor/source).read_text()
    position = text.index(marker)
    start = text.rfind('/*', 0, position + 1)
    end = text.index('*/', position) + 2
    if start < 0: raise RuntimeError(f'Missing pinned vendor license block: {source}')
    (licenses/name).write_text(text[start:end] + '\n')
preserve_comment('stb/stb_image.h', 'This software is available under 2 licenses', 'stb-image-LICENSE')
preserve_comment('miniaudio/miniaudio.h', 'This software is available as a choice', 'miniaudio-LICENSE')
preserve_comment('hash/sha1/sha1.c', '100% Public Domain', 'sha1-LICENSE')
record_path = licenses/'runtime-build.json'
previous = json.loads(record_path.read_text()) if record_path.exists() else {}
binaries = previous.get('binaries', {'arm64-v8a': previous['binary']} if 'binary' in previous else {})
sources = {str(p.relative_to(root)):sha(p) for p in sorted(list((root/'common-jni/src/main/cpp/translation').glob('*')) + list((root/'common-jni/src/main/cpp/vision').glob('*')) + [root/'scripts/translation/CMakeLists.txt', root/'scripts/translation/build-runtime.sh', root/'scripts/translation/stage-runtime.py', root/'common-jni/src/main/cpp/common/lease_registry.h', root/'common-jni/src/main/cpp/common/utf8_utils.h']) if p.is_file()}
binaries[abi] = {'name':target.name, 'bytes':target.stat().st_size, 'sha256':sha(target), 'sourceSetSha256':source_set_digest(sources)}
record = {'llama_revision': subprocess.check_output(['git','-C',str(work/'llama.cpp'),'rev-parse','HEAD'],text=True).strip(), 'ndk':ndk_revision, 'architectures':sorted(binaries), 'inference':'CPU, two translation threads; configurable bounded vision threads; isolated GGML symbols; mtmd vision, no video/FFmpeg', 'binaries':binaries, 'sources':sources}
(licenses/'runtime-build.json').write_text(json.dumps(record,indent=2)+'\n')
libroot = root / 'common-jni/src/main/jniLibs'
update_checksums(libroot)
print(target, sha(target))
