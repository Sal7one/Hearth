#!/usr/bin/env python3
"""Build isolated, model-free Windows/macOS desktop JNI adapters from pinned local sources.

The ONNX speech stage compiles pinned static sherpa-onnx for macOS arm64; its own
CMake dependencies are fetched at build time and pinned by URL_HASH upstream.
Supply the official extracted ONNX Runtime 1.20.1 SDK, a local llama.cpp checkout at
the exact Android translation source revision, and a clean sherpa-onnx checkout at
HEARTH_QWEN_REVISION.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
LLAMA_PIN = "64e9bceb2c3a856efed96feda784a50947049feb"
WHISPER_PIN = "5f3464bc06c04426d66eae69e5e510d32fb76621b524c42ab25b4294f035a920"
SHERPA_PIN = "210f340bcfdfd5b9ad6b24245e77a934d6c28f1b"
CT2_PIN = "8c8cab2a954810216a45d65c1b8d56839c846e5b"
SENTENCEPIECE_PIN = "17d7580d6407802f85855d2cc9190634e2c95624"
# Every dependency the pinned sherpa-onnx CMake fetches is declared with this URL_HASH.
# The build fetches them at build time; the digest of this map is recorded in the bundle.
SHERPA_INPUTS = {
    "eigen-5.0.1.tar.gz": "e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec",
    "hclust-cpp-2026-02-25.tar.gz": "8f14e024c709d73afb40ae69cb22de4b73dba67cbce40f2e518813da8139ab56",
    "json-v3.12.0.tar.gz": "4b92eb0c06d10683f7447ce9406cb97cd4b453be18d7279320f7b2f025c10187",
    "kaldi-decoder-v0.3.0.tar.gz": "b9f34cfb4fd3b1344100eead79ef4d37aa15962274b9e3056de345021f76a1b0",
    "kaldi-native-fbank-v1.22.3.tar.gz": "9176cc66fc7ce1edf85cf355b06e320c57db6297df74277f575183468893cf61",
    "openfst-v1.8.5-2026-07-09.tar.gz": "2ff712a32952fcb01d351121a6bc8ccf4fdc6b2aa06ce8df2b3095dedd518c0e",
    "simple-sentencepiece-v0.7.tar.gz": "1748a822060a35baa9f6609f84efc8eb54dc0e74b9ece3d82367b7119fdc75af",
    "kaldifst-v1.8.0.tar.gz": "3f247b7e5a2409071202f5e2bc6200060f66728c0a3443c03923ad2723e040b3",
    "kissfft-febd4cae.zip": "497103e664168ebe39580b757adbe616f6cf85a16572af581ca7bc42d0ab13fd",
}
SHERPA_NOTICES = [
    "sherpa-onnx/LICENSE", "eigen/LICENSE", "eigen/COPYING.APACHE", "eigen/COPYING.BSD",
    "eigen/COPYING.MINPACK", "eigen/COPYING.MPL2", "eigen/COPYING.README", "json/LICENSE.MIT",
    "kaldi_decoder/LICENSE", "kaldi_native_fbank/LICENSE", "kaldifst/LICENSE", "kissfft/COPYING",
    "openfst/COPYING", "sentencepiece/LICENSE", "simple-sentencepiece/LICENSE",
]
SHERPA_COMBINED = [
    "libsherpa-onnx-c-api.a", "libsherpa-onnx-core.a", "libkaldi-native-fbank-core.a",
    "libkissfft-float.a", "libsherpa-onnx-fstfar.a", "libsherpa-onnx-fst.a",
    "libsherpa-onnx-kaldifst-core.a", "libkaldi-decoder-core.a", "libssentencepiece_core.a",
]


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def source_tree_sha(root):
    """Content digest of a pinned checkout's tracked files; never follows symlinks."""
    names = subprocess.check_output(["git", "-C", str(root), "ls-files", "-z"], text=False).split(b"\0")
    entries = {}
    for raw in names:
        if not raw:
            continue
        relative = os.fsdecode(raw)
        path = root / relative
        entries[relative] = "symlink:" + os.readlink(path) if path.is_symlink() else sha(path)
    return hashlib.sha256(json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def require_exports(dylib, prefix, expected):
    names = [line.split()[-1] for line in subprocess.check_output(["nm", "-gU", str(dylib)], text=True).splitlines() if line.strip()]
    found = [name for name in names if name.startswith(prefix)]
    if len(found) != expected:
        raise RuntimeError(f"Desktop JNI exports changed: expected {expected} {prefix} symbols, found {len(found)}")


def snapshot():
    paths = [ROOT / "scripts/desktop/build-native.py"]
    for folder in ["scripts/desktop/native", "desktop-runtime/src/main/cpp"]:
        paths.extend(p for p in (ROOT / folder).rglob("*") if p.is_file())
    for name in ["common-jni/src/main/cpp/translation/text_model.h", "common-jni/src/main/cpp/translation/translation_jni.cpp",
                 "common-jni/src/main/cpp/common/lease_registry.h", "hearth-tts-supertonic/src/main/cpp/voice/voice_bounds.h",
                 "common-jni/src/main/cpp/onnx/include/onnxruntime/onnxruntime_c_api.h",
                 "common-jni/src/main/cpp/speech/backends/qwen_backend.cpp",
                 "common-jni/src/main/cpp/speech/backend_abi.h",
                 "common-jni/src/main/cpp/speech/backends/backend_guard.h",
                 "common-jni/src/main/cpp/speech/backend_versions.h",
                 "common-jni/src/main/cpp/speech/utterance_segmenter.h",
                 "common-jni/src/main/cpp/speech/qwen_language.h",
                 "common-jni/src/main/cpp/onnx/onnx_engine.cpp",
                 "common-jni/src/main/cpp/onnx/onnx_engine.h",
                 "common-jni/src/main/cpp/common/logging.cpp", "common-jni/src/main/cpp/common/logging.h",
                 "common-jni/src/main/cpp/common/json_utils.cpp", "common-jni/src/main/cpp/common/json_utils.h",
                 "common-jni/src/main/cpp/common/engine_interface.cpp", "common-jni/src/main/cpp/common/engine_interface.h",
                 "common-jni/src/main/cpp/common/audio_utils.cpp", "common-jni/src/main/cpp/common/audio_utils.h",
                 "common-jni/src/main/cpp/common/error_codes.h", "common-jni/src/main/cpp/common/audio_gate.h",
                 "common-jni/src/main/cpp/common/buffer_view.h",
                 "common-jni/src/main/cpp/common/lifecycle_gate.h", "common-jni/src/main/cpp/common/scoped_timer.h",
                 "common-jni/src/main/cpp/translation/ct2/ct2_jni.cpp",
                 "common-jni/src/main/cpp/translation/ct2/ct2_model.h",
                 "common-jni/src/main/cpp/translation/ct2/byte_bpe.h",
                 "common-jni/src/main/cpp/translation/ct2/ct2_desktop.exports",
                 "common-jni/src/main/cpp/translation/bpe-rust/src/lib.rs",
                 "common-jni/src/main/cpp/translation/bpe-rust/Cargo.toml",
                 "common-jni/src/main/cpp/translation/bpe-rust/Cargo.lock",
                 "scripts/translation/ct2/CMakeLists.txt",
                 "scripts/translation/ct2/build-runtime.sh"]:
        paths.append(ROOT / name)
    paths.extend((ROOT / "hearth-tts-supertonic/src/main/cpp/voice").glob("*.cpp"))
    paths.append(ROOT / "hearth-tts-supertonic/src/main/cpp/voice/supertonic.h")
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(set(paths))}


