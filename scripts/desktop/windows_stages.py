"""Windows x64 builders for the engine stages that use shell recipes on macOS.

The macOS recipes (bash, libtool, otool, nm) and the Android recipes they share are
left untouched: several of those files are hashed into the Android runtime receipts.
Each function here builds the same pinned sources with MSVC, statically linked to the
C runtime like Hearth's other Windows adapters, and checks the staged DLL's exports and
imports from its PE headers.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
CPP = ROOT / "common-jni/src/main/cpp"
COMPAT = ROOT / "desktop-runtime/src/main/cpp/windows"
PROJECTS = ROOT / "scripts/desktop/native/windows"
# Windows components every supported release provides. Anything else must be a verified bundle file.
SYSTEM_DLLS = {"kernel32.dll", "kernelbase.dll", "ntdll.dll", "advapi32.dll", "user32.dll", "ole32.dll",
               "oleaut32.dll", "combase.dll", "rpcrt4.dll", "bcrypt.dll", "bcryptprimitives.dll", "ucrtbase.dll",
               "ws2_32.dll", "shlwapi.dll", "shell32.dll", "userenv.dll", "crypt32.dll", "secur32.dll",
               "dbghelp.dll", "psapi.dll", "synchronization.dll", "powrprof.dll", "winmm.dll", "iphlpapi.dll",
               "ncrypt.dll", "version.dll", "gdi32.dll", "msvcrt.dll"}
MSVC_FLAGS = ["-A", "x64", "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_POLICY_DEFAULT_CMP0091=NEW",
              "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded", "-DCMAKE_POLICY_VERSION_MINIMUM=3.10",
              "-DBUILD_SHARED_LIBS=OFF"]

_crt = importlib.util.spec_from_file_location("stage_windows_crt", Path(__file__).with_name("stage-windows-crt.py"))
_crt_module = importlib.util.module_from_spec(_crt)
_crt.loader.exec_module(_crt_module)
pe_imports = _crt_module.pe_imports


def run(command, env=None, cwd=None):
    subprocess.run([str(part) for part in command], check=True, env=env, cwd=cwd)


def environment(extra=None):
    """MSBuild compiles one file at a time per project; /MP lets cl use the idle cores."""
    values = dict(os.environ)
    values["CL"] = (values.get("CL", "") + " /MP").strip()
    cargo = Path.home() / ".cargo/bin"
    if cargo.is_dir() and str(cargo).lower() not in values.get("PATH", "").lower():
        values["PATH"] = values.get("PATH", "") + os.pathsep + str(cargo)
    values.update(extra or {})
    return values


def checkout(url, directory, pin, submodules=()):
    """A dedicated, disposable build checkout at one exact revision with upstream line endings."""
    directory = Path(directory)
    if not (directory / ".git").is_dir():
        if directory.exists():
            raise RuntimeError(f"Refusing to overwrite a non-checkout dependency directory: {directory}")
        directory.parent.mkdir(parents=True, exist_ok=True)
        run(["git", "init", "--quiet", directory])
        run(["git", "-C", directory, "remote", "add", "origin", url])
    run(["git", "-C", directory, "config", "core.autocrlf", "false"])
    present = subprocess.run(["git", "-C", str(directory), "cat-file", "-e", f"{pin}^{{commit}}"],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not present:
        run(["git", "-C", directory, "fetch", "--depth", "1", "origin", pin])
    run(["git", "-C", directory, "checkout", "--quiet", "--detach", "--force", pin])
    head = subprocess.check_output(["git", "-C", str(directory), "rev-parse", "HEAD"], text=True).strip()
    if head != pin:
        raise RuntimeError(f"{directory.name} must be checked out at {pin}, found {head}")
    for name in submodules:
        run(["git", "-C", directory, "-c", "core.autocrlf=false", "submodule", "update", "--init", "--recursive", "--depth", "1", name])
    return directory


def pe_exports(path):
    """Exported names of an x64 PE image, read from its export directory."""
    data = Path(path).read_bytes()
    def unpack(fmt, offset):
        if offset < 0 or offset + struct.calcsize(fmt) > len(data):
            raise ValueError("Truncated PE image")
        return struct.unpack_from(fmt, data, offset)
    if data[:2] != b"MZ":
        raise ValueError("Not a PE image")
    pe, = unpack("<I", 0x3c)
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("Invalid PE signature")
    machine, sections = unpack("<HH", pe + 4)
    optional_size, = unpack("<H", pe + 20)
    optional = pe + 24
    magic, = unpack("<H", optional)
    if machine != 0x8664 or magic != 0x20b:
        raise ValueError("Expected an x64 PE32+ image")
    layout = [unpack("<IIII", optional + optional_size + index * 40 + 8) for index in range(sections)]
    def offset(rva):
        for size, start, raw_size, raw in layout:
            if start <= rva < start + max(size, raw_size):
                return raw + rva - start
        raise ValueError("PE export points outside file data")
    rva, size = unpack("<II", optional + 112)
    if not rva or not size:
        return []
    count, = unpack("<I", offset(rva) + 24)
    names_rva, = unpack("<I", offset(rva) + 32)
    names = []
    for index in range(min(count, 65536)):
        name_rva, = unpack("<I", offset(names_rva) + index * 4)
        start = offset(name_rva)
        names.append(data[start:data.index(b"\0", start)].decode("ascii"))
    return names


def require_exports(dll, prefix, expected):
    found = [name for name in pe_exports(dll) if name.startswith(prefix)]
    if len(found) != expected:
        raise RuntimeError(f"Desktop JNI exports changed: expected {expected} {prefix} symbols in {Path(dll).name}, found {len(found)}")


def require_symbols(dll, symbols):
    exported = set(pe_exports(dll))
    missing = [symbol for symbol in symbols if symbol not in exported]
    if missing:
        raise RuntimeError(f"{Path(dll).name} does not export: {', '.join(missing)}")


def require_system_imports(dll, bundled=()):
    """Every import is a Windows component or an explicitly named, separately verified bundle file."""
    allowed = {name.lower() for name in bundled}
    foreign = [name for name in pe_imports(Path(dll).read_bytes())
               if name not in SYSTEM_DLLS and name not in allowed and not name.startswith(("api-ms-win-", "ext-ms-win-"))]
    if foreign:
        raise RuntimeError(f"{Path(dll).name} has a non-system dependency: {', '.join(foreign)}")


def built(directory, name):
    """One Release artifact below a Visual Studio build tree."""
    matches = [path for path in Path(directory).rglob(name) if "Release" in path.parts or path.parent == Path(directory)]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one built {name} below {directory}, found {len(matches)}")
    return matches[0]


def notices(output, name, candidates):
    aggregate = [f"===== {label} =====\n{path.read_text(encoding='utf-8', errors='replace').rstrip()}\n"
                 for path, label in candidates if path.is_file()]
    (output / name).write_text("\n".join(aggregate), encoding="utf-8", newline="\n")


def speech_backends(names, output, jobs, pins):
    """TranscribePsy and Nemotron backends as self-contained DLLs behind the shared bridge."""
    work = ROOT / "build/speech-runtimes"
    source_root = work / "src"
    env = environment()
    revisions = {}
    if "transcribepsy" in names:
        source = checkout("https://github.com/tether-ai-research/qvac-transcribepsy.git", source_root / "transcribepsy", pins["transcribepsy"])
        build = work / "windows-x64/transcribepsy"
        run(["cmake", "-S", PROJECTS / "transcribepsy", "-B", build, *MSVC_FLAGS,
             f"-DTRANSCRIBEPSY_SOURCE={source.as_posix()}", f"-DHEARTH_CPP={CPP.as_posix()}",
             f"-DHEARTH_WINDOWS_COMPAT={COMPAT.as_posix()}"], env=env)
        run(["cmake", "--build", build, "--config", "Release", "--target", "hearth_transcribepsy", "--parallel", jobs], env=env)
        stage_backend(built(build, "hearth_transcribepsy.dll"), output)
        revisions["transcribepsyRevision"] = pins["transcribepsy"]
    if "nemotron" in names:
        nemo = checkout("https://github.com/NVIDIA/NeMo-Speech.cpp.git", source_root / "nemo", pins["nemotron"], submodules=["ggml"])
        sentencepiece = checkout("https://github.com/google/sentencepiece.git", source_root / "sentencepiece", pins["sentencepiece"])
        spm_build = work / "windows-x64/sentencepiece"
        run(["cmake", "-S", sentencepiece, "-B", spm_build, *MSVC_FLAGS, "-DSPM_ENABLE_SHARED=OFF",
             "-DSPM_BUILD_TEST=OFF", "-DSPM_ENABLE_TCMALLOC=OFF", "-DSPM_ENABLE_MSVC_MT_BUILD=ON"], env=env)
        run(["cmake", "--build", spm_build, "--config", "Release", "--target", "sentencepiece-static", "--parallel", jobs], env=env)
        # Upstream's shared C++ target would export STL/ggml; embed it privately in the one ABI DLL,
        # exactly as the Android and macOS recipe does in this disposable checkout.
        asr = nemo / "src/asr/CMakeLists.txt"
        text = asr.read_text(encoding="utf-8")
        shared = "add_library(nemo_speech_asr SHARED ${ASR_SOURCES})"
        if text.count(shared) != 1:
            raise RuntimeError("Pinned NeMo-Speech.cpp ASR target changed; review the static embedding")
        asr.write_text(text.replace(shared, "add_library(nemo_speech_asr STATIC ${ASR_SOURCES})"), encoding="utf-8", newline=chr(10))
        build = work / "windows-x64/nemo"
        run(["cmake", "-S", nemo, "-B", build, *MSVC_FLAGS,
             f"-DCMAKE_PROJECT_nemo_speech_INCLUDE={(PROJECTS / 'nemotron-inject.cmake').as_posix()}",
             f"-DHEARTH_CPP={CPP.as_posix()}", f"-DHEARTH_WINDOWS_COMPAT={COMPAT.as_posix()}",
             "-DGGML_NATIVE=OFF", "-DGGML_OPENMP=OFF", "-DGGML_CPU_KLEIDIAI=OFF", "-DGGML_METAL=OFF", "-DGGML_CUDA=OFF",
             "-DNEMO_SPEECH_GGML_PATCHED=OFF", "-DNEMO_SPEECH_BUILD_ASR=ON", "-DNEMO_SPEECH_BUILD_DIAR=OFF",
             "-DNEMO_SPEECH_BUILD_TTS=OFF", "-DNEMO_SPEECH_BUILD_NMT=OFF", "-DNEMO_SPEECH_BUILD_CLI=OFF",
             "-DNEMO_SPEECH_BUILD_MIC_CAPTURE=OFF", "-DNEMO_SPEECH_BUILD_HTTP=OFF", "-DNEMO_SPEECH_BUILD_GRPC=OFF",
             "-DNEMO_SPEECH_BUILD_TESTS=OFF", "-DNEMO_SPEECH_BUILD_EXAMPLES=OFF", "-DNEMO_SPEECH_BUILD_TOOLS=OFF",
             f"-DSENTENCEPIECE_LIB={built(spm_build, 'sentencepiece.lib').as_posix()}",
             f"-DSENTENCEPIECE_INCLUDE_DIR={(sentencepiece / 'src').as_posix()}"], env=env)
        run(["cmake", "--build", build, "--config", "Release", "--target", "nemo_speech_asr_c", "--parallel", jobs], env=env)
        stage_backend(built(build, "hearth_nemotron.dll"), output)
        revisions["nemoRevision"] = pins["nemotron"]
        revisions["sentencepieceRevision"] = pins["sentencepiece"]
    candidates = []
    if "nemotron" in names:
        candidates += [(source_root / "nemo/LICENSE", "NeMo-Speech.cpp LICENSE (Apache-2.0)"),
                       (source_root / "nemo/NOTICE", "NeMo-Speech.cpp NOTICE"),
                       (source_root / "nemo/THIRD_PARTY_NOTICES.md", "NeMo-Speech.cpp third-party notices"),
                       (source_root / "nemo/ggml/LICENSE", "NeMo-Speech.cpp ggml LICENSE (MIT)"),
                       (source_root / "sentencepiece/LICENSE", "SentencePiece LICENSE (Apache-2.0)")]
    if "transcribepsy" in names:
        candidates += [(source_root / "transcribepsy/LICENSE", "TranscribePsy/parakeet LICENSE (Apache-2.0)"),
                       (source_root / "transcribepsy/NOTICE", "TranscribePsy NOTICE"),
                       (source_root / "transcribepsy/ggml/LICENSE", "TranscribePsy ggml LICENSE (MIT)")]
    notices(output, "Speech-Backends-NOTICES.txt", candidates)
    return revisions


def stage_backend(dll, output):
    require_symbols(dll, ["hearth_speech_backend_v1"])
    require_system_imports(dll)
    shutil.copyfile(dll, output / dll.name)


SHERPA_LIBRARIES = ["sherpa-onnx-c-api.lib", "sherpa-onnx-core.lib", "kaldi-native-fbank-core.lib", "kissfft-float.lib",
                    "sherpa-onnx-fstfar.lib", "sherpa-onnx-fst.lib", "sherpa-onnx-kaldifst-core.lib",
                    "kaldi-decoder-core.lib", "ssentencepiece_core.lib"]


def sherpa(source, ort_root, cache, jobs, pin, prepare_language):
    """The pinned static sherpa-onnx C API for MSVC, against the official ONNX Runtime 1.20.1 SDK."""
    head = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if head != pin:
        raise RuntimeError(f"sherpa-onnx must be checked out at {pin}, found {head}")
    if subprocess.check_output(["git", "-C", str(source), "status", "--porcelain"], text=True).strip():
        raise RuntimeError("Desktop sherpa-onnx source checkout has local changes")
    if (ort_root / "VERSION_NUMBER").read_text(encoding="utf-8").strip() != "1.20.1":
        raise RuntimeError("The desktop sherpa build requires the official ONNX Runtime 1.20.1 host SDK")
    match = re.search(r'static const char \*version = "([^"]+)"',
                      (source / "sherpa-onnx/csrc/version.cc").read_text(encoding="utf-8"))
    if not match:
        raise RuntimeError("Unable to read the pinned sherpa-onnx version")
    prepared = cache / "sherpa-source"
    if prepared.exists():
        shutil.rmtree(prepared)
    shutil.copytree(source, prepared, symlinks=True, ignore=shutil.ignore_patterns(".git"))
    decoder = prepared / "sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc"
    decoder.write_text(prepare_language(decoder.read_text(encoding="utf-8")), encoding="utf-8")
    build, install = cache / "sherpa-build-prepared", cache / "sherpa-install"
    # Forward slashes: sherpa writes these paths into generated CMake install scripts.
    env = environment({"SHERPA_ONNXRUNTIME_INCLUDE_DIR": (ort_root / "include").resolve().as_posix(),
                       "SHERPA_ONNXRUNTIME_LIB_DIR": (ort_root / "lib").resolve().as_posix()})
    run(["cmake", "-S", prepared, "-B", build, *MSVC_FLAGS, f"-DCMAKE_INSTALL_PREFIX={install.as_posix()}",
         "-DSHERPA_ONNX_USE_STATIC_CRT=ON", "-DSHERPA_ONNX_ENABLE_C_API=ON", "-DSHERPA_ONNX_ENABLE_BINARY=OFF",
         "-DSHERPA_ONNX_BUILD_C_API_EXAMPLES=OFF", "-DSHERPA_ONNX_ENABLE_TESTS=OFF", "-DSHERPA_ONNX_ENABLE_CHECK=OFF",
         "-DSHERPA_ONNX_ENABLE_PYTHON=OFF", "-DSHERPA_ONNX_ENABLE_JNI=OFF", "-DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF",
         "-DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF", "-DSHERPA_ONNX_ENABLE_TTS=OFF",
         "-DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON",
         # Upstream leaves this import-library variable unset for a CPU Windows SDK.
         f"-Dlocation_onnxruntime_lib2={(ort_root / 'lib/onnxruntime.lib').as_posix()}"], env=env)
    run(["cmake", "--build", build, "--config", "Release", "--parallel", jobs], env=env)
    run(["cmake", "--install", build, "--config", "Release", "--prefix", install], env=env)
    if not (install / "include/sherpa-onnx/c-api/c-api.h").is_file():
        raise RuntimeError("The pinned sherpa-onnx build did not install its C API header")
    missing = [name for name in SHERPA_LIBRARIES if not (install / "lib" / name).is_file()]
    if missing:
        raise RuntimeError("The pinned sherpa-onnx build is missing archives: " + ", ".join(missing))
    return install, match.group(1)


def prepare_onnx_engine(build_dir):
    """ORT takes wide paths on Windows. The Android framework source is adapted in the build tree only."""
    source = (CPP / "onnx/onnx_engine.cpp").read_text(encoding="utf-8")
    old = "api->CreateSession(env, modelPath.c_str(), sessionOptions, session)"
    if source.count(old) != 1:
        raise RuntimeError("ONNX engine session creation changed; review the Windows wide-path adaptation")
    prepared = Path(build_dir) / "onnx-engine-desktop.cpp"
    prepared.write_text("#include <filesystem>" + chr(10) + source.replace(
        old, "api->CreateSession(env, std::filesystem::u8path(modelPath).c_str(), sessionOptions, session)"), encoding="utf-8")
    return prepared


def check_qwen(dll):
    require_exports(dll, "Java_com_sal7one_common_1jni_desktop_DesktopQwenNative_", 7)
    require_symbols(dll, ["hearth_speech_backend_v1", "hearth_moonshine_backend_v1",
                          "hearth_omnilingual_backend_v1", "hearth_phonon_backend_v1"])
    if "onnxruntime.dll" not in pe_imports(Path(dll).read_bytes()):
        raise RuntimeError("The Qwen3-ASR DLL does not link the staged ONNX Runtime 1.20.1")
    require_system_imports(dll, bundled=["onnxruntime.dll"])


def rust_static_library(manifest, target_dir, name, toolchain=None):
    """A locked Rust static library for MSVC with the static C runtime, like the C++ it links into."""
    # Windows resolves a child executable from this process's PATH, not the child's.
    cargo = shutil.which("cargo", path=environment()["PATH"])
    if not cargo:
        raise RuntimeError("This stage requires cargo and rustc (rustup, x86_64-pc-windows-msvc)")
    env = environment({"RUSTFLAGS": "-C target-feature=+crt-static"})
    command = [cargo] + ([f"+{toolchain}"] if toolchain else []) + [
        "build", "--manifest-path", manifest, "--locked", "--release",
        "--target", "x86_64-pc-windows-msvc", "--target-dir", target_dir]
    run(command, env=env)
    library = Path(target_dir) / "x86_64-pc-windows-msvc/release" / name
    if not library.is_file():
        raise RuntimeError(f"The Rust build did not produce {name}")
    return library


def byte_bpe():
    return rust_static_library(CPP / "translation/bpe-rust/Cargo.toml", ROOT / "build/ct2-runtime/rust", "hearth_byte_bpe.lib")


def ct2(output, jobs, pins):
    """CTranslate2 + SentencePiece + the Rust byte-BPE as one self-contained JNI DLL."""
    work = ROOT / "build/ct2-runtime"
    source = checkout("https://github.com/OpenNMT/CTranslate2.git", work / "src/CTranslate2", pins["ct2"],
                      submodules=["third_party/ruy", "third_party/spdlog", "third_party/cpu_features"])
    sentencepiece = checkout("https://github.com/google/sentencepiece.git", work / "src/sentencepiece", pins["sentencepiece"])
    sources = [sys.executable, str(ROOT / "scripts/translation/ct2/runtime_sources.py")]
    before = subprocess.check_output(sources, text=True)
    build = work / "windows-x64"
    env = environment()
    run(["cmake", "-S", PROJECTS / "ct2", "-B", build, *MSVC_FLAGS, f"-DCT2_SOURCE={source.as_posix()}",
         f"-DSPM_SOURCE={sentencepiece.as_posix()}", f"-DCPP={CPP.as_posix()}", f"-DBPE_LIB={byte_bpe().as_posix()}"], env=env)
    run(["cmake", "--build", build, "--config", "Release", "--target", "ct2_smoke", "hearth_desktop_ct2", "--parallel", jobs], env=env)
    if subprocess.check_output(sources, text=True) != before:
        raise RuntimeError("CT2 sources changed during compilation; rebuild")
    receipt = build / "hearth-ct2-sources.json"
    receipt.write_text(before, encoding="utf-8", newline=chr(10))
    dll = built(build, "hearth_desktop_ct2.dll")
    require_exports(dll, "Java_com_sal7one_common_1jni_translation_Ct2Native_", 5)
    require_system_imports(dll)
    shutil.copyfile(dll, output / dll.name)
    notices(output, "Ct2-Dependencies-NOTICES.txt", [
        (source / "LICENSE", "CTranslate2 LICENSE (MIT)"),
        (source / "third_party/ruy/LICENSE", "Ruy LICENSE (Apache-2.0)"),
        (source / "third_party/spdlog/LICENSE", "spdlog LICENSE (MIT)"),
        (source / "third_party/cpu_features/LICENSE", "cpu_features LICENSE (Apache-2.0)"),
        (sentencepiece / "LICENSE", "SentencePiece LICENSE (Apache-2.0)"),
        (CPP / "translation/bpe-rust/Cargo.toml", "hearth-byte-bpe (Apache-2.0): pinned Rust byte-BPE static library and its Cargo.lock dependencies"),
    ])
    return _crt_module.sha256(receipt)


def prepared_copy(source, destination, replacements, prefix="#include <filesystem>"):
    """A build-tree copy of a shared source with exact, counted Windows adaptations."""
    text = Path(source).read_text(encoding="utf-8")
    for old, new in replacements:
        if text.count(old) != 1:
            raise RuntimeError(f"{Path(source).name} changed; review the Windows adaptation of: {old[:80]}")
        text = text.replace(old, new)
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(prefix + chr(10) + text, encoding="utf-8")
    return destination


def prepare_moss(build_dir):
    """ONNX Runtime and the C++ runtime take wide paths on Windows; model paths stay UTF-8 at the ABI."""
    return prepared_copy(ROOT / "hearth-tts-moss/src/main/cpp/moss.cpp", Path(build_dir) / "moss-desktop.cpp", [
        ("checked(ort().CreateSession(env,path.c_str(),options,&p))",
         "checked(ort().CreateSession(env,std::filesystem::u8path(path).c_str(),options,&p))"),
        ("std::ifstream input(path,std::ios::binary|std::ios::ate);",
         "std::ifstream input(std::filesystem::u8path(path),std::ios::binary|std::ios::ate);"),
    ])


def check_voice(dll, jni_class, count, ort=False, symbols=()):
    require_exports(dll, f"Java_com_sal7one_common_1jni_voice_{jni_class}_", count)
    if symbols:
        require_symbols(dll, symbols)
    if ort and "onnxruntime.dll" not in pe_imports(Path(dll).read_bytes()):
        raise RuntimeError(f"{Path(dll).name} does not link the staged ONNX Runtime 1.20.1")
    require_system_imports(dll, bundled=["onnxruntime.dll"] if ort else [])


def prepare_chatterbox(build_dir):
    """Build-tree copies of the Chatterbox runtime whose file access takes UTF-8 paths on Windows.

    chatterbox.cpp includes its two helper headers as siblings, so the adapted headers
    beside the copy are the ones it compiles; the shared sources stay untouched.
    """
    source = ROOT / "hearth-tts-chatterbox/src/main/cpp"
    target = Path(build_dir) / "chatterbox-desktop"
    prepared_copy(source / "ort_values.h", target / "ort_values.h", [
        ("CreateSession(env,path.c_str(),options,&p)", "CreateSession(env,std::filesystem::u8path(path).c_str(),options,&p)")])
    prepared_copy(source / "frontend.h", target / "frontend.h", [
        ("std::ifstream in(path,std::ios::binary|std::ios::ate);",
         "std::ifstream in(std::filesystem::u8path(path),std::ios::binary|std::ios::ate);")])
    return prepared_copy(source / "chatterbox.cpp", target / "chatterbox.cpp", [])


def magpie(root, output, build_dir, jobs):
    """The pinned NeMo MagpieTTS C ABI plus Hearth's JNI adapter as one self-contained DLL."""
    java_home = os.environ.get("JAVA_HOME")
    if not java_home or not (Path(java_home) / "include/win32/jni_md.h").is_file():
        raise RuntimeError("Set JAVA_HOME to a JDK installation for the Magpie JNI adapter")
    java = Path(java_home).as_posix()
    cache = Path(build_dir).resolve() / "magpie-nemo"
    # cl reads CL before its command line: every upstream source gets UTF-8 and the GCC-attribute shim.
    env = environment({"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "core.abbrev", "GIT_CONFIG_VALUE_0": "7"})
    env["CL"] += f" /utf-8 /FI{COMPAT / 'hearth_windows_compat.h'}"
    run(["cmake", "-S", root, "-B", cache, *MSVC_FLAGS,
         "-DGGML_NATIVE=OFF", "-DGGML_OPENMP=OFF", "-DGGML_METAL=OFF", "-DGGML_VULKAN=OFF", "-DGGML_CUDA=OFF",
         "-DGGML_BLAS=OFF", "-DGGML_BACKEND_DL=OFF", "-DNEMO_SPEECH_GGML_PATCHED=OFF", "-DNEMO_SPEECH_BUILD_ASR=OFF",
         "-DNEMO_SPEECH_BUILD_DIAR=OFF", "-DNEMO_SPEECH_BUILD_TTS=ON", "-DNEMO_SPEECH_BUILD_NMT=OFF",
         "-DNEMO_SPEECH_BUILD_CLI=OFF", "-DNEMO_SPEECH_BUILD_MIC_CAPTURE=OFF", "-DNEMO_SPEECH_BUILD_HTTP=OFF",
         "-DNEMO_SPEECH_BUILD_GRPC=OFF", "-DNEMO_SPEECH_BUILD_TESTS=OFF", "-DBUILD_TESTING=OFF",
         "-DNEMO_SPEECH_TTS_WITH_JA=OFF", "-DNEMO_SPEECH_TTS_WITH_ZH=OFF", "-DNEMO_SPEECH_WITH_NORM=OFF",
         f"-DCMAKE_PROJECT_TOP_LEVEL_INCLUDES={(ROOT / 'scripts/desktop/native/magpie-inject.cmake').as_posix()}",
         f"-DHEARTH_MAGPIE_ROOT={ROOT.as_posix()}",
         f"-DHEARTH_MAGPIE_JNI_SOURCE={(ROOT / 'desktop-runtime/src/main/cpp/magpie_jni.cpp').as_posix()}",
         f"-DHEARTH_MAGPIE_JNI_INCLUDE_DIRS={java}/include;{java}/include/win32",
         f"-DHEARTH_MAGPIE_COMMON_CPP={(CPP / 'common').as_posix()}",
         f"-DHEARTH_MAGPIE_EXPORTS={(ROOT / 'scripts/desktop/native/magpie.exports').as_posix()}"], env=env)
    run(["cmake", "--build", cache, "--config", "Release", "--target", "nemo_speech_tts", "--parallel", jobs], env=env)
    dll = built(cache, "hearth_desktop_magpie.dll")
    check_voice(dll, "MagpieNative", 6)
    staged = output / dll.name
    shutil.copyfile(dll, staged)
    return staged


# Pinned official Vosk Windows release, the same v0.3.42 as the macOS bundle (its vosk_api.h
# is byte-identical). Fetched and verified at build time only; the app never downloads a runtime.
VOSK_RELEASE = "0.3.42"
VOSK_ZIP_URL = f"https://github.com/alphacep/vosk-api/releases/download/v{VOSK_RELEASE}/vosk-win64-{VOSK_RELEASE}.zip"
VOSK_ZIP_SHA256 = "9a63e42bd970343041d19e784e545228d3f4703ccec9f2eb1ccc6d5e96c170c3"
VOSK_HEADER_SHA256 = "3c53864a8ed10d77e3c456f6371ea8e361414ad478314378309168d8add5325c"
# Load order: the MinGW-w64 runtime libraries first, then the Vosk library that imports them.
VOSK_RUNTIME = {
    "libwinpthread-1.dll": "5b60907df42009f1ba437e7f2a6278651be97f4993869105d8a31086138e4570",
    "libgcc_s_seh-1.dll": "e16de83d48247cde2581ec36279102dda00b7d7b36558cad72164e3c4caae0a4",
    "libstdc++-6.dll": "1766f6e252410086c26400ab085e017e7ad5e8993f3d19dda87a13a1e978f0a2",
    "libvosk.dll": "7a242909fe36436a7660037d85c75eb18eefbd963455cd4ab1b665cb9b0074bc",
}
VOSK_NOTICES = f"""===== Vosk / Kaldi (Apache-2.0) =====
Vosk runtime: official vosk-win64-{VOSK_RELEASE} release asset (Apache-2.0), downloaded and
verified at build time from https://github.com/alphacep/vosk-api/releases/tag/v{VOSK_RELEASE}
Release ZIP SHA-256: {VOSK_ZIP_SHA256}
libvosk.dll SHA-256: {VOSK_RUNTIME['libvosk.dll']}
Vosk bundles Kaldi and OpenFST statically. Model archives are Vosk/Kaldi models (Apache-2.0)
from https://alphacephei.com/vosk/models and are pinned by archive and tree digest.

===== MinGW-w64 runtime libraries shipped inside the same release asset =====
libstdc++-6.dll and libgcc_s_seh-1.dll: GNU GPL version 3 with the GCC Runtime Library
Exception 3.1 (https://www.gnu.org/licenses/gcc-exception-3.1.html).
libwinpthread-1.dll: MinGW-w64 winpthreads, MIT and BSD 3-Clause licenses
(https://www.mingw-w64.org/). These unmodified files are loaded only by libvosk.dll.
"""


def prepare_vosk(build_dir):
    """Windows names and UTF-8 path checks for the shared Vosk adapter, in the build tree only."""
    return prepared_copy(CPP / "speech/backends/vosk_backend.cpp", Path(build_dir) / "vosk-backend-desktop.cpp", [
        ('"vosk-osx-0.3.42/65395f196c9d0583d79949142b25560acaf9c295f36284e18433097f3adb0ea1";',
         f'"vosk-win64-{VOSK_RELEASE}/{VOSK_ZIP_SHA256}";'),
        ("const size_t slash = self.find_last_of('/');", 'const size_t slash = self.find_last_of("/' + chr(92) * 2 + '");'),
        ('const std::string candidate = self.substr(0, slash + 1) + "libvosk.dylib";',
         'const std::string candidate = self.substr(0, slash + 1) + "libvosk.dll";'),
        ("        struct stat status {};" + chr(10) + "        if (::stat(candidate.c_str(), &status) != 0 || !S_ISREG(status.st_mode)) {",
         "        if (!std::filesystem::is_regular_file(std::filesystem::u8path(candidate))) {"),
    ])


def vosk_runtime(cache, output):
    """Fetch, verify and stage the pinned official Vosk runtime and its MinGW runtime libraries."""
    import zipfile
    cache = Path(cache)
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / f"vosk-win64-{VOSK_RELEASE}.zip"
    if not archive.is_file() or _crt_module.sha256(archive) != VOSK_ZIP_SHA256:
        archive.unlink(missing_ok=True)
        run(["curl", "--fail", "--location", "--silent", "--show-error", "--output", archive, VOSK_ZIP_URL])
        if _crt_module.sha256(archive) != VOSK_ZIP_SHA256:
            archive.unlink(missing_ok=True)
            raise RuntimeError("The Vosk release archive does not match its pinned SHA-256")
    prefix = f"vosk-win64-{VOSK_RELEASE}/"
    with zipfile.ZipFile(archive) as package:
        members = {item.filename for item in package.infolist()}
        expected = {prefix, prefix + "vosk_api.h", prefix + "libvosk.lib", *(prefix + name for name in VOSK_RUNTIME)}
        if members != expected:
            raise RuntimeError("The Vosk release archive layout changed: " + ", ".join(sorted(members)))
        import hashlib
        if hashlib.sha256(package.read(prefix + "vosk_api.h")).hexdigest() != VOSK_HEADER_SHA256:
            raise RuntimeError("The Vosk release header does not match its pinned SHA-256")
        for name, pin in VOSK_RUNTIME.items():
            data = package.read(prefix + name)
            if hashlib.sha256(data).hexdigest() != pin:
                raise RuntimeError(f"The Vosk release member {name} does not match its pinned SHA-256")
            (output / name).write_bytes(data)
    (output / "Vosk-NOTICES.txt").write_text(VOSK_NOTICES, encoding="utf-8", newline=chr(10))
    return {"voskRelease": VOSK_RELEASE, "voskZipSha256": VOSK_ZIP_SHA256,
            "voskDylibSha256": VOSK_RUNTIME["libvosk.dll"], "voskHeaderSha256": VOSK_HEADER_SHA256,
            "voskRuntimeSha256": dict(VOSK_RUNTIME)}


def check_vosk(dll):
    require_symbols(dll, ["hearth_speech_backend_v1"])
    imports = pe_imports(Path(dll).read_bytes())
    if any("vosk" in name for name in imports):
        raise RuntimeError("hearth_vosk.dll must load libvosk.dll at runtime, not link it")
    require_system_imports(dll)


def ming(output, toolchain="1.99.0"):
    """The experimental Ming JNI runtime from the pinned Rust sources; fusor-ai 0.1.2 needs rustc 1.99."""
    dll = rust_static_library(CPP / "ming/jni/Cargo.toml", ROOT / "build/ming-runtime/rust", "ming_jni.dll", toolchain)
    require_exports(dll, "Java_com_sal7one_common_1jni_MingNative_", 3)
    require_system_imports(dll)
    staged = output / dll.name
    shutil.copyfile(dll, staged)
    return staged
