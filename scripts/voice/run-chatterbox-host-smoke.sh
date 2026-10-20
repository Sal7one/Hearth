#!/usr/bin/env bash
# Explicit real-model JNI smoke against previously verified assets; no network.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$root/build/chatterbox-real-jni";models="${1:-$root/build/voice-runtimes/chatterbox-models}"
java_dir="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
case "$(uname -s)" in Darwin)jni_platform=darwin;suffix=dylib;dynamic_link=(-pthread);;Linux)jni_platform=linux;suffix=so;dynamic_link=(-pthread -ldl);;*)exit 2;;esac
mkdir -p "$work/classes"
cp "$root/build/voice-runtimes/chatterbox-host-arm64/libhearth_chatterbox.$suffix" "$work/libhearth_chatterbox.so"
"${CXX:-c++}" -std=c++17 -shared -fPIC -fvisibility=hidden "$root/hearth-tts-chatterbox/src/main/cpp/chatterbox_jni.cpp" -I "$root/common-jni/src/main/cpp/common" -I "$java_dir/include" -I "$java_dir/include/$jni_platform" "${dynamic_link[@]}" -o "$work/libhearth_chatterbox_jni.$suffix"
"$java_dir/bin/javac" -d "$work/classes" "$root/scripts/voice/tests/com/sal7one/common_jni/voice/ChatterboxNative.java"
LD_LIBRARY_PATH="$work" DYLD_LIBRARY_PATH="$work" "$java_dir/bin/java" -Djava.library.path="$work" -cp "$work/classes" com.sal7one.common_jni.voice.ChatterboxNative "$models"
