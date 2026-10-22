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
TRANSCRIBEPSY_PIN = "364ec36130dc87d2cd46e596598268a6e3313751"
NEMO_PIN = "ffa38cb2408f1e832a36d46fef5e3e1e80d07e6c"
MING_WASM_PIN = "7252aa537ab2ef4c4c2c58088116ecb754f654d6"
MING_FUSOR_AI_PIN = "7dda3bfb4015d76bed053456ad371f1e4f487ad9"
# OmniVoice is a self-contained C++/ggml family. Sources are a developer checkout at
# these exact revisions; the desktop stage links its own static ggml copy and never
# duplicates ggml or ONNX Runtime into another dylib.
OMNIVOICE_PIN = "1a468d8b9d65b3544ca1004d2d7c05dfc65d1f3e"
OMNIVOICE_GGML_PIN = "cc5df49a6b878db2b68b9a7b45976e279594627d"
# Pinned official Vosk macOS release. The app never downloads a native runtime:
# this runs at build time only, verifies the release ZIP and each member, thins
# the universal2 slice to arm64 and records both digests in the manifest.
VOSK_RELEASE = "0.3.42"
VOSK_ZIP_URL = f"https://github.com/alphacep/vosk-api/releases/download/v{VOSK_RELEASE}/vosk-osx-{VOSK_RELEASE}.zip"
VOSK_ZIP_SHA256 = "65395f196c9d0583d79949142b25560acaf9c295f36284e18433097f3adb0ea1"
VOSK_DYLIB_SHA256 = "82fdfba0dde392a7dbba70f9c1a17f6e3da27a50444a9f53939508525a1e6fdf"
VOSK_ARM64_SHA256 = "6baf792c6eefe9c19c5203afeea368d2a8cda73b7142863228c78dc6ddb274d3"
VOSK_HEADER_SHA256 = "3c53864a8ed10d77e3c456f6371ea8e361414ad478314378309168d8add5325c"
VOSK_NOTICES = """===== Vosk / Kaldi (Apache-2.0) =====
Vosk runtime: official vosk-osx-0.3.42 release asset (Apache-2.0), downloaded and
verified at build time from https://github.com/alphacep/vosk-api/releases/tag/v0.3.42
Release ZIP SHA-256: 65395f196c9d0583d79949142b25560acaf9c295f36284e18433097f3adb0ea1
Universal libvosk.dylib SHA-256: 82fdfba0dde392a7dbba70f9c1a17f6e3da27a50444a9f53939508525a1e6fdf
Staged arm64 slice SHA-256: 6baf792c6eefe9c19c5203afeea368d2a8cda73b7142863228c78dc6ddb274d3
Vosk bundles Kaldi and OpenFST statically; only Accelerate, libc++ and libSystem
are dynamic dependencies. Model archives are Vosk/Kaldi models (Apache-2.0) from
https://alphacephei.com/vosk/models and are pinned by archive and tree digest.
"""
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
                 "common-jni/src/main/cpp/speech/backends/vosk_backend.cpp",
                 "common-jni/src/main/cpp/vosk/vosk_api.h",
                 "common-jni/src/main/cpp/vosk/vosk_engine.cpp",
                 "scripts/desktop/native/vosk.exports",
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
                 "scripts/translation/ct2/build-runtime.sh",
                 "common-jni/src/main/cpp/speech/backend_versions.h",
                 "scripts/speech/build-runtimes.sh",
                 "scripts/speech/transcribepsy.cmake",
                 "scripts/speech/nemo-inject.cmake",
                 "hearth-tts-moss/src/main/cpp/moss.cpp",
                 "hearth-tts-moss/src/main/cpp/vendor/moss.h",
                 "hearth-tts-omnivoice/src/main/cpp/vendor/omnivoice.h",
                 "common-jni/src/main/cpp/ming/ffi/Cargo.toml",
                 "common-jni/src/main/cpp/ming/ffi/Cargo.lock",
                 "common-jni/src/main/cpp/ming/ffi/src/lib.rs",
                 "common-jni/src/main/cpp/ming/ffi/include/ming.h",
                 "common-jni/src/main/cpp/ming/jni/Cargo.toml",
                 "common-jni/src/main/cpp/ming/jni/Cargo.lock",
                 "common-jni/src/main/cpp/ming/jni/src/lib.rs",
                 "scripts/desktop/build-ming-runtime.sh"]:
        paths.append(ROOT / name)
    paths.extend((ROOT / "hearth-tts-supertonic/src/main/cpp/voice").glob("*.cpp"))
    paths.append(ROOT / "hearth-tts-supertonic/src/main/cpp/voice/supertonic.h")
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(set(paths))}


