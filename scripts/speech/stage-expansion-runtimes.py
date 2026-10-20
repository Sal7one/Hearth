#!/usr/bin/env python3
"""Publish the real ARM64 native multilingual runtime with its compilation snapshot."""
import hashlib
import importlib.util
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path
repo = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(repo / 'scripts'))
from native_abis import source_set_digest, update_checksums, verify_elf
work = Path(os.environ.get('HEARTH_SPEECH_EXPANSION_BUILD_DIR', repo / 'build/speech-expansion'))
source = Path(os.environ.get('HEARTH_TRANSCRIBE_SOURCE', work / 'src/transcribe.cpp'))
ndk = Path(os.environ.get('ANDROID_NDK_HOME', Path.home() / 'Library/Android/sdk/ndk/27.0.12077973'))
host = 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
tools = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
pin = re.search(r'HEARTH_TRANSCRIBE_CPP_REVISION "([a-f0-9]{40})"', (repo / 'common-jni/src/main/cpp/speech/expansion/versions.h').read_text()).group(1)
assert subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() == pin
assert not subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain', '--untracked-files=no'], text=True).strip()
expected_exports = {'hearth_cohere_backend_v1', 'hearth_funasr_backend_v1', 'hearth_voxtral_backend_v1'}
allowed = {'libc++_shared.so','libc.so','libm.so','libdl.so','liblog.so','libandroid.so'}
artifacts, snapshots = [], []
for compute in ('cpu', 'vulkan'):
    output = work / ('android-' + compute)
    snapshot = json.loads((output / 'build-sources.json').read_text())
    for path, digest in snapshot.items():
        assert hashlib.sha256((repo / path).read_bytes()).hexdigest() == digest, f'Source changed since compilation: {path}'
    snapshots.append(snapshot)
    name = 'libhearth_transcribe_cpp' + ('_vulkan' if compute == 'vulkan' else '') + '.so'
    binary = output / 'lib' / name
    data = binary.read_bytes()
    verify_elf('arm64-v8a', data)
    def read(*args): return subprocess.check_output([str(tools / 'llvm-readelf'), *args, str(binary)], text=True)
    dynamic = read('-d')
    dependencies = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
    assert not set(dependencies) - (allowed | ({'libvulkan.so'} if compute == 'vulkan' else set())), dependencies
    assert re.findall(r'\(SONAME\).*?\[(.*?)\]', dynamic) == [name]
    symbols = subprocess.check_output([str(tools / 'llvm-nm'), '-D', '--defined-only', str(binary)], text=True)
    assert {line.split()[-1] for line in symbols.splitlines() if line.split()} == expected_exports
    pages = [int(line.split()[-1],16) for line in read('-lW').splitlines() if line.strip().startswith('LOAD ')]
    assert pages and min(pages) >= 16384
    artifacts.append({'abi':'arm64-v8a','file':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),
        'acceleration':compute,'dependencies':dependencies,'minPageAlignment':min(pages),'sourceSetSha256':source_set_digest(snapshot)})
assert snapshots[0] == snapshots[1], 'CPU/GPU were not compiled from identical current inputs'
assets = repo / 'common-jni/src/main/assets/licenses/speech-expansion'
assets.mkdir(parents=True,exist_ok=True)
for relative in ('LICENSE','ggml/LICENSE','ggml/UPSTREAM','src/third_party/miniz/LICENSE','src/third_party/miniz/UPSTREAM'):
    original = source / relative
    if original.is_file():
        destination = assets / 'transcribe.cpp' / relative
        destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(original,destination)
# The Fun-ASR conversion uses this exact model-specific license, distinct from runtime MIT.
model_notice = repo / 'docs/handoff/notices/funasr-model-license-v1.1.txt'
if model_notice.is_file(): shutil.copy2(model_notice,assets / 'FunASR-Model-License-v1.1.txt')
cache = (work / 'android-vulkan/cmake/CMakeCache.txt').read_text().splitlines()
glslc = Path(next(line.split('=',1)[1] for line in cache if line.startswith('Vulkan_GLSLC_EXECUTABLE:')))
metadata = {'abi':2,'architectures':['arm64-v8a'],'sourceRepo':'https://github.com/handy-computer/transcribe.cpp',
    'sourceRevision':pin,'vendoredGgmlUpstreamRevision':'d7cb574130e6f01ad25b3289685489200febcd74',
    'adapterSources':snapshots[0],'artifacts':artifacts,'publicExports':sorted(expected_exports),
    'backends':['cohere_asr','funasr_mlt_nano','voxtral_realtime'],
    'selection':'CPU default never loads GPU plugin; explicit Vulkan verifies primary backend, frontend remains CPU; Voxtral bounded windowed partials',
    'shaderCompiler':{'sha256':hashlib.sha256(glslc.read_bytes()).hexdigest(),'version':subprocess.check_output([str(glslc),'--version'],text=True).strip()},
    'dependencies':{'Vulkan-Headers':'19725e4d48082fe78e26622b15d3080ccd54112b','SPIRV-Headers':'01e0577914a75a2569c846778c2f93aa8e6feddd'},
    'ndk':(ndk / 'source.properties').read_text(),'deviceMeasured':False}
# Publish only after both artifacts and all provenance have passed; no fake sibling binaries.
libroot = repo / 'common-jni/src/main/jniLibs'
for artifact in artifacts:
    shutil.copy2(work / ('android-' + artifact['acceleration']) / 'lib' / artifact['file'],libroot / artifact['abi'] / artifact['file'])
(assets / 'runtime-build.json').write_text(json.dumps(metadata,indent=2)+'\n')
(assets / 'README.txt').write_text('Hearth multilingual native speech. No model weights or network clients. ARM64 CPU and optional explicit Vulkan.\n'
    'Cohere Arabic and Fun-ASR MLT Nano: bounded utterance finals. Voxtral: bounded windowed partials.\n'
    'Primary runtime backend is checked; this does not claim every graph operation executes on GPU. Frontends remain CPU.\n'
    'Not measured on the owner phone. Exact source, recipe, compiler, ABI and artifact hashes: runtime-build.json.\n')
update_checksums(libroot)
print(json.dumps(artifacts,indent=2))