def prepare_translation(source):
    """Versioned desktop ABI; Android's two-argument JNI and sources stay untouched."""
    def replace(old, new):
        nonlocal source
        if source.count(old) != 1:
            raise RuntimeError(f"Translation desktop preparation changed; review exact source adaptation: {old[:100]}")
        source = source.replace(old, new)
    replace('#include "text_model.h"', '#include "text_model.h"\n#include "desktop_metal.h"\n#include "desktop_jni.h"\n#include <cstdio>')
    replace('#include "../common/lease_registry.h"', '#include "lease_registry.h"')
    replace('stt::concurrency::LeaseRegistry<transiber::TextModel> models;', '''class DesktopTextModel final : public transiber::TextModel {
    std::string placement;
public:
    DesktopTextModel(const std::string& path, int threads, int layers, int mode)
        : transiber::TextModel(path, threads, layers, mode) {
        if (layers > 0) {
            int offloaded = 0, total = 0;
            const auto marker = transiber::nativeOffloadSummary.find("offloaded ");
            if (marker == std::string::npos || std::sscanf(transiber::nativeOffloadSummary.c_str() + marker,
                    "offloaded %d/%d layers to GPU", &offloaded, &total) != 2 || offloaded <= 0)
                throw std::runtime_error("Metal requested, but the translation model did not offload any layers to the GPU");
            placement = "Metal GPU; " + transiber::nativeOffloadSummary + transiber::nativeModelBufferSummary;
        } else placement = "CPU";
        placement += "; threads=" + std::to_string(threads);
    }
    const std::string& executionInfo() const { return placement; }
};
stt::concurrency::LeaseRegistry<DesktopTextModel> models;''')
    replace('    return 3;\n}', '    return 4;\n}')
    replace('jbyteArray path, jint promptMode) {', 'jbyteArray path, jint promptMode, jint threads, jint gpuLayers) {')
    replace('return models.insert(std::make_unique<transiber::TextModel>(bytes(env, path), 2, 0, promptMode));', '''if (threads < 1 || threads > 32) throw std::invalid_argument("Translation threads must be 1–32");
        if (gpuLayers != 0 && gpuLayers != 99) throw std::invalid_argument("Desktop GPU layer policy must be CPU (0) or Metal (99)");
        if (gpuLayers > 0) desktopRequireMetal();
        return models.insert(std::make_unique<DesktopTextModel>(bytes(env, path), threads, gpuLayers, promptMode));''')
    replace('#if defined(HEARTH_TRANSLATION_VULKAN) || defined(HEARTH_TRANSLATION_OPENCL)\nextern "C" JNIEXPORT jbyteArray JNICALL HEARTH_TRANSLATION_JNI(executionInfo)', '#if 1 // Desktop exposes actual CPU/Metal placement.\nextern "C" JNIEXPORT jbyteArray JNICALL HEARTH_TRANSLATION_JNI(executionInfo)')
    source += '\nextern "C" JNIEXPORT jboolean JNICALL HEARTH_TRANSLATION_JNI(metalAvailable)(JNIEnv*, jobject) { return desktopMetalAvailable(); }\n'
    return source


