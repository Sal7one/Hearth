#!/usr/bin/env bash
# Explicit real-model JNI smoke after fetching and verifying the pinned test fixtures.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$root/build/oute-real-jni";models="${1:-$root/build/voice-runtimes/oute-test-assets}"
java_dir="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
case "$(uname -s)" in Darwin)jni_platform=darwin;suffix=dylib;dynamic_link=(-pthread);;Linux)jni_platform=linux;suffix=so;dynamic_link=(-pthread -ldl);;*)exit 2;;esac
mkdir -p "$work/classes"
python3 - "$models" <<'PY'
from pathlib import Path
import hashlib,sys
root=Path(sys.argv[1])
for name,size,sha in [('oute-tts-1b.bin',1438416240,'e4e14b573459a8a4958e32e39678d2f0ac61cf73ccadb6dbda1f8fde1f8ade83'),('en-female-1-neutral.json',25295,'6bb6bacca60c9759741d849c2baf2af3f6263a51b759d67826ef39e1efc4436c')]:
 p=root/name
 with p.open('rb') as f:assert p.stat().st_size==size and hashlib.file_digest(f,'sha256').hexdigest()==sha, name
PY
cp "$root/build/voice-runtimes/oute-host-arm64-v8a-cpu/libhearth_oute.$suffix" "$work/libhearth_oute.so"
"${CXX:-c++}" -std=c++17 -shared -fPIC -fvisibility=hidden "$root/hearth-tts-oute/src/main/cpp/oute_jni.cpp" -I "$root/common-jni/src/main/cpp/common" -I "$java_dir/include" -I "$java_dir/include/$jni_platform" "${dynamic_link[@]}" -o "$work/libhearth_oute_jni.$suffix"
"$java_dir/bin/javac" -d "$work/classes" "$root/scripts/voice/tests/com/sal7one/common_jni/voice/OuteNative.java" "$root/scripts/voice/tests/com/sal7one/common_jni/voice/OuteRealSmoke.java"
LD_LIBRARY_PATH="$work" DYLD_LIBRARY_PATH="$work" "$java_dir/bin/java" -Djava.library.path="$work" -cp "$work/classes" com.sal7one.common_jni.voice.OuteRealSmoke "$models/oute-tts-1b.bin" "$models/en-female-1-neutral.json"
