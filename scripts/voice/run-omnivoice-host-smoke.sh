#!/usr/bin/env bash
# Explicit real-model C ABI/JNI smoke; uses previously fetched pinned files, no network.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$repo_root/build/omnivoice-real-jni"
models="${1:-$repo_root/build/voice-runtimes/omnivoice-models}"
java_dir="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
case "$(uname -s)" in Darwin)jni_platform=darwin;suffix=dylib;dynamic_link=(-pthread);;Linux)jni_platform=linux;suffix=so;dynamic_link=(-pthread -ldl);;*)exit 2;;esac
mkdir -p "$work/classes"
cp "$repo_root/build/voice-runtimes/omnivoice-host-arm64-v8a-cpu/libhearth_omnivoice.$suffix" "$work/libhearth_omnivoice.so"
"${CXX:-c++}" -std=c++17 -shared -fPIC -fvisibility=hidden "$repo_root/hearth-tts-omnivoice/src/main/cpp/omnivoice_jni.cpp" -I "$repo_root/common-jni/src/main/cpp/common" -I "$java_dir/include" -I "$java_dir/include/$jni_platform" "${dynamic_link[@]}" -o "$work/libhearth_omnivoice_jni.$suffix"
"$java_dir/bin/javac" -d "$work/classes" "$repo_root/scripts/voice/tests/com/sal7one/common_jni/voice/OmniVoiceNative.java" "$repo_root/scripts/voice/tests/com/sal7one/common_jni/voice/OmniVoiceRealSmoke.java"
LD_LIBRARY_PATH="$work" DYLD_LIBRARY_PATH="$work" "$java_dir/bin/java" -Djava.library.path="$work" -cp "$work/classes" com.sal7one.common_jni.voice.OmniVoiceRealSmoke "$models"
