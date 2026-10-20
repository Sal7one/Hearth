#!/usr/bin/env python3
"""Publish independently attested ARM64 CPU speech DSOs; no model weights or network clients."""
import hashlib, json, os, platform, re, shutil, subprocess, sys
from pathlib import Path
root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / 'scripts'))
from native_abis import source_set_digest, update_checksums, verify_elf
work = Path(os.environ.get('HEARTH_MODERN_SPEECH_BUILD', root / 'build/speech-modern'))
ndk = Path(os.environ.get('ANDROID_NDK_HOME', Path.home() / 'Library/Android/sdk/ndk/27.0.12077973'))
tools = ndk / 'toolchains/llvm/prebuilt' / ('darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64') / 'bin'
versions = (root / 'common-jni/src/main/cpp/speech/modern_cpu/versions.h').read_text()
records = []
allowed = {'libc++_shared.so', 'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so'}
for backend, repository, clone in (
    ('owsm', 'https://github.com/0xShug0/audio.cpp', root / 'research/audio.cpp'),
    ('audar', 'https://github.com/ggml-org/llama.cpp', root / 'research/llama.cpp'),
):
    pin = re.search(r'HEARTH_' + backend.upper() + r'_REVISION "([a-f0-9]{40})"', versions).group(1)
    assert subprocess.check_output(['git','-C',str(clone),'rev-parse','HEAD'], text=True).strip() == pin
    assert not subprocess.check_output(['git','-C',str(clone),'status','--porcelain','--untracked-files=normal'], text=True).strip()
    output = work / backend / 'android'
    snapshot = json.loads((output / 'build-sources.json').read_text())
    for path, digest in snapshot.items():
        assert hashlib.sha256((root / path).read_bytes()).hexdigest() == digest, f'Unrebuilt source: {path}'
    name = f'libhearth_{backend}.so'
    binary = output / 'lib' / name
    data = binary.read_bytes()
    verify_elf('arm64-v8a', data)
    def read(*args): return subprocess.check_output([str(tools / 'llvm-readelf'), *args, str(binary)], text=True)
    dynamic = read('-d')
    dependencies = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
    assert not set(dependencies) - allowed, dependencies
    assert re.findall(r'\(SONAME\).*?\[(.*?)\]', dynamic) == [name]
    symbols = subprocess.check_output([str(tools / 'llvm-nm'), '-D','--defined-only',str(binary)], text=True)
    entry = f'hearth_{backend}_backend_v1'
    assert {line.split()[-1] for line in symbols.splitlines() if line.split()} == {entry}, symbols
    pages = [int(line.split()[-1],16) for line in read('-lW').splitlines() if line.strip().startswith('LOAD ')]
    assert pages and min(pages) >= 16384
    artifact = {'abi':'arm64-v8a','file':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),
        'acceleration':'cpu','dependencies':dependencies,'minPageAlignment':min(pages),'sourceSetSha256':source_set_digest(snapshot)}
    records.append({'backend':'audar_mtmd' if backend == 'audar' else backend, 'sourceRepo':repository,
        'sourceRevision':pin,'adapterSources':snapshot,'publicExports':[entry],'artifacts':[artifact]})

assets = root / 'common-jni/src/main/assets/licenses/speech-modern-cpu'
assets.mkdir(parents=True, exist_ok=True)
for label, clone, paths in (
    ('audio.cpp', root/'research/audio.cpp', ['LICENSE','external/ggml/LICENSE','external/sentencepiece/LICENSE',
        'external/sentencepiece/third_party/esaxx/LICENSE','external/sentencepiece/third_party/absl/LICENSE',
        'external/sentencepiece/third_party/darts_clone/LICENSE','external/sentencepiece/third_party/protobuf-lite/LICENSE']),
    ('llama.cpp', root/'research/llama.cpp', ['LICENSE','vendor/hash/xxhash/LICENSE','vendor/hash/sha256/LICENSE']),
):
    for relative in paths:
        original = clone / relative
        assert original.is_file(), original
        destination = assets / label / relative
        destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(original,destination)
metadata = {'abi':2,'architectures':['arm64-v8a'],'runtimes':records,'deviceMeasured':False,
    'selection':'CPU only; short bounded utterance finals; independent STT stage; no framework server, model manager or network runtime.',
    'owsmPatches':['Strict base 102M dimensions only','Greedy output token exhaustion is an explicit error','Embedded assets cache beside app-private model folders, never inside immutable package'],
    'audarEncoder':'Publisher BF16 projector contains 110 BF16 and 188 F32 tensors plus four F16 frontend tensors: a.conv2d.1.weight, a.conv2d.2.weight, a.conv2d.3.weight, a.conv_out.weight. Adapter accepts only those F16 exceptions; generic F16/quantized encoders are rejected.',
    'ndk':(ndk/'source.properties').read_text()}
for item in records:
    artifact = item['artifacts'][0]
    destination = root / 'common-jni/src/main/jniLibs/arm64-v8a' / artifact['file']
    shutil.copy2(work / ('audar' if item['backend']=='audar_mtmd' else 'owsm') / 'android/lib' / artifact['file'], destination)
(assets/'runtime-build.json').write_text(json.dumps(metadata,indent=2)+'\n')
(assets/'README.txt').write_text('Independent Hearth speech ABI v2 plugins: OWSM v4 Base and Audar Flash. CPU only, ARM64 phones; no model weights.\n'
    'OWSM weights: CC-BY-4.0, Yifan Peng et al./ESPnet. Audar weights: AudarAI Open License v1.0. Runtime licenses are separate.\n'
    'Audar model card: https://huggingface.co/audarai/Audar-ASR-V1-Flash\n'
    'Audar model license: https://www.audarai.com/license/audarai-open-license-v1.0/\n'
    'OWSM model card: https://huggingface.co/espnet/owsm_v4_base_102M\n'
    'Models are separately downloaded with exact component pins; runtime licenses/notices are included below.\n'
    'Host real inference is recorded separately; no phone latency, accuracy or GPU claims.\n')
update_checksums(root/'common-jni/src/main/jniLibs')
print(json.dumps([item['artifacts'][0] for item in records],indent=2))