def sherpa_install(source, ort_root, cache, jobs):
    """Build the pinned static sherpa-onnx C API for macOS arm64 and combine its archives."""
    pin = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if pin != SHERPA_PIN:
        raise RuntimeError(f"sherpa-onnx must be checked out at {SHERPA_PIN}, found {pin}")
    changes = subprocess.check_output(["git", "-C", str(source), "status", "--porcelain"], text=True)
    if changes.strip():
        raise RuntimeError("Desktop sherpa-onnx source checkout has local changes")
    if (ort_root / "VERSION_NUMBER").read_text(encoding="utf-8").strip() != "1.20.1":
        raise RuntimeError("The desktop sherpa build requires the official ONNX Runtime 1.20.1 host SDK")
    match = re.search(r'static const char \*version = "([^"]+)"',
                      (source / "sherpa-onnx/csrc/version.cc").read_text(encoding="utf-8"))
    if not match:
        raise RuntimeError("Unable to read the pinned sherpa-onnx version")
    build = cache / "sherpa-build"
    install = cache / "sherpa-install"
    # sherpa's pre-installed ORT probe looks for the unversioned dylib name.
    ort_lib = cache / "sherpa-ort-lib"
    ort_lib.mkdir(parents=True, exist_ok=True)
    unversioned = ort_lib / "libonnxruntime.dylib"
    if unversioned.is_symlink() or unversioned.exists():
        unversioned.unlink()
    unversioned.symlink_to((ort_root / "lib/libonnxruntime.1.20.1.dylib").resolve())
    environment = os.environ.copy()
    environment["SHERPA_ONNXRUNTIME_INCLUDE_DIR"] = str((ort_root / "include").resolve())
    environment["SHERPA_ONNXRUNTIME_LIB_DIR"] = str(ort_lib.resolve())
    configure = [
        "cmake", "-S", str(source), "-B", str(build),
        "-DCMAKE_BUILD_TYPE=Release", f"-DCMAKE_INSTALL_PREFIX={install}",
        "-DCMAKE_OSX_ARCHITECTURES=arm64", "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        "-DBUILD_SHARED_LIBS=OFF", "-DSHERPA_ONNX_ENABLE_C_API=ON",
        "-DSHERPA_ONNX_ENABLE_BINARY=OFF", "-DSHERPA_ONNX_BUILD_C_API_EXAMPLES=OFF",
        "-DSHERPA_ONNX_ENABLE_TESTS=OFF", "-DSHERPA_ONNX_ENABLE_CHECK=OFF",
        "-DSHERPA_ONNX_ENABLE_PYTHON=OFF", "-DSHERPA_ONNX_ENABLE_JNI=OFF",
        "-DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF", "-DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF",
        "-DSHERPA_ONNX_ENABLE_TTS=OFF",
        "-DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON",
    ]
    subprocess.run(configure, check=True, env=environment)
    subprocess.run(["cmake", "--build", str(build), "--config", "Release", "--parallel", str(jobs)], check=True, env=environment)
    subprocess.run(["cmake", "--install", str(build), "--config", "Release", "--prefix", str(install)], check=True, env=environment)
    headers = install / "include/sherpa-onnx/c-api/c-api.h"
    if not headers.is_file():
        raise RuntimeError("The pinned sherpa-onnx build did not install its C API header")
    libraries = install / "lib"
    members = [libraries / name for name in SHERPA_COMBINED]
    missing = [member.name for member in members if not member.is_file()]
    if missing:
        raise RuntimeError("The pinned sherpa-onnx build is missing archives: " + ", ".join(missing))
    subprocess.run(["libtool", "-static", "-o", str(libraries / "libsherpa-onnx.a"), *map(str, members)], check=True)
    return install, match.group(1)


