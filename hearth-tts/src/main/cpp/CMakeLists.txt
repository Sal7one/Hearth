cmake_minimum_required(VERSION 3.22.1)
project(hearth_tts_adapters LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(ADAPTER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")

# One Android TTS project, one set of JNI targets. The runtime .so files come from
# the merged module's jniLibs; only the wrappers are compiled here.
add_subdirectory("${ADAPTER_ROOT}/hearth-tts-supertonic/src/main/cpp" supertonic)
add_subdirectory("${ADAPTER_ROOT}/hearth-tts-magpie/src/main/cpp" magpie)
add_subdirectory("${ADAPTER_ROOT}/hearth-tts-omnivoice/src/main/cpp" omnivoice)
add_subdirectory("${ADAPTER_ROOT}/hearth-tts-moss/src/main/cpp" moss)
add_subdirectory("${ADAPTER_ROOT}/hearth-tts-oute/src/main/cpp" oute)
add_subdirectory("${ADAPTER_ROOT}/hearth-tts-chatterbox/src/main/cpp" chatterbox)
