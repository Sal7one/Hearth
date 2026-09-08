cmake_minimum_required(VERSION 3.22.1)

project("common_jni" VERSION 1.0.0 LANGUAGES C CXX)

# =============================================================================
# Common JNI Module - Reusable Native Library
# =============================================================================
# This module provides:
# - STT engines (Whisper, Vosk, ONNX)
# - Session management
# - VAD (Voice Activity Detection)
# - Model loading
# - Qwen / Nemotron speech plugin sessions
# =============================================================================

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Options - configure what to include
option(WITH_WHISPER "Enable Whisper.cpp STT engine" ON)
option(WITH_VOSK "Enable Vosk STT engine" ON)
option(WITH_ONNX "Enable ONNX Runtime STT engine" ON)
set(WITH_FFMPEG OFF)
set(WITH_OPENCV OFF)
option(VERBOSE_LOGGING "Enable verbose logging" ON)

# Include generated config
include_directories(${CMAKE_CURRENT_BINARY_DIR})
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/common)

# Platform libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# =============================================================================
# Build Configuration: Debug vs Release
# =============================================================================

# Debug flags - keep symbols for debugging
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -g -O0")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -g -O0")

# Release flags - optimize and strip
# Preserve IEEE floating-point behavior for speech inference
# -fomit-frame-pointer: free up a register for general use (safe)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3 -DNDEBUG -fvisibility=hidden -ffunction-sections -fdata-sections")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG -fvisibility=hidden -ffunction-sections -fdata-sections")

# Security hardening flags
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fstack-protector-strong")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fstack-protector-strong")

# Release linker flags - strip symbols and garbage collect unused sections
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -Wl,--gc-sections -Wl,-s")

# Enable LTO for release builds (Link Time Optimization)
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set(CMAKE_INTERPROCEDURAL_OPTIMIZATION TRUE)
    message(STATUS "✓ LTO enabled for release build")
endif()

# =============================================================================
# 16 KB Page Size Support (Android 15+ / Google Play Nov 2025)
# =============================================================================
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# ARM NEON support and CPU-specific optimizations
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_definitions(GGML_USE_NEON=1)
    add_compile_definitions(GGML_USE_CPU=1)
    # Enable auto-vectorization hints
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ftree-vectorize")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ftree-vectorize")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -mfpu=neon -ftree-vectorize")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon -ftree-vectorize")
    add_compile_definitions(GGML_USE_CPU=1)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # Enable SSE/AVX for x86_64 emulator testing
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -msse4.2")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -msse4.2")
endif()

if(VERBOSE_LOGGING)
    add_compile_definitions(VERBOSE_LOGGING=1)
endif()

# =============================================================================
# 1. Common Library (shared utilities)
# =============================================================================
add_library(stt_common STATIC
    common/logging.cpp
    common/json_utils.cpp
    common/engine_interface.cpp
    common/audio_utils.cpp
    common/stt_kit.cpp
)
target_include_directories(stt_common PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/common)
target_link_libraries(stt_common ${log-lib})

