cmake_minimum_required(VERSION 3.22)
project(hearth_whisper_vulkan LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
if(ANDROID AND NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "The optional Whisper Vulkan runtime is ARM64 only")
endif()
find_package(SPIRV-Headers CONFIG REQUIRED)
add_subdirectory("${WHISPER_ROOT}/ggml" ggml)
target_link_libraries(ggml-vulkan PRIVATE SPIRV-Headers::SPIRV-Headers)
add_library(hearth_whisper_vulkan SHARED
  "${PATCHED_WHISPER}" "${CPP}/whisper/whisper_vulkan_plugin.cpp")
target_include_directories(hearth_whisper_vulkan PRIVATE
  "${WHISPER_ROOT}/include" "${WHISPER_ROOT}/src" "${CPP}/whisper")
target_compile_definitions(hearth_whisper_vulkan PRIVATE WHISPER_VERSION="1.8.2")
target_compile_options(hearth_whisper_vulkan PRIVATE -fvisibility=hidden)
target_link_libraries(hearth_whisper_vulkan PRIVATE ggml)
if(ANDROID)
  target_link_libraries(hearth_whisper_vulkan PRIVATE log)
  target_link_options(hearth_whisper_vulkan PRIVATE
    -Wl,--exclude-libs,ALL -Wl,-Bsymbolic -Wl,-z,max-page-size=16384
    "-Wl,--version-script=${CPP}/whisper/vulkan_exports.map")
elseif(APPLE)
  target_link_options(hearth_whisper_vulkan PRIVATE
    -Wl,-exported_symbol,_hearth_whisper_vulkan_api_v1)
endif()

if(NOT ANDROID)
  # Keep the host's CPU table independent too: production common_jni never
  # links Vulkan. Only the explicitly selected optional DSO contains it.
  file(GLOB CPU_GGML "${WHISPER_ROOT}/ggml/src/*.c" "${WHISPER_ROOT}/ggml/src/*.cpp")
  file(GLOB_RECURSE CPU_KERNELS "${WHISPER_ROOT}/ggml/src/ggml-cpu/*.c" "${WHISPER_ROOT}/ggml/src/ggml-cpu/*.cpp")
  list(FILTER CPU_KERNELS EXCLUDE REGEX ".*/arch/(powerpc|s390|riscv|loongarch|avx)/.*")
  list(FILTER CPU_KERNELS EXCLUDE REGEX ".*/(kleidiai|spacemit)/.*")
  add_library(whisper_cpu STATIC "${WHISPER_ROOT}/src/whisper.cpp" ${CPU_GGML} ${CPU_KERNELS})
  target_include_directories(whisper_cpu PUBLIC
    "${WHISPER_ROOT}/include" "${WHISPER_ROOT}/ggml/include"
    "${WHISPER_ROOT}/ggml/src" "${WHISPER_ROOT}/ggml/src/ggml-cpu" "${WHISPER_ROOT}/src")
  target_compile_definitions(whisper_cpu PRIVATE GGML_USE_CPU=1 WHISPER_VERSION="1.8.2"
    GGML_VERSION="0.9.4" GGML_COMMIT="19ceec8" GGML_SCHED_MAX_COPIES=4)
  add_executable(whisper_gpu_smoke smoke.cpp
    "${CPP}/whisper/whisper_engine.cpp" "${CPP}/common/json_utils.cpp"
    "${CPP}/common/engine_interface.cpp" "${CPP}/common/logging.cpp")
  target_compile_definitions(whisper_gpu_smoke PRIVATE WHISPER_AVAILABLE=1)
  target_include_directories(whisper_gpu_smoke PRIVATE "${CPP}/whisper" "${CPP}/common")
  target_link_libraries(whisper_gpu_smoke PRIVATE whisper_cpu ${CMAKE_DL_LIBS})
endif()