def source_tree_sha_files(root):
    """Content digest of a pinned checkout's tracked files, skipping submodule gitlinks."""
    names = subprocess.check_output(["git", "-C", str(root), "ls-files", "-z"], text=False).split(b"\0")
    entries = {}
    for raw in names:
        if not raw:
            continue
        relative = os.fsdecode(raw)
        path = root / relative
        if path.is_dir():
            # A gitlink is recorded separately by the caller via its own revision.
            continue
        entries[relative] = "symlink:" + os.readlink(path) if path.is_symlink() else sha(path)
    return hashlib.sha256(json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


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


def speech_backend_install(names, output, jobs):
    """Build the pinned speech backends with the host recipe and stage their dylibs."""
    environment = os.environ.copy()
    environment["HEARTH_BUILD_JOBS"] = str(jobs)
    checkouts = {"transcribepsy": ("transcribepsy", TRANSCRIBEPSY_PIN), "nemotron": ("nemo", NEMO_PIN)}
    revisions = {}
    source_root = ROOT / "build/speech-runtimes/src"
    for name in names:
        subprocess.run(["bash", str(ROOT / "scripts/speech/build-runtimes.sh"), name, "host"], check=True, env=environment)
        directory, pin = checkouts[name]
        head = subprocess.check_output(["git", "-C", str(source_root / directory), "rev-parse", "HEAD"], text=True).strip()
        if head != pin:
            raise RuntimeError(f"{directory} must be checked out at {pin}, found {head}")
        dylib = ROOT / "build/speech-runtimes/host/lib" / f"libhearth_{name}.dylib"
        if not dylib.is_file():
            raise RuntimeError(f"The host speech build did not produce {dylib.name}")
        require_exports(dylib, "_hearth_speech_backend_v1", 1)
        linked = subprocess.check_output(["otool", "-L", str(dylib)], text=True).splitlines()[1:]
        for line in linked:
            entry = line.strip().split(" ")[0] if line.strip() else ""
            if entry.startswith("/") and not (entry.startswith("/usr/lib/") or entry.startswith("/System/Library/")):
                raise RuntimeError(f"{dylib.name} must not depend on a non-system dylib: {entry}")
        shutil.copyfile(dylib, output / dylib.name)
        if name == "transcribepsy":
            revisions["transcribepsyRevision"] = TRANSCRIBEPSY_PIN
        else:
            revisions["nemoRevision"] = NEMO_PIN
            revisions["sentencepieceRevision"] = SENTENCEPIECE_PIN
    if "nemotron" in names:
        head = subprocess.check_output(["git", "-C", str(source_root / "sentencepiece"), "rev-parse", "HEAD"], text=True).strip()
        if head != SENTENCEPIECE_PIN:
            raise RuntimeError(f"sentencepiece must be checked out at {SENTENCEPIECE_PIN}, found {head}")
    notices = []
    candidates = []
    if "transcribepsy" in names:
        candidates += [
            (source_root / "transcribepsy/LICENSE", "TranscribePsy/parakeet LICENSE (Apache-2.0)"),
            (source_root / "transcribepsy/NOTICE", "TranscribePsy NOTICE"),
            (source_root / "transcribepsy/ggml/LICENSE", "TranscribePsy ggml LICENSE (MIT)"),
        ]
    if "nemotron" in names:
        candidates += [
            (source_root / "nemo/LICENSE", "NeMo-Speech.cpp LICENSE (Apache-2.0)"),
            (source_root / "nemo/NOTICE", "NeMo-Speech.cpp NOTICE"),
            (source_root / "nemo/THIRD_PARTY_NOTICES.md", "NeMo-Speech.cpp third-party notices"),
            (source_root / "nemo/ggml/LICENSE", "NeMo-Speech.cpp ggml LICENSE (MIT)"),
            (source_root / "sentencepiece/LICENSE", "SentencePiece LICENSE (Apache-2.0)"),
        ]
    for path, label in candidates:
        if path.is_file():
            notices.append(f"===== {label} =====\n{path.read_text(encoding='utf-8').rstrip()}\n")
    (output / "Speech-Backends-NOTICES.txt").write_text("\n".join(notices), encoding="utf-8", newline="\n")
    return revisions


def vosk_install(cache, output):
    """Fetch, verify and stage the pinned official Vosk runtime; never at app runtime."""
    archive = cache / f"vosk-osx-{VOSK_RELEASE}.zip"
    if not archive.is_file() or sha(archive) != VOSK_ZIP_SHA256:
        cache.mkdir(parents=True, exist_ok=True)
        archive.unlink(missing_ok=True)
        subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error", "--output",
                        str(archive), VOSK_ZIP_URL], check=True)
        if sha(archive) != VOSK_ZIP_SHA256:
            archive.unlink(missing_ok=True)
            raise RuntimeError("The Vosk release archive does not match its pinned SHA-256")
    members = set(subprocess.check_output(["unzip", "-Z1", str(archive)], text=True).split())
    expected = {f"vosk-osx-{VOSK_RELEASE}/", f"vosk-osx-{VOSK_RELEASE}/libvosk.dylib", f"vosk-osx-{VOSK_RELEASE}/vosk_api.h"}
    if members != expected:
        raise RuntimeError("The Vosk release archive layout changed: " + ", ".join(sorted(members)))
    extracted = cache / f"vosk-osx-{VOSK_RELEASE}"
    shutil.rmtree(extracted, ignore_errors=True)
    subprocess.run(["unzip", "-q", str(archive), "-d", str(cache)], check=True)
    dylib, header = extracted / "libvosk.dylib", extracted / "vosk_api.h"
    if sha(dylib) != VOSK_DYLIB_SHA256 or sha(header) != VOSK_HEADER_SHA256:
        raise RuntimeError("The Vosk release members do not match their pinned SHA-256")
    thin = cache / "libvosk-arm64.dylib"
    subprocess.run(["lipo", str(dylib), "-thin", "arm64", "-output", str(thin)], check=True)
    if sha(thin) != VOSK_ARM64_SHA256:
        raise RuntimeError("The Vosk arm64 slice does not match its pinned SHA-256")
    shutil.copyfile(thin, output / "libvosk.dylib")
    (output / "Vosk-NOTICES.txt").write_text(VOSK_NOTICES, encoding="utf-8", newline="\n")
    return {"voskRelease": VOSK_RELEASE, "voskZipSha256": VOSK_ZIP_SHA256,
            "voskDylibSha256": VOSK_DYLIB_SHA256, "voskArm64DylibSha256": VOSK_ARM64_SHA256,
            "voskHeaderSha256": VOSK_HEADER_SHA256}