# =============================================================================
# 2. Whisper.cpp Engine
# =============================================================================
set(WHISPER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/whisper/upstream")

if(WITH_WHISPER)
    if(NOT EXISTS "${WHISPER_ROOT}/src/whisper.cpp")
        message(FATAL_ERROR
            "Whisper.cpp is enabled but the pinned source is missing. "
            "Run: git submodule update --init --recursive -- "
            "common-jni/src/main/cpp/whisper/upstream")
    endif()

    message(STATUS "✓ Whisper.cpp found at ${WHISPER_ROOT}")

    # Collect GGML sources
    file(GLOB GGML_SOURCES
        "${WHISPER_ROOT}/ggml/src/*.c"
        "${WHISPER_ROOT}/ggml/src/*.cpp"
    )

    # Collect CPU Backend sources
    if(EXISTS "${WHISPER_ROOT}/ggml/src/ggml-cpu")
        file(GLOB_RECURSE GGML_CPU_SOURCES
            "${WHISPER_ROOT}/ggml/src/ggml-cpu/*.c"
            "${WHISPER_ROOT}/ggml/src/ggml-cpu/*.cpp"
        )
        # Filter unsupported architectures
        list(FILTER GGML_CPU_SOURCES EXCLUDE REGEX ".*/arch/powerpc/.*")
        list(FILTER GGML_CPU_SOURCES EXCLUDE REGEX ".*/arch/s390/.*")
        list(FILTER GGML_CPU_SOURCES EXCLUDE REGEX ".*/arch/riscv/.*")
        list(FILTER GGML_CPU_SOURCES EXCLUDE REGEX ".*/arch/loongarch/.*")
        list(FILTER GGML_CPU_SOURCES EXCLUDE REGEX ".*/arch/avx/.*")
        list(FILTER GGML_CPU_SOURCES EXCLUDE REGEX ".*/spacemit/.*")
        list(FILTER GGML_CPU_SOURCES EXCLUDE REGEX ".*/kleidiai/.*")
        list(APPEND GGML_SOURCES ${GGML_CPU_SOURCES})
    endif()

    add_library(whisper_core STATIC "${WHISPER_ROOT}/src/whisper.cpp" ${GGML_SOURCES})
    target_include_directories(whisper_core PUBLIC
        "${WHISPER_ROOT}/include"
        "${WHISPER_ROOT}/ggml/include"
        "${WHISPER_ROOT}/ggml/src"
        "${WHISPER_ROOT}/ggml/src/ggml-cpu"
        "${WHISPER_ROOT}/src"
    )
    target_compile_definitions(whisper_core PRIVATE
        WHISPER_VERSION="1.8.2"
        GGML_VERSION="0.9.4"
        GGML_COMMIT="19ceec8"
        GGML_SCHED_MAX_COPIES=4
    )

    add_library(whisper_engine STATIC whisper/whisper_engine.cpp)
    target_link_libraries(whisper_engine stt_common whisper_core)
    target_include_directories(whisper_engine PRIVATE "${WHISPER_ROOT}/include")
    target_compile_definitions(whisper_engine PRIVATE WHISPER_AVAILABLE=1)

    set(HAS_WHISPER TRUE)
else()
    message(STATUS "✗ Whisper.cpp disabled")
    set(HAS_WHISPER FALSE)
endif()

# =============================================================================
# 3. Vosk Engine (Prebuilt)
# =============================================================================
set(VOSK_LIB_FILE "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libvosk.so")

if(WITH_VOSK AND EXISTS "${VOSK_LIB_FILE}")
    message(STATUS "✓ Vosk found at ${VOSK_LIB_FILE}")
    
    add_library(vosk_prebuilt SHARED IMPORTED)
    set_target_properties(vosk_prebuilt PROPERTIES IMPORTED_LOCATION "${VOSK_LIB_FILE}")

    add_library(vosk_engine STATIC vosk/vosk_engine.cpp)
    target_include_directories(vosk_engine PRIVATE vosk)
    target_link_libraries(vosk_engine stt_common vosk_prebuilt)
    target_compile_definitions(vosk_engine PRIVATE VOSK_AVAILABLE=1)

    set(HAS_VOSK TRUE)
else()
    message(STATUS "✗ Vosk not found")
    set(HAS_VOSK FALSE)
endif()

# =============================================================================
# 4. ONNX Runtime Engine (Prebuilt)
# =============================================================================
set(ONNX_LIB_FILE "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libonnxruntime.so")
set(ONNX_HEADER_FILE "${CMAKE_CURRENT_SOURCE_DIR}/onnx/include/onnxruntime/onnxruntime_c_api.h")

if(WITH_ONNX AND EXISTS "${ONNX_LIB_FILE}" AND EXISTS "${ONNX_HEADER_FILE}")
    message(STATUS "✓ ONNX Runtime found")
    
    add_library(onnx_prebuilt SHARED IMPORTED)
    set_target_properties(onnx_prebuilt PROPERTIES IMPORTED_LOCATION "${ONNX_LIB_FILE}")

    add_library(onnx_engine STATIC
        onnx/onnx_engine.cpp
    )
    target_include_directories(onnx_engine PRIVATE onnx onnx/include)
    target_link_libraries(onnx_engine stt_common onnx_prebuilt)
    target_compile_definitions(onnx_engine PRIVATE ONNX_AVAILABLE=1)

    # Marian (OPUS-MT) translation engine over the same prebuilt runtime.
    add_library(marian_engine STATIC
        marian/marian_tokenizer.cpp
        marian/marian_engine.cpp
    )
    target_include_directories(marian_engine PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/marian
        ${CMAKE_CURRENT_SOURCE_DIR}/onnx
        ${CMAKE_CURRENT_SOURCE_DIR}/onnx/include
        ${CMAKE_CURRENT_SOURCE_DIR}/common
    )
    target_link_libraries(marian_engine stt_common onnx_prebuilt)
    target_compile_definitions(marian_engine PRIVATE MARIAN_AVAILABLE=1)

    set(HAS_ONNX TRUE)
else()
    message(STATUS "✗ ONNX Runtime not found")
    set(HAS_ONNX FALSE)
endif()

# =============================================================================
# 7. Core Library (Session, VAD, Model Loading)
# =============================================================================
add_library(stt_core STATIC
    core/session.cpp
    core/model_loader.cpp
    core/vad.cpp
)
target_include_directories(stt_core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/core
    ${CMAKE_CURRENT_SOURCE_DIR}/common
)
target_link_libraries(stt_core stt_common ${log-lib} ${android-lib})

# =============================================================================
# 7.1 TTS Engine Fleet (Kokoro, sherpa-onnx, Supertonic) — ONNX-based
# =============================================================================
# NOTE: Must be defined BEFORE main JNI library so HAS_TTS is set.
# WITH_TTS compiles the TTS JNI bindings + factory + router. Individual
# engines must be enabled with their -DENABLE_* flag and provide a real
# backend library. The factory returns nullptr (never synthetic audio)
# when no backend is compiled in.
option(WITH_TTS "Compile TTS JNI bindings (engines need real backends)" ON)
option(ENABLE_SUPERTONIC "Enable Supertonic TTS (ONNX)" OFF)
option(ENABLE_KOKORO "Enable Kokoro TTS (ONNX)" OFF)
option(ENABLE_SHERPA_ONNX_TTS "Enable sherpa-onnx TTS" OFF)

if(WITH_TTS)
    message(STATUS "✓ TTS Engine Fleet enabled")
    
    # Core TTS library (tts_router.h is header-only)
    # Note: tts_jni.cpp is in the main shared lib for symbol visibility
    add_library(tts_engine STATIC
        tts/tts_engine_factory.cpp
        tts/vits_onnx_engine.cpp
    )
    target_include_directories(tts_engine PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/tts
        ${CMAKE_CURRENT_SOURCE_DIR}/common
        ${CMAKE_CURRENT_SOURCE_DIR}/jni
        ${CMAKE_CURRENT_SOURCE_DIR}
    )
    target_link_libraries(tts_engine stt_common ${log-lib})
    if(HAS_ONNX)
        # The VITS engine runs through the vendored ONNX Runtime.
        target_link_libraries(tts_engine onnx_prebuilt)
        target_include_directories(tts_engine PRIVATE onnx/include)
        target_compile_definitions(tts_engine PRIVATE WITH_ONNX=1)
    endif()
    target_compile_definitions(tts_engine PRIVATE WITH_TTS=1)

    # Supertonic (ONNX)
    if(ENABLE_SUPERTONIC)
        target_compile_definitions(tts_engine PRIVATE ENABLE_SUPERTONIC=1)
        message(STATUS "  ✓ Supertonic (ONNX) enabled")
    endif()

    # Kokoro (ONNX)
    if(ENABLE_KOKORO)
        target_compile_definitions(tts_engine PRIVATE ENABLE_KOKORO=1)
        message(STATUS "  ✓ Kokoro (ONNX) enabled")
    endif()

    # sherpa-onnx TTS
    if(ENABLE_SHERPA_ONNX_TTS)
        target_compile_definitions(tts_engine PRIVATE ENABLE_SHERPA_ONNX_TTS=1)
        message(STATUS "  ✓ sherpa-onnx TTS enabled")
    endif()

    if(NOT ENABLE_SUPERTONIC AND NOT ENABLE_KOKORO AND NOT ENABLE_SHERPA_ONNX_TTS)
        message(STATUS "  ⚠ TTS compiled without any backend — createEngine() will return nullptr")
    endif()
    
    set(HAS_TTS TRUE)
else()
    message(STATUS "✗ TTS Engines disabled")
    set(HAS_TTS FALSE)
endif()

# =============================================================================
# 7.2 Generate Config Header
# =============================================================================
set(STT_CAPABILITY_MASK 0)
if(HAS_WHISPER)
    math(EXPR STT_CAPABILITY_MASK "${STT_CAPABILITY_MASK} | 1")
endif()
if(HAS_VOSK)
    math(EXPR STT_CAPABILITY_MASK "${STT_CAPABILITY_MASK} | 2")
endif()
if(HAS_ONNX)
    math(EXPR STT_CAPABILITY_MASK "${STT_CAPABILITY_MASK} | 4")
endif()



set(WITH_WHISPER_VAL 0)
set(WITH_VOSK_VAL 0)
set(WITH_ONNX_VAL 0)
set(WITH_FFMPEG_VAL 0)
set(WITH_OPENCV_VAL 0)
if(HAS_WHISPER)
    set(WITH_WHISPER_VAL 1)
endif()
if(HAS_VOSK)
    set(WITH_VOSK_VAL 1)
endif()
if(HAS_ONNX)
    set(WITH_ONNX_VAL 1)
endif()



configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/common/stt_config.h.in"
    "${CMAKE_CURRENT_BINARY_DIR}/stt_config.h"
)

