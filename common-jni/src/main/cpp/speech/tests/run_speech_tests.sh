#!/usr/bin/env bash
set -euo pipefail
SPEECH="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
"${CXX:-c++}" -std=c++17 -O1 -g -Wall -Wextra -fsanitize=address,undefined \
  "$SPEECH/tests/speech_test.cpp" "$SPEECH/speech_session.cpp" \
  "$SPEECH/../common/json_utils.cpp" "$SPEECH/../common/engine_interface.cpp" \
  -pthread -ldl -o "$BUILD/speech_test"
"$BUILD/speech_test"

# The legacy Whisper adapter also serves live captions. Stub only its pinned C
# API so decoder failures exercise production worker/finalize/batch behavior.
CPP="$(dirname "$SPEECH")"
"${CXX:-c++}" -std=c++17 -O1 -g -Wall -Wextra -fsanitize=address,undefined \
  -DWHISPER_AVAILABLE=1 -I"$CPP/common" -I"$CPP/whisper/upstream/include" -I"$CPP/whisper/upstream/ggml/include" \
  "$CPP/whisper/tests/whisper_failure_test.cpp" "$CPP/whisper/whisper_engine.cpp" \
  "$CPP/common/json_utils.cpp" "$CPP/common/engine_interface.cpp" "$CPP/common/logging.cpp" \
  -pthread -o "$BUILD/whisper_failure_test"
"$BUILD/whisper_failure_test"

# Fake the QVAC model API, retaining the production plugin and owner session.
"${CXX:-c++}" -std=c++17 -O1 -g -Wall -Wextra -fsanitize=address,undefined \
  -I"$SPEECH/tests/fakes" \
  "$SPEECH/tests/transcribepsy_backend_test.cpp" "$SPEECH/speech_session.cpp" \
  "$CPP/common/json_utils.cpp" "$CPP/common/engine_interface.cpp" \
  -pthread -ldl -o "$BUILD/transcribepsy_backend_test"
"$BUILD/transcribepsy_backend_test"
"${CXX:-c++}" -std=c++17 -O1 -g -Wall -Wextra -fsanitize=address,undefined \
  -I"$SPEECH/tests/fakes" \
  "$SPEECH/tests/transcribepsy_gpu_test.cpp" "$SPEECH/speech_session.cpp" \
  "$CPP/common/json_utils.cpp" "$CPP/common/engine_interface.cpp" \
  -pthread -ldl -o "$BUILD/transcribepsy_gpu_test"
"$BUILD/transcribepsy_gpu_test"
