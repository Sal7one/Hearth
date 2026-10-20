#!/usr/bin/env python3
"""Build isolated, model-free Windows/macOS desktop JNI adapters from pinned local sources.

No downloads are performed. Supply the official extracted ONNX Runtime 1.20.1 SDK
and a local llama.cpp checkout at the exact Android translation source revision.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
LLAMA_PIN = "64e9bceb2c3a856efed96feda784a50947049feb"
WHISPER_PIN = "5f3464bc06c04426d66eae69e5e510d32fb76621b524c42ab25b4294f035a920"


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def snapshot():
    paths = [ROOT / "scripts/desktop/build-native.py"]
    for folder in ["scripts/desktop/native", "desktop-runtime/src/main/cpp"]:
        paths.extend(p for p in (ROOT / folder).rglob("*") if p.is_file())
    for name in ["common-jni/src/main/cpp/translation/text_model.h", "common-jni/src/main/cpp/translation/translation_jni.cpp",
                 "common-jni/src/main/cpp/common/lease_registry.h", "hearth-tts-supertonic/src/main/cpp/voice/voice_bounds.h",
                 "common-jni/src/main/cpp/onnx/include/onnxruntime/onnxruntime_c_api.h"]:
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=["all", "whisper", "translation", "supertonic"], default="all")
    parser.add_argument("--llama-root", type=Path, default=ROOT / "build/translation-runtime/llama.cpp")
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
    if "supertonic" in stages and (not args.ort_root or not args.ort_root.is_dir()):
        parser.error("--ort-root/HEARTH_ORT_ROOT must name the official extracted ONNX Runtime 1.20.1 host SDK")
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
    for stage in stages:
        cache = args.build_dir.resolve() / target / stage
        command = ["cmake", "-S", str(ROOT / "scripts/desktop/native"), "-B", str(cache),
                   "-DCMAKE_BUILD_TYPE=Release", f"-DHEARTH_ROOT={ROOT}", f"-DHEARTH_STAGE={stage}",
                   f"-DHEARTH_LLAMA_ROOT={args.llama_root.resolve()}",
                   f"-DHEARTH_SUPERTONIC_SOURCE={prepared}",
                   f"-DHEARTH_SUPERTONIC_JNI_SOURCE={prepared_jni}",
                   f"-DHEARTH_TRANSLATION_JNI_SOURCE={translation_jni}",
                   f"-DHEARTH_DESKTOP_METAL={'ON' if args.metal else 'OFF'}"]
        if args.ort_root: command.append(f"-DHEARTH_ORT_ROOT={args.ort_root.resolve()}")
        if system == "macos": command.append("-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0")
        subprocess.run(command, check=True)
        subprocess.run(["cmake", "--build", str(cache), "--config", "Release", "--parallel", str(args.jobs)], check=True)
        subprocess.run(["cmake", "--install", str(cache), "--config", "Release", "--component", "hearth-desktop", "--prefix", str(output)], check=True)
    if "supertonic" in stages:
        ort = args.ort_root.resolve()
        if system == "macos":
            # JAR resources cannot preserve symlinks. The linked versioned install name is sufficient.
            shutil.copyfile(ort / "lib/libonnxruntime.1.20.1.dylib", output / "libonnxruntime.1.20.1.dylib")
            (output / "libonnxruntime.dylib").unlink(missing_ok=True)
        elif system == "windows": shutil.copyfile(ort / "lib/onnxruntime.dll", output / "onnxruntime.dll")
        else: shutil.copyfile(ort / "lib/libonnxruntime.so", output / "libonnxruntime.so")
        for name, dest in [("LICENSE", "ONNX-LICENSE.txt"), ("ThirdPartyNotices.txt", "ONNX-ThirdPartyNotices.txt")]:
            shutil.copyfile(ort / name, output / dest)
    shutil.copyfile(ROOT / "common-jni/src/main/cpp/whisper/upstream/LICENSE", output / "Whisper-LICENSE.txt")
    if "translation" in stages: shutil.copyfile(args.llama_root / "LICENSE", output / "llama-LICENSE.txt")
    shutil.copyfile(ROOT / "hearth-tts-supertonic/src/main/assets/licenses/voice/Supertonic-MIT.txt", output / "Supertonic-LICENSE.txt")
    if before != snapshot(): raise RuntimeError("Desktop native sources changed during compilation; rebuild the complete bundle")
    files = {p.name: sha(p) for p in sorted(output.iterdir()) if p.is_file() and p.name != "manifest.json"}
    manifest = {"schemaVersion": 1, "platform": target, "files": files, "sources": before,
                "sourceSetSha256": hashlib.sha256(json.dumps(before, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
                "upstreams": {"whisperSourceTreeSha256": WHISPER_PIN, "llamaRevision": LLAMA_PIN, "onnxRuntime": "1.20.1"},
                "backends": ["cpu", "metal"] if args.metal else ["cpu"],
                "compiledMetalSupport": args.metal, "metalSelectionRequiresUsableDevice": True,
                "modelWeightsIncluded": False}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Desktop native bundle staged at {output}")


if __name__ == "__main__":
    main()