# =============================================================================
# 7.5. JNI Helper Library
# =============================================================================
add_library(jni_helper STATIC
    jni/jni_helper.cpp
)
target_include_directories(jni_helper PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/jni)
target_link_libraries(jni_helper ${log-lib})

# =============================================================================
# 8. Main JNI Library (exported)
# =============================================================================
set(COMMON_JNI_SOURCES
    speech/speech_jni.cpp
    speech/speech_session.cpp
    router/stt_jni.cpp
    router/engine_router.cpp
    core/core_jni.cpp
    common_jni_bridge.cpp
)

# Add OpenCV JNI if available


# Add TTS JNI if available (must be in shared lib for symbol visibility)
if(HAS_TTS)
    list(APPEND COMMON_JNI_SOURCES tts/tts_jni.cpp)
endif()

# Add Marian translation JNI if ONNX Runtime is present.
if(HAS_ONNX)
    list(APPEND COMMON_JNI_SOURCES marian/marian_jni.cpp)
endif()







# Add media editing JNI if FFmpeg is present (media/media_jni.cpp implements
# the Java-side bindings for the media_engine static library).


add_library(common_jni SHARED ${COMMON_JNI_SOURCES})

target_include_directories(common_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/common
    ${CMAKE_CURRENT_SOURCE_DIR}/jni
    ${CMAKE_CURRENT_SOURCE_DIR}/tts
    ${CMAKE_CURRENT_SOURCE_DIR}/marian
    ${CMAKE_CURRENT_SOURCE_DIR}/onnx
    ${CMAKE_CURRENT_SOURCE_DIR}/onnx/include
)

