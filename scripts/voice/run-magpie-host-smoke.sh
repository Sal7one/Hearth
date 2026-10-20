#!/usr/bin/env bash
# Requires explicit assets fetch first. Runs real C ABI and JNI checks on macOS, never on an owner device.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
work="${HEARTH_MAGPIE_BUILD_DIR:-$repo_root/build/voice-runtimes}"
java_dir="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
test "$(uname -s)" = Darwin
"$repo_root/scripts/voice/build-magpie-runtime.sh" host
host_lib="$work/magpie-host-arm64-v8a-cpu/bin"
c++ -std=c++17 "$repo_root/scripts/voice/magpie-smoke.cpp" -I "$repo_root/hearth-tts-magpie/src/main/cpp/vendor" -L "$host_lib" -lhearth_magpie -Wl,-rpath,"$host_lib" -o "$work/magpie-smoke"
"$work/magpie-smoke" "$work/models"
mkdir -p "$work/jni-host/classes"
c++ -std=c++17 -shared -fPIC -fvisibility=hidden "$repo_root/hearth-tts-magpie/src/main/cpp/magpie_jni.cpp" -I "$repo_root/common-jni/src/main/cpp/common" -I "$java_dir/include" -I "$java_dir/include/darwin" -o "$work/jni-host/libhearth_magpie_jni.dylib"
ln -sf "$host_lib/libhearth_magpie.dylib" "$work/jni-host/libhearth_magpie.so"
"$java_dir/bin/javac" -d "$work/jni-host/classes" "$repo_root/scripts/voice/tests/com/sal7one/common_jni/voice/MagpieNative.java"
DYLD_LIBRARY_PATH="$work/jni-host" "$java_dir/bin/java" -Djava.library.path="$work/jni-host" -cp "$work/jni-host/classes" com.sal7one.common_jni.voice.MagpieNative "$work/models"