def ct2_install(build_dir, output, jobs):
    """Build the pinned CTranslate2/SentencePiece host runtime and stage its JNI dylib."""
    if not shutil.which("cargo") or not shutil.which("rustc"):
        raise RuntimeError("The CTranslate2 stage requires cargo and rustc")
    environment = os.environ.copy()
    environment["HEARTH_BUILD_JOBS"] = str(jobs)
    subprocess.run(["bash", str(ROOT / "scripts/translation/ct2/build-runtime.sh"), "host"], check=True, env=environment)
    for name, pin in [("CTranslate2", CT2_PIN), ("sentencepiece", SENTENCEPIECE_PIN)]:
        checkout = ROOT / "build/ct2-runtime/src" / name
        head = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
        if head != pin:
            raise RuntimeError(f"{name} must be checked out at {pin}, found {head}")
    dylib = ROOT / "build/ct2-runtime/host/libhearth_desktop_ct2.dylib"
    if not dylib.is_file():
        raise RuntimeError("The CTranslate2 host build did not produce libhearth_desktop_ct2.dylib")
    require_exports(dylib, "_Java_com_sal7one_common_1jni_translation_Ct2Native_", 5)
    linked = subprocess.check_output(["otool", "-L", str(dylib)], text=True)
    for banned in ("libctranslate2", "libsentencepiece", "libhearth_byte_bpe"):
        if banned in linked:
            raise RuntimeError(f"The CTranslate2 dylib must statically embed {banned}")
    shutil.copyfile(dylib, output / "libhearth_desktop_ct2.dylib")
    notices = [
        (ROOT / "build/ct2-runtime/src/CTranslate2/LICENSE", "CTranslate2 LICENSE (MIT)"),
        (ROOT / "build/ct2-runtime/src/CTranslate2/third_party/ruy/LICENSE", "Ruy LICENSE (Apache-2.0)"),
        (ROOT / "build/ct2-runtime/src/CTranslate2/third_party/spdlog/LICENSE", "spdlog LICENSE (MIT)"),
        (ROOT / "build/ct2-runtime/src/CTranslate2/third_party/cpu_features/LICENSE", "cpu_features LICENSE (Apache-2.0)"),
        (ROOT / "build/ct2-runtime/src/sentencepiece/LICENSE", "SentencePiece LICENSE (Apache-2.0)"),
    ]
    aggregate = []
    for path, label in notices:
        if path.is_file():
            aggregate.append(f"===== {label} =====\n{path.read_text(encoding='utf-8').rstrip()}\n")
    aggregate.append("===== hearth-byte-bpe (Apache-2.0) =====\nThe pinned Rust byte-BPE static library is built from common-jni/src/main/cpp/translation/bpe-rust (Apache-2.0) and its Cargo.lock dependencies.\n")
    (output / "Ct2-Dependencies-NOTICES.txt").write_text("\n".join(aggregate), encoding="utf-8", newline="\n")
    receipt = ROOT / "build/ct2-runtime/host/hearth-ct2-sources.json"
    return sha(receipt) if receipt.is_file() else ""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=["all", "whisper", "translation", "supertonic", "onnx", "ct2"], default="all")
    parser.add_argument("--llama-root", type=Path, default=ROOT / "build/translation-runtime/llama.cpp")
    parser.add_argument("--sherpa-root", type=Path, default=ROOT / "build/desktop-dependencies/sherpa-onnx")
    parser.add_argument("--ort-root", type=Path, default=os.environ.get("HEARTH_ORT_ROOT"))
    parser.add_argument("--build-dir", type=Path, default=ROOT / "build/desktop-native")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--metal", action="store_true", help="Compile optional runtime-selected Metal using embedded kernels (macOS)")
    args = parser.parse_args()
    system = {"Darwin": "macos", "Windows": "windows", "Linux": "linux"}.get(platform.system())
    arch = {"arm64": "arm64", "aarch64": "arm64", "x86_64": "x64", "amd64": "x64"}.get(platform.machine().lower())
    if not system or not arch:
        parser.error("Unsupported host OS/architecture")
    target = f"{system}-{arch}"
    output = (args.output or ROOT / "desktop-runtime/native-libs" / target).resolve()
    output.mkdir(parents=True, exist_ok=True)
    args.build_dir.mkdir(parents=True, exist_ok=True)
    stages = ["whisper", "translation", "supertonic"] if args.stage == "all" else [args.stage]
    if args.stage == "all":
        if (system, arch) == ("macos", "arm64"):
            stages.extend(["onnx", "ct2"])
        else:
            print(f"Skipping Qwen3-ASR (onnx) and CTranslate2 (ct2): built only for macos-arm64; host is {target}", file=sys.stderr)
    if "onnx" in stages and system != "macos":
        parser.error("The Qwen3-ASR (sherpa-onnx) stage is built for macOS arm64 only")
    if "onnx" in stages and arch != "arm64":
        parser.error("The Qwen3-ASR (sherpa-onnx) stage is built for macOS arm64 only")
    if "ct2" in stages and (system, arch) != ("macos", "arm64"):
        parser.error("The CTranslate2 (ct2) stage is built for macOS arm64 only")
    if args.metal and system != "macos": parser.error("Metal requires macOS")
    before = snapshot()
    subprocess.run([sys.executable, str(ROOT / "scripts/whisper/source-pin.py"),
                    str(ROOT / "common-jni/src/main/cpp/whisper/upstream"),
                    str(ROOT / "common-jni/src/main/cpp/whisper/whisper_runtime_api.h")], check=True)
    if "translation" in stages:
        pin = subprocess.check_output(["git", "-C", str(args.llama_root), "rev-parse", "HEAD"], text=True).strip()
        if pin != LLAMA_PIN: raise RuntimeError(f"llama.cpp must be checked out at {LLAMA_PIN}, found {pin}")
        changes = subprocess.check_output(["git", "-C", str(args.llama_root), "status", "--porcelain"], text=True)
        if changes.strip(): raise RuntimeError("Desktop llama.cpp source checkout has local changes")
    if ("supertonic" in stages or "onnx" in stages) and (not args.ort_root or not args.ort_root.is_dir()):
        parser.error("--ort-root/HEARTH_ORT_ROOT must name the official extracted ONNX Runtime 1.20.1 host SDK")
    sherpa = None
    sherpa_version = None
    sherpa_tree = None
    sherpa_source_tree = None
    if "onnx" in stages:
        sherpa, sherpa_version = sherpa_install(args.sherpa_root.resolve(), args.ort_root.resolve(),
                                                args.build_dir.resolve() / target / "onnx", args.jobs)
        sherpa_tree = subprocess.check_output(["git", "-C", str(args.sherpa_root), "rev-parse", "HEAD^{tree}"], text=True).strip()
        sherpa_source_tree = source_tree_sha(args.sherpa_root.resolve())
    ct2_sources = None
    if "ct2" in stages:
        ct2_sources = ct2_install(args.build_dir.resolve() / target / "ct2", output, args.jobs)
    source = ROOT / "hearth-tts-supertonic/src/main/cpp/voice/supertonic.cpp"
    original = source.read_text(encoding="utf-8")
    old = 'for(int i=0;i<4;++i)checked(api()->CreateSession(env,(dir+"/"+names[i]).c_str(),options,&sessions[i]));'
    if original.count(old) != 1: raise RuntimeError("Supertonic path preparation source changed; review desktop wide-path adaptation")
    # filesystem::path::c_str is wchar_t on Windows, char on Unix. No Android sources are changed.
    prepared = args.build_dir.resolve() / "supertonic-desktop.cpp"
    prepared.write_text('#include <filesystem>\n' + original.replace(old,
        'for(int i=0;i<4;++i){ const auto path=std::filesystem::u8path(dir+"/"+names[i]); checked(api()->CreateSession(env,path.c_str(),options,&sessions[i])); }'), encoding="utf-8")
    jni_source = (ROOT / "hearth-tts-supertonic/src/main/cpp/voice/voice_jni.cpp").read_text(encoding="utf-8")
    old_error = 'void fail(JNIEnv* env,const char* message){if(!env->ExceptionCheck())env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),message);}'
    if jni_source.count(old_error) != 1: raise RuntimeError("Supertonic JNI error preparation changed; review UTF-8 exception adaptation")
    prepared_jni = args.build_dir.resolve() / "supertonic-jni-desktop.cpp"
    prepared_jni.write_text('#include "desktop_jni.h"\n' + jni_source.replace(old_error, 'void fail(JNIEnv* env,const char* message){desktopFail(env,message);}'), encoding="utf-8")
    translation_jni = args.build_dir.resolve() / "translation-jni-desktop.cpp"
    translation_jni.write_text(prepare_translation((ROOT / "common-jni/src/main/cpp/translation/translation_jni.cpp").read_text(encoding="utf-8")), encoding="utf-8")
    for stage in [value for value in stages if value != "ct2"]:
        cache = args.build_dir.resolve() / target / stage
        command = ["cmake", "-S", str(ROOT / "scripts/desktop/native"), "-B", str(cache),
                   "-DCMAKE_BUILD_TYPE=Release", f"-DHEARTH_ROOT={ROOT}", f"-DHEARTH_STAGE={stage}",
                   f"-DHEARTH_LLAMA_ROOT={args.llama_root.resolve()}",
                   f"-DHEARTH_SUPERTONIC_SOURCE={prepared}",
                   f"-DHEARTH_SUPERTONIC_JNI_SOURCE={prepared_jni}",
                   f"-DHEARTH_TRANSLATION_JNI_SOURCE={translation_jni}",
                   f"-DHEARTH_DESKTOP_METAL={'ON' if args.metal else 'OFF'}"]
        if args.ort_root: command.append(f"-DHEARTH_ORT_ROOT={args.ort_root.resolve()}")
        if stage == "onnx": command.append(f"-DHEARTH_SHERPA_ROOT={sherpa}")
        if system == "macos": command.append(f"-DCMAKE_OSX_DEPLOYMENT_TARGET={'13.3' if stage == 'onnx' else '13.0'}")
        subprocess.run(command, check=True)
        subprocess.run(["cmake", "--build", str(cache), "--config", "Release", "--parallel", str(args.jobs)], check=True)
        subprocess.run(["cmake", "--install", str(cache), "--config", "Release", "--component", "hearth-desktop", "--prefix", str(output)], check=True)
        if stage == "onnx":
            installed = output / "libhearth_desktop_qwen3_asr.dylib"
            if not installed.is_file():
                raise RuntimeError("The Qwen3-ASR dylib was not staged into the bundle")
            require_exports(installed, "_Java_com_sal7one_common_1jni_desktop_DesktopQwenNative_", 7)
            linked = subprocess.check_output(["otool", "-L", str(installed)], text=True)
            if "@rpath/libonnxruntime.1.20.1.dylib" not in linked:
                raise RuntimeError("The Qwen3-ASR dylib does not link the staged ONNX Runtime 1.20.1")
    if "supertonic" in stages or "onnx" in stages:
        ort = args.ort_root.resolve()
        if system == "macos":
            # JAR resources cannot preserve symlinks. The linked versioned install name is sufficient.
            shutil.copyfile(ort / "lib/libonnxruntime.1.20.1.dylib", output / "libonnxruntime.1.20.1.dylib")
            (output / "libonnxruntime.dylib").unlink(missing_ok=True)
        elif system == "windows": shutil.copyfile(ort / "lib/onnxruntime.dll", output / "onnxruntime.dll")
        else: shutil.copyfile(ort / "lib/libonnxruntime.so", output / "libonnxruntime.so")
        for name, dest in [("LICENSE", "ONNX-LICENSE.txt"), ("ThirdPartyNotices.txt", "ONNX-ThirdPartyNotices.txt")]:
            shutil.copyfile(ort / name, output / dest)
    if "onnx" in stages:
        shutil.copyfile(args.sherpa_root / "LICENSE", output / "Sherpa-LICENSE.txt")
        notices = ROOT / "common-jni/src/main/assets/licenses/speech"
        aggregate = []
        for name in SHERPA_NOTICES:
            aggregate.append(f"===== {name} =====\n{(notices / name).read_text(encoding='utf-8').rstrip()}\n")
        (output / "Sherpa-Dependencies-NOTICES.txt").write_text("\n".join(aggregate), encoding="utf-8", newline="\n")
    shutil.copyfile(ROOT / "common-jni/src/main/cpp/whisper/upstream/LICENSE", output / "Whisper-LICENSE.txt")
    if "translation" in stages: shutil.copyfile(args.llama_root / "LICENSE", output / "llama-LICENSE.txt")
    shutil.copyfile(ROOT / "hearth-tts-supertonic/src/main/assets/licenses/voice/Supertonic-MIT.txt", output / "Supertonic-LICENSE.txt")
    if before != snapshot(): raise RuntimeError("Desktop native sources changed during compilation; rebuild the complete bundle")
    files = {p.name: sha(p) for p in sorted(output.iterdir()) if p.is_file() and p.name != "manifest.json"}
    upstreams = {"whisperSourceTreeSha256": WHISPER_PIN, "llamaRevision": LLAMA_PIN, "onnxRuntime": "1.20.1"}
    if "onnx" in stages:
        upstreams.update({
            "sherpaRevision": SHERPA_PIN,
            "sherpaTreeRevision": sherpa_tree,
            "sherpaSourceTreeSha256": sherpa_source_tree,
            "sherpaInputsSha256": hashlib.sha256(json.dumps(SHERPA_INPUTS, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
            "sherpaVersion": sherpa_version,
        })
    if "ct2" in stages:
        upstreams.update({
            "ct2Revision": CT2_PIN,
            "sentencepieceRevision": SENTENCEPIECE_PIN,
            "ct2SourcesSha256": ct2_sources,
        })
    manifest = {"schemaVersion": 1, "platform": target, "files": files, "sources": before,
                "sourceSetSha256": hashlib.sha256(json.dumps(before, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
                "upstreams": upstreams,
                "backends": ["cpu", "metal"] if args.metal else ["cpu"],
                "compiledMetalSupport": args.metal, "metalSelectionRequiresUsableDevice": True,
                "modelWeightsIncluded": False}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Desktop native bundle staged at {output}")


if __name__ == "__main__":
    main()
