#!/usr/bin/env bash
# =============================================================================
# run_common_utils_tests.sh — host tests for the common/ shared utils.
#
# No gradle, no Android, no network: compiles each util test directly with
# the system C++ compiler against json_utils.cpp + engine_interface.cpp
# (the only non-header common sources) and runs it. Mirrors the pattern of
# media/tests/run_media_engine_tests.sh.
#
# Environment overrides:
#   CXX        compiler (default: c++)
#   BUILD_DIR  keep everything under this dir (default: fresh mktemp)
#   KEEP=1     keep the temp build dir and print its path
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CPP_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
COMMON_DIR="${CPP_DIR}/common"

log() { printf '\033[1;36m[common-tests]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[common-tests] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

CXX_BIN="${CXX:-c++}"
command -v "${CXX_BIN}" >/dev/null 2>&1 || die "C++ compiler not found (set CXX=)"

BUILD_DIR="${BUILD_DIR:-$(mktemp -d)}"
trap 'if [ "${KEEP:-0}" = "1" ]; then echo "build dir: ${BUILD_DIR}"; else rm -rf "${BUILD_DIR}"; fi' EXIT

TESTS=(cancel_token pipe_progress job_future json_options pcm_buffer_range lease_registry utf8_utils audio_gate scoped_timer buffer_pool sha256)

OVERALL=0
for name in "${TESTS[@]}"; do
    SRC="${SCRIPT_DIR}/${name}_test.cpp"
    BIN="${BUILD_DIR}/${name}_test"
    [ -f "${SRC}" ] || die "test source not found: ${SRC}"
    log "compiling ${name}"
    "${CXX_BIN}" -std=c++17 -O1 -Wall -Wextra -fsanitize=address,undefined \
        -I"${COMMON_DIR}" \
        "${SRC}" "${COMMON_DIR}/json_utils.cpp" "${COMMON_DIR}/engine_interface.cpp" \
        -pthread -o "${BIN}"
    log "running ${name}"
    if "${BIN}"; then
        log "PASS ${name}"
    else
        log "FAIL ${name}"
        OVERALL=1
    fi
done

# Exercise JNI exception construction under a real JVM with -Xcheck:jni.
# Android's jni.h uses the same JNI call ABI, while the host VM catches
# pending-exception misuse and preserves supplementary Unicode in messages.
JDK_HOME="${JAVA_HOME:-}"
if [ -z "${JDK_HOME}" ] && [ "$(uname -s)" = Darwin ]; then
    JDK_HOME="$(/usr/libexec/java_home)"
fi
if [ -z "${JDK_HOME}" ] && [ "$(uname -s)" = Linux ] && command -v javac >/dev/null 2>&1; then
    JDK_HOME="$(dirname -- "$(dirname -- "$(readlink -f -- "$(command -v javac)")")")"
fi
[ -n "${JDK_HOME}" ] || die "JDK home required for jni_helper_test (set JAVA_HOME)"
case "$(uname -s)" in
    Darwin) JNI_PLATFORM=darwin ;;
    Linux) JNI_PLATFORM=linux ;;
    *) die "unsupported host platform for jni_helper_test" ;;
esac
log "compiling jni_helper"
"${CXX_BIN}" -std=c++17 -O1 -Wall -Wextra -fsanitize=address,undefined \
    -I"${JDK_HOME}/include" -I"${JDK_HOME}/include/${JNI_PLATFORM}" \
    "${SCRIPT_DIR}/jni_helper_test.cpp" \
    -L"${JDK_HOME}/lib/server" -Wl,-rpath,"${JDK_HOME}/lib/server" -ljvm \
    -pthread -o "${BUILD_DIR}/jni_helper_test"
log "running jni_helper"
if "${BUILD_DIR}/jni_helper_test"; then
    log "PASS jni_helper"
else
    log "FAIL jni_helper"
    OVERALL=1
fi

if [ "${OVERALL}" -ne 0 ]; then
    die "one or more util tests failed"
fi
log "ALL UTIL TESTS PASS"
