#!/usr/bin/env python3
"""Stage the optional ARM64 runtime with exact vendored sources and build inputs."""
from pathlib import Path
import hashlib, importlib.util, json, os, shutil, subprocess, sys

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'scripts'))
from native_abis import source_set_digest, update_checksums, verify_elf
pin_spec = importlib.util.spec_from_file_location('whisper_source_pin', Path(__file__).with_name('source-pin.py'))
pins = importlib.util.module_from_spec(pin_spec)
pin_spec.loader.exec_module(pins)
work = root / 'build/whisper-runtime'
vendor = root / 'common-jni/src/main/cpp/whisper/upstream'
ndk = Path(os.environ.get('ANDROID_NDK_HOME', Path.home() / 'Library/Android/sdk/ndk/27.0.12077973'))
revision = next(line.split('=', 1)[1].strip() for line in (ndk / 'source.properties').read_text().splitlines() if line.startswith('Pkg.Revision'))
assert revision == '27.0.12077973', 'Use the pinned NDK'
source = work / 'android/libhearth_whisper_vulkan.so'
target = root / 'common-jni/src/main/jniLibs/arm64-v8a/libhearth_whisper_vulkan.so'
verify_elf('arm64-v8a', source.read_bytes())
shutil.copy2(source, target)
strip = next(ndk.glob('toolchains/llvm/prebuilt/*/bin/llvm-strip'))
subprocess.run([strip, '--strip-unneeded', target], check=True)
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
inputs = [p for p in (root / 'scripts/whisper').glob('*') if p.is_file()]
inputs += [root / f'common-jni/src/main/cpp/whisper/{n}' for n in
           ('whisper_runtime_api.h', 'whisper_vulkan_plugin.cpp', 'vulkan_exports.map')]
inputs += [root / 'scripts/native_abis.py']
sources = {str(p.relative_to(root)): sha(p) for p in sorted(inputs)}
vendor_sources = pins.source_hashes(vendor)
sources.update({str(vendor.relative_to(root) / p): digest for p, digest in vendor_sources.items()})
expected = __import__('re').search(r'#define HEARTH_WHISPER_REVISION "([0-9a-f]{64})"',
    (root / 'common-jni/src/main/cpp/whisper/whisper_runtime_api.h').read_text()).group(1)
assert pins.digest(vendor_sources) == expected
dependencies = {'Vulkan-Headers': '19725e4d48082fe78e26622b15d3080ccd54112b',
                'SPIRV-Headers': '01e0577914a75a2569c846778c2f93aa8e6feddd'}
dep_root = root / 'build/translation-runtime'
licenses = root / 'common-jni/src/main/assets/licenses/whisper-vulkan'
licenses.mkdir(parents=True, exist_ok=True)
shutil.copy2(vendor / 'LICENSE', licenses / 'whisper-LICENSE')
for name, pin in dependencies.items():
    assert subprocess.check_output(['git', '-C', str(dep_root / name), 'rev-parse', 'HEAD'], text=True).strip() == pin
    for item in (dep_root / name).glob('LICENSE*'):
        destination = licenses / name / item.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        if item.is_file(): shutil.copy2(item, destination)
        elif item.is_dir(): shutil.copytree(item, destination, dirs_exist_ok=True)
cache = (work / 'android/CMakeCache.txt').read_text().splitlines()
glslc = Path(next(line.split('=', 1)[1] for line in cache if line.startswith('Vulkan_GLSLC_EXECUTABLE:')))
record = {'abi': 'arm64-v8a', 'apiVersion': 1, 'ndk': revision,
    'vendorTreeSha256': expected, 'upstreamRevision': None,
    'sourceNote': 'Complete Hearth vendored Whisper 1.8.2/GGML 0.9.4 tree; original upstream commit not recorded',
    'binary': {'name': target.name, 'bytes': target.stat().st_size, 'sha256': sha(target),
               'sourceSetSha256': source_set_digest(sources)},
    'sources': sources, 'dependencies': dependencies,
    'patchedWhisperSha256': sha(work / 'whisper-gpu.cpp'),
    'shaderCompilerSha256': sha(glslc),
    'shaderCompilerVersion': subprocess.check_output([glslc, '--version'], text=True).strip(),
    'selection': 'Explicit opt-in only; version/size/vendor-tree checked private ABI; missing GPU or allocation errors are failures; CPU remains independent'}
(licenses / 'runtime-build.json').write_text(json.dumps(record, indent=2) + '\n')
update_checksums(root / 'common-jni/src/main/jniLibs')
print(target, sha(target))
