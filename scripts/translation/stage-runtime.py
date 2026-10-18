#!/usr/bin/env python3
"""Stage the pinned Android runtime, stripped binary hash and its reproducible source record."""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, sys
root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / "scripts"))
from native_abis import ABIS, source_set_digest, update_checksums, verify_elf
abi = sys.argv[1] if len(sys.argv) > 1 else "arm64-v8a"
if abi not in ABIS: raise SystemExit("Unsupported Android ABI")
backend = sys.argv[2] if len(sys.argv) > 2 else "cpu"
if backend not in ("cpu", "vulkan", "opencl"): raise SystemExit("Expected cpu, vulkan or opencl backend")
if backend != "cpu" and abi != "arm64-v8a": raise SystemExit("The optional GPU runtimes are ARM64 only")
output = "android" if abi == "arm64-v8a" else f"android-{abi}"
if backend == "vulkan": output = "android-vulkan-arm64-v8a"
if backend == "opencl": output = "android-opencl-arm64-v8a"
if len(sys.argv) > 3:
    requested_output = sys.argv[3]
    if backend == 'cpu' or Path(requested_output).name != requested_output:
        raise SystemExit('An alternate build directory must be a GPU output name below build/translation-runtime')
    output = requested_output
work = root / 'build/translation-runtime'
ndk = Path(os.environ.get('ANDROID_NDK_HOME', Path.home() / 'Library/Android/sdk/ndk/27.0.12077973'))
ndk_revision = next(line.split('=', 1)[1].strip() for line in (ndk/'source.properties').read_text().splitlines() if line.startswith('Pkg.Revision'))
assert ndk_revision == '27.0.12077973', f'Pinned runtime requires NDK 27.0.12077973; found {ndk_revision}'
strip = next(ndk.glob('toolchains/llvm/prebuilt/*/bin/llvm-strip'))
name = 'libtransiber_translation.so' if backend == 'cpu' else f'libtransiber_translation_{backend}.so'
target = root / f'common-jni/src/main/jniLibs/{abi}/{name}'
target.parent.mkdir(parents=True, exist_ok=True)
verify_elf(abi, (work/output/name).read_bytes())
shutil.copy2(work/output/name, target)
subprocess.run([strip, '--strip-unneeded', target], check=True)
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
licenses = root / 'common-jni/src/main/assets/licenses/translation'
licenses.mkdir(parents=True, exist_ok=True)
shutil.copy2(work/'llama.cpp/LICENSE', licenses/'llama.cpp-LICENSE')
record_path = licenses/'runtime-build.json'
previous = json.loads(record_path.read_text()) if record_path.exists() else {}
binaries = previous.get('binaries', {'arm64-v8a': previous['binary']} if 'binary' in previous else {})
sources = {str(p.relative_to(root)):sha(p) for p in sorted(list((root/'common-jni/src/main/cpp/translation').glob('*')) + [root/'scripts/translation/CMakeLists.txt', root/'scripts/translation/build-runtime.sh', root/'scripts/translation/generate-opencl-shim.py', root/'scripts/translation/patch-opencl-backend.py', root/'common-jni/src/main/cpp/common/lease_registry.h', root/'common-jni/src/main/cpp/common/opencl_loader.h']) if p.is_file()}
binary = {'name':target.name, 'bytes':target.stat().st_size, 'sha256':sha(target), 'sourceSetSha256':source_set_digest(sources)}
if backend == 'cpu': binaries[abi] = binary
record = {'llama_revision': subprocess.check_output(['git','-C',str(work/'llama.cpp'),'rev-parse','HEAD'],text=True).strip(), 'ndk':ndk_revision, 'architectures':sorted(binaries), 'inference':'CPU default, two threads, isolated GGML symbols; separately loaded optional ARM64 Vulkan or Adreno OpenCL', 'binaries':binaries, 'sources':sources}
if 'vulkan' in previous: record['vulkan'] = previous['vulkan']
if 'opencl' in previous: record['opencl'] = previous['opencl']
if backend == 'opencl':
    dependencies = {'OpenCL-Headers':'30bc20a8e90468e231d7c639805ae61ad1fefa4f'}
    for dependency, pin in dependencies.items():
        actual = subprocess.check_output(['git','-C',str(work/dependency),'rev-parse','HEAD'],text=True).strip()
        assert actual == pin, f'Unexpected {dependency} revision: {actual}'
        notices = licenses/dependency
        notices.mkdir(exist_ok=True)
        for item in (work/dependency).glob('LICENSE*'):
            if item.is_file(): shutil.copy2(item, notices/item.name)
    record['opencl'] = {'abi':abi, 'binary':binary, 'dependencies':dependencies,
        'loader':'Typed API forwarding shim; dlopen libOpenCL.so only; no vendor paths or bundled driver; no OpenCL DT_NEEDED dependency',
        'kernels':'Pinned llama Adreno kernels embedded as source; JIT by actual device driver; proprietary precompiled binary kernels disabled',
        'errorHandling':'Pinned GGML CLI exit/CL_CHECK assertions rewritten to exceptions in a generated build copy; kernels unchanged',
        'selection':'Explicit opt-in only; require accessible Adreno GPU, compiler, FP16, model layer assignment and actual GPU model buffers; no automatic CPU fallback'}
if backend == 'vulkan':
    dependencies = {'Vulkan-Headers':'19725e4d48082fe78e26622b15d3080ccd54112b', 'SPIRV-Headers':'01e0577914a75a2569c846778c2f93aa8e6feddd'}
    for dependency, pin in dependencies.items():
        actual = subprocess.check_output(['git','-C',str(work/dependency),'rev-parse','HEAD'],text=True).strip()
        assert actual == pin, f'Unexpected {dependency} revision: {actual}'
        notices = licenses/dependency
        notices.mkdir(exist_ok=True)
        for item in (work/dependency).glob('LICENSE*'):
            if item.is_file(): shutil.copy2(item, notices/item.name)
            elif item.is_dir(): shutil.copytree(item, notices/item.name, dirs_exist_ok=True)
    cache = (work/output/'CMakeCache.txt').read_text().splitlines()
    glslc = Path(next(line.split('=', 1)[1] for line in cache if line.startswith('Vulkan_GLSLC_EXECUTABLE:')))
    record['vulkan'] = {'abi':abi, 'binary':binary, 'dependencies':dependencies,
        'shaderCompilerSha256':sha(glslc),
        'shaderCompilerVersion':subprocess.check_output([glslc,'--version'],text=True).strip(),
        'selection':'Explicit opt-in only; fail on missing GPU, allocation or model errors; no automatic CPU fallback'}
(licenses/'runtime-build.json').write_text(json.dumps(record,indent=2)+'\n')
libroot = root / 'common-jni/src/main/jniLibs'
update_checksums(libroot)
print(target, sha(target))
