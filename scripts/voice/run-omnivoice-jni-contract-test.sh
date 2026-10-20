#!/usr/bin/env bash
# No models or network: exercise the real JNI bridge against an isolated fake C ABI.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$repo_root/build/omnivoice-jni-contract"
java_dir="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
case "$(uname -s)" in
  Darwin) jni_platform=darwin; library_suffix=dylib; dynamic_link=(-pthread) ;;
  Linux) jni_platform=linux; library_suffix=so; dynamic_link=(-pthread -ldl) ;;
  *) echo 'The JNI host fixture supports Linux and macOS' >&2; exit 2 ;;
esac
compiler="${CXX:-c++}"
mkdir -p "$work/classes"
"$compiler" -std=c++17 -shared -fPIC "$repo_root/scripts/voice/tests/fake-omnivoice.cpp" -I "$repo_root/hearth-tts-omnivoice/src/main/cpp/vendor" -o "$work/libhearth_omnivoice.so"
"$compiler" -std=c++17 -shared -fPIC -fvisibility=hidden "$repo_root/hearth-tts-omnivoice/src/main/cpp/omnivoice_jni.cpp" -I "$repo_root/common-jni/src/main/cpp/common" -I "$java_dir/include" -I "$java_dir/include/$jni_platform" "${dynamic_link[@]}" -o "$work/libhearth_omnivoice_jni.$library_suffix"
"$java_dir/bin/javac" -d "$work/classes" "$repo_root/scripts/voice/tests/com/sal7one/common_jni/voice/OmniVoiceNative.java"
LD_LIBRARY_PATH="$work" DYLD_LIBRARY_PATH="$work" python3 - "$java_dir/bin/java" -Djava.library.path="$work" -cp "$work/classes" com.sal7one.common_jni.voice.OmniVoiceNative "$work/unused-fixture-path" <<'BOUNDED_FIXTURE'
import subprocess,sys
subprocess.run(sys.argv[1:],check=True,timeout=60)
BOUNDED_FIXTURE