def ming_install(output):
    """Build the experimental Ming JNI runtime from the pinned Rust source."""
    script = ROOT / "scripts/desktop/build-ming-runtime.sh"
    environment = os.environ.copy()
    environment["HEARTH_MING_OUTPUT"] = str(output)
    subprocess.run(["bash", str(script)], check=True, env=environment)
    dylib = output / "libming_jni.dylib"
    if not dylib.is_file():
        raise RuntimeError("The Ming runtime build did not produce libming_jni.dylib")
    require_exports(dylib, "_Java_com_sal7one_common_1jni_MingNative_", 3)
    linked = subprocess.check_output(["otool", "-L", str(dylib)], text=True).splitlines()[1:]
    for line in linked:
        entry = line.strip().split(" ")[0] if line.strip() else ""
        if entry in (str(dylib), "@rpath/libming_jni.dylib"):
            continue
        if entry.startswith("/") and not (entry.startswith("/usr/lib/") or entry.startswith("/System/Library/")):
            raise RuntimeError(f"libming_jni.dylib has a non-system dependency: {entry}")
    notice = """===== ming-wasm / fusor-ai (MIT) =====
ming-wasm 7252aa537ab2ef4c4c2c58088116ecb754f654d6 (MIT, (c) 2026 Fusor)
fusor-ai 7dda3bfb4015d76bed053456ad371f1e4f487ad9, crates fusor-ai-* 0.1.2 (MIT)
https://github.com/fusor-rs/ming-wasm
https://github.com/fusor-rs/fusor-ai
The shipped JNI binary also includes its MIT/Apache-2.0 Rust dependency closure.
===== Ming-3.0-tts-0.5B (MIT) =====
Model inclusionAI/Ming-3.0-tts-0.5B revision e5bd8da31ecf1b9276cdbdda821fa891068f1368.
The precompiled fusor-ai bundle is fusorlabs/ming-3.0-tts-0.5b-web revision 62588575d91dceb33822fe1e3925e748393c6b3e.
Synthetic speech must be labelled as synthetic. Model weights are downloaded separately and never committed.
"""
    (output / "Ming-NOTICES.txt").write_text(notice, encoding="utf-8", newline="\n")
    return {"mingWasmRevision": MING_WASM_PIN, "mingFusorAiRevision": MING_FUSOR_AI_PIN,
            "mingJniSha256": sha(dylib)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=["all", "whisper", "translation", "supertonic", "onnx", "ct2", "moss", "omnivoice", "backend", "vosk", "ming"], default="all")
    parser.add_argument("--speech-backends", default="transcribepsy,nemotron", help="Comma-separated speech backends to stage (transcribepsy, nemotron)")
    parser.add_argument("--llama-root", type=Path, default=ROOT / "build/translation-runtime/llama.cpp")
    parser.add_argument("--sherpa-root", type=Path, default=ROOT / "build/desktop-dependencies/sherpa-onnx")
    parser.add_argument("--omnivoice-root", type=Path, default=Path(os.environ.get("HEARTH_OMNIVOICE_ROOT", str(ROOT / "build/desktop-dependencies/omnivoice.cpp"))))
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
            stages.extend(["onnx", "ct2", "backend", "moss", "omnivoice", "vosk", "ming"])
        else:
            print(f"Skipping Qwen3-ASR (onnx), CTranslate2 (ct2), speech backends (backend), MOSS TTS (moss) and OmniVoice TTS (omnivoice): built only for macos-arm64; host is {target}", file=sys.stderr)
    if "onnx" in stages and system != "macos":
        parser.error("The Qwen3-ASR (sherpa-onnx) stage is built for macOS arm64 only")
    if "onnx" in stages and arch != "arm64":
        parser.error("The Qwen3-ASR (sherpa-onnx) stage is built for macOS arm64 only")
    if "ct2" in stages and (system, arch) != ("macos", "arm64"):
        parser.error("The CTranslate2 (ct2) stage is built for macOS arm64 only")
    if "backend" in stages and (system, arch) != ("macos", "arm64"):
        parser.error("The desktop speech backend stage is built for macOS arm64 only")
    if "moss" in stages and (system, arch) != ("macos", "arm64"):
        parser.error("The MOSS TTS stage is built for macOS arm64 only")
    if "omnivoice" in stages and (system, arch) != ("macos", "arm64"):
        parser.error("The OmniVoice TTS stage is built for macOS arm64 only")
    if "ming" in stages and (system, arch) != ("macos", "arm64"):
        parser.error("The Ming TTS runtime is built for macOS arm64 in this stage")
    if "vosk" in stages and (system, arch) != ("macos", "arm64"):
        parser.error("The Vosk speech backend stage is built for macOS arm64 only")
    speech_backends = [value.strip() for value in args.speech_backends.split(",") if value.strip()]
    if any(value not in ("transcribepsy", "nemotron") for value in speech_backends):
        parser.error("--speech-backends accepts transcribepsy and/or nemotron")
    if "backend" in stages and not speech_backends:
        parser.error("--speech-backends must name at least one backend")
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
    omnivoice_tree = None
    omnivoice_ggml_tree = None
    if "omnivoice" in stages:
        root = args.omnivoice_root.resolve()
        if not (root / "src/omnivoice.cpp").is_file() or not (root / "ggml/CMakeLists.txt").is_file():
            raise RuntimeError(f"--omnivoice-root must name a clean omnivoice.cpp checkout at {OMNIVOICE_PIN}; {root} is incomplete")
        pin = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
        if pin != OMNIVOICE_PIN: raise RuntimeError(f"omnivoice.cpp must be checked out at {OMNIVOICE_PIN}, found {pin}")
        ggml_pin = subprocess.check_output(["git", "-C", str(root / "ggml"), "rev-parse", "HEAD"], text=True).strip()
        if ggml_pin != OMNIVOICE_GGML_PIN: raise RuntimeError(f"omnivoice.cpp/ggml must be checked out at {OMNIVOICE_GGML_PIN}, found {ggml_pin}")
        for folder, label in ((root, "omnivoice.cpp"), (root / "ggml", "omnivoice.cpp/ggml")):
            changes = subprocess.check_output(["git", "-C", str(folder), "status", "--porcelain"], text=True)
            if changes.strip(): raise RuntimeError(f"{label} source checkout has local changes")
        omnivoice_tree = source_tree_sha_files(root)
        omnivoice_ggml_tree = source_tree_sha_files(root / "ggml")
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
    backend_revisions = None
    vosk_upstreams = None
    if "backend" in stages:
        backend_revisions = speech_backend_install(speech_backends, output, args.jobs)
    ming_revisions = None
    if "ming" in stages:
        ming_revisions = ming_install(output)
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
    for stage in [value for value in stages if value not in ("ct2", "ming")]:
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
        if stage == "omnivoice":
            command.append(f"-DHEARTH_OMNIVOICE_SOURCE={args.omnivoice_root.resolve()}")
        if stage == "moss":
            sentencepiece = ROOT / "build/ct2-runtime/src/sentencepiece"
            if not (sentencepiece / "src/sentencepiece_processor.h").is_file():
                raise RuntimeError("The MOSS stage needs the pinned SentencePiece source from the CT2 build; run --stage ct2 first")
            command.append(f"-DHEARTH_MOSS_CPP={ROOT / 'hearth-tts-moss/src/main/cpp'}")
            command.append(f"-DHEARTH_SENTENCEPIECE_SOURCE={sentencepiece}")
        if system == "macos": command.append(f"-DCMAKE_OSX_DEPLOYMENT_TARGET={'13.3' if stage == 'onnx' else '13.0'}")
        stage_env = dict(os.environ)
        if stage == "omnivoice":
            # ggml embeds `git rev-parse --short HEAD`; without a fixed abbreviation the
            # same pin produced cc5df49 in a full checkout and cc5df49a in a shallow one.
            stage_env.update({"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "core.abbrev", "GIT_CONFIG_VALUE_0": "7"})
        subprocess.run(command, check=True, env=stage_env)
        subprocess.run(["cmake", "--build", str(cache), "--config", "Release", "--parallel", str(args.jobs)], check=True, env=stage_env)
        subprocess.run(["cmake", "--install", str(cache), "--config", "Release", "--component", "hearth-desktop", "--prefix", str(output)], check=True)
        if stage == "backend":
            installed_backend = output / "libhearth_desktop_backend_speech.dylib"
            if not installed_backend.is_file():
                raise RuntimeError("The desktop speech backend bridge was not staged")
            require_exports(installed_backend, "_Java_com_sal7one_common_1jni_desktop_DesktopBackendSpeechNative_", 7)
        if stage == "vosk":
            installed_vosk = output / "libhearth_vosk.dylib"
            if not installed_vosk.is_file():
                raise RuntimeError("The Vosk speech backend dylib was not staged")
            require_exports(installed_vosk, "_hearth_speech_backend_v1", 1)
            linked = subprocess.check_output(["otool", "-L", str(installed_vosk)], text=True).splitlines()[1:]
            for line in linked:
                entry = line.strip().split(" ")[0] if line.strip() else ""
                if "libvosk" in entry:
                    raise RuntimeError("libhearth_vosk.dylib must dlopen libvosk.dylib, not link it")
            vosk_upstreams = vosk_install(args.build_dir.resolve() / target / "vosk", output)
        if stage == "moss":
            installed_moss = output / "libhearth_desktop_moss.dylib"
            if not installed_moss.is_file():
                raise RuntimeError("The MOSS desktop voice dylib was not staged")
            require_exports(installed_moss, "_Java_com_sal7one_common_1jni_voice_MossNative_", 6)
            linked = subprocess.check_output(["otool", "-L", str(installed_moss)], text=True)
            if "@rpath/libonnxruntime.1.20.1.dylib" not in linked:
                raise RuntimeError("The MOSS dylib does not link the staged ONNX Runtime 1.20.1")
            notices = []
            for path in sorted((ROOT / "hearth-tts-moss/src/main/assets/licenses/moss").glob("*")):
                if path.is_file():
                    notices.append(f"===== MOSS asset {path.name} =====\n{path.read_text(encoding='utf-8', errors='replace').rstrip()}\n")
            (output / "Moss-Dependencies-NOTICES.txt").write_text("\n".join(notices), encoding="utf-8", newline="\n")
        if stage == "omnivoice":
            installed_omnivoice = output / "libhearth_desktop_omnivoice.dylib"
            if not installed_omnivoice.is_file():
                raise RuntimeError("The OmniVoice desktop voice dylib was not staged")
            require_exports(installed_omnivoice, "_Java_com_sal7one_common_1jni_voice_OmniVoiceNative_", 6)
            linked = subprocess.check_output(["otool", "-L", str(installed_omnivoice)], text=True).splitlines()[1:]
            for line in linked:
                entry = line.strip().split(" ")[0] if line.strip() else ""
                if not entry: continue
                if entry.startswith(("/usr/lib/", "/System/Library/Frameworks/", "@rpath/libhearth_desktop_omnivoice.dylib")):
                    continue
                raise RuntimeError(f"The OmniVoice dylib has a non-system dependency: {entry}")
            notices = ["===== OmniVoice pretrained weights (downloaded separately, never bundled) =====\n"
                       "OmniVoice generator weights: CC-BY-NC-4.0 (k2-fsa/OmniVoice).\n"
                       "Audio codec weights: Boson Higgs Audio 2 Community License with incorporated Meta Llama 3 terms.\n"
                       "The user downloads the pinned Q8 GGUF files; they are not redistributed with Hearth.\n"]
            for name, label in (("LICENSE", "omnivoice.cpp"), ("GGML-LICENSE", "ggml")):
                path = ROOT / "hearth-tts-omnivoice/src/main/assets/licenses/omnivoice" / name
                if not path.is_file(): raise RuntimeError(f"Missing pinned {label} notice: {path}")
                notices.append(f"===== {label} ({name}) =====\n{path.read_text(encoding='utf-8').rstrip()}\n")
            (output / "OmniVoice-Dependencies-NOTICES.txt").write_text("\n".join(notices), encoding="utf-8", newline="\n")
        if stage == "onnx":
            installed = output / "libhearth_desktop_qwen3_asr.dylib"
            if not installed.is_file():
                raise RuntimeError("The Qwen3-ASR dylib was not staged into the bundle")
            require_exports(installed, "_Java_com_sal7one_common_1jni_desktop_DesktopQwenNative_", 7)
            symbols = subprocess.check_output(["nm", "-gU", str(installed)], text=True)
            for symbol in ("_hearth_speech_backend_v1", "_hearth_moonshine_backend_v1",
                           "_hearth_omnilingual_backend_v1", "_hearth_phonon_backend_v1"):
                if symbol not in symbols.split():
                    raise RuntimeError(f"The sherpa dylib does not export {symbol}")
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
    if backend_revisions:
        upstreams.update(backend_revisions)
    if vosk_upstreams:
        upstreams.update(vosk_upstreams)
    if ming_revisions:
        upstreams.update(ming_revisions)
    if "moss" in stages:
        upstreams.update({
            "mossRuntimeSourceSha256": sha(ROOT / "hearth-tts-moss/src/main/cpp/moss.cpp"),
            "sentencepieceRevision": SENTENCEPIECE_PIN,
        })
    if "omnivoice" in stages:
        upstreams.update({
            "omnivoiceRevision": OMNIVOICE_PIN,
            "omnivoiceGgmlRevision": OMNIVOICE_GGML_PIN,
            "omnivoiceSourceTreeSha256": omnivoice_tree,
            "omnivoiceGgmlTreeSha256": omnivoice_ggml_tree,
            "omnivoiceJniSha256": sha(output / "libhearth_desktop_omnivoice.dylib"),
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
