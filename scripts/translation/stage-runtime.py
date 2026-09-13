#!/usr/bin/env python3
"""Stage the pinned Android runtime, stripped binary hash and its reproducible source record."""
from pathlib import Path
import hashlib, json, os, shutil, subprocess
root = Path(__file__).resolve().parents[2]
work = root / 'build/translation-runtime'
ndk = Path(os.environ.get('ANDROID_NDK_HOME', Path.home() / 'Library/Android/sdk/ndk/27.0.12077973'))
ndk_revision = next(line.split('=', 1)[1].strip() for line in (ndk/'source.properties').read_text().splitlines() if line.startswith('Pkg.Revision'))
assert ndk_revision == '27.0.12077973', f'Pinned runtime requires NDK 27.0.12077973; found {ndk_revision}'
strip = next(ndk.glob('toolchains/llvm/prebuilt/*/bin/llvm-strip'))
target = root / 'common-jni/src/main/jniLibs/arm64-v8a/libtransiber_translation.so'
shutil.copy2(work/'android/libtransiber_translation.so', target)
subprocess.run([strip, '--strip-unneeded', target], check=True)
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
licenses = root / 'common-jni/src/main/assets/licenses/translation'
licenses.mkdir(parents=True, exist_ok=True)
shutil.copy2(work/'llama.cpp/LICENSE', licenses/'llama.cpp-LICENSE')
record = {'llama_revision': subprocess.check_output(['git','-C',str(work/'llama.cpp'),'rev-parse','HEAD'],text=True).strip(), 'ndk':ndk_revision, 'abi':'arm64-v8a', 'inference':'CPU, two threads, isolated GGML symbols', 'binary':{'name':target.name,'sha256':sha(target)}, 'sources':{str(p.relative_to(root)):sha(p) for p in sorted(list((root/'common-jni/src/main/cpp/translation').glob('*')) + [root/'scripts/translation/CMakeLists.txt', root/'scripts/translation/build-runtime.sh', root/'common-jni/src/main/cpp/common/lease_registry.h']) if p.is_file()}}
(licenses/'runtime-build.json').write_text(json.dumps(record,indent=2)+'\n')
libroot = root / 'common-jni/src/main/jniLibs'
(libroot/'SHA256SUMS').write_text(''.join(f'{sha(p)}  {p.relative_to(libroot)}\n' for p in sorted(libroot.glob('arm64-v8a/*.so'))))
print(target, sha(target))
