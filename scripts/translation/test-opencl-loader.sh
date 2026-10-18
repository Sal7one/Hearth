#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
HEADERS="${1:-$REPO/build/translation-runtime/OpenCL-Headers}"
WORK="$REPO/build/translation-runtime/opencl-loader-tests"
mkdir -p "$WORK"
CPP="$REPO/common-jni/src/main/cpp"
FLAGS=(-std=c++17 -Wall -Wextra -Wpedantic -Werror -I"$HEADERS" -I"$CPP/common")
if [ "$(uname -s)" = Darwin ]; then SHARED=-dynamiclib; LINK=(-lm); else SHARED=-shared; LINK=(-ldl); fi
"${CXX:-c++}" "${FLAGS[@]}" "$SHARED" -fPIC -DHEARTH_FAKE_OPENCL_DRIVER=1 "$CPP/translation/tests/opencl_loader_test.cpp" -o "$WORK/libOpenCL.so"
python3 "$REPO/scripts/translation/generate-opencl-shim.py" --headers "$HEADERS" --source "$CPP/common/opencl_loader.h" --source "$CPP/translation/tests/opencl_loader_test.cpp" --output "$WORK/shim.cpp"
"${CXX:-c++}" "${FLAGS[@]}" "-DHEARTH_OPENCL_DRIVER_NAME=\"$WORK/libOpenCL.so\"" "$CPP/translation/tests/opencl_loader_test.cpp" "$WORK/shim.cpp" "${LINK[@]}" -o "$WORK/opencl_loader_test"
"$WORK/opencl_loader_test"
"${CXX:-c++}" "${FLAGS[@]}" "-DHEARTH_OPENCL_DRIVER_NAME=\"$WORK/absent-driver.so\"" "$CPP/translation/tests/opencl_loader_test.cpp" "$WORK/shim.cpp" "${LINK[@]}" -o "$WORK/opencl_absent_driver_test"
"$WORK/opencl_absent_driver_test" missing