# Add OpenCV include path if available


# Symbol visibility: only export JNI_OnLoad, JNI_OnUnload, and Java_* functions.
set(EXPORTS_MAP "${CMAKE_CURRENT_SOURCE_DIR}/jni/exports.map")
if(NOT EXISTS "${EXPORTS_MAP}")
    message(FATAL_ERROR "JNI export map is missing: ${EXPORTS_MAP}")
endif()
target_link_options(common_jni PRIVATE "-Wl,--version-script=${EXPORTS_MAP}")
set_property(TARGET common_jni APPEND PROPERTY LINK_DEPENDS "${EXPORTS_MAP}")
message(STATUS "✓ Using version script for symbol visibility")

# Hide all symbols by default
target_compile_options(common_jni PRIVATE -fvisibility=hidden)

target_link_libraries(common_jni jni_helper stt_common stt_core ${log-lib} ${android-lib} ${jnigraphics-lib} ${CMAKE_DL_LIBS})

if(HAS_WHISPER)
    target_link_libraries(common_jni whisper_engine)
    target_compile_definitions(common_jni PRIVATE WITH_WHISPER=1)
endif()

if(HAS_VOSK)
    target_link_libraries(common_jni vosk_engine)
    target_compile_definitions(common_jni PRIVATE WITH_VOSK=1)
endif()

if(HAS_ONNX)
    target_link_libraries(common_jni onnx_engine marian_engine)
    target_compile_definitions(common_jni PRIVATE WITH_ONNX=1 MARIAN_AVAILABLE=1)
endif()









if(HAS_TTS)
    target_link_libraries(common_jni tts_engine)
    target_compile_definitions(common_jni PRIVATE WITH_TTS=1)
    target_include_directories(common_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/tts)
endif()

# =============================================================================
# Summary
# =============================================================================
message(STATUS "")
message(STATUS "=== Common JNI Module Configuration ===")
message(STATUS "  Whisper:  ${HAS_WHISPER}")
message(STATUS "  Vosk:     ${HAS_VOSK}")
message(STATUS "  ONNX:     ${HAS_ONNX}")
message(STATUS "  FFmpeg:   ${HAS_FFMPEG}")
message(STATUS "  OpenCV:   ${HAS_OPENCV}")
message(STATUS "  TTS:      ${HAS_TTS}")
message(STATUS "  Marian:   ${HAS_ONNX}")
message(STATUS "  Capability Mask: ${STT_CAPABILITY_MASK}")
message(STATUS "========================================")
