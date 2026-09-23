cmake_minimum_required(VERSION 3.22)
project(hearth_whisper_host_benchmark LANGUAGES C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WHISPER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../common-jni/src/main/cpp/whisper/upstream")
if(NOT EXISTS "${WHISPER_ROOT}/src/whisper.cpp")
  message(FATAL_ERROR "Vendored whisper.cpp source is missing: ${WHISPER_ROOT}")
endif()

file(GLOB GGML_SOURCES "${WHISPER_ROOT}/ggml/src/*.c" "${WHISPER_ROOT}/ggml/src/*.cpp")
file(GLOB_RECURSE GGML_CPU_SOURCES
  "${WHISPER_ROOT}/ggml/src/ggml-cpu/*.c"
  "${WHISPER_ROOT}/ggml/src/ggml-cpu/*.cpp")
list(FILTER GGML_CPU_SOURCES EXCLUDE REGEX ".*/arch/(powerpc|s390|riscv|loongarch|avx)/.*")
list(FILTER GGML_CPU_SOURCES EXCLUDE REGEX ".*/(kleidiai|spacemit)/.*")
list(APPEND GGML_SOURCES ${GGML_CPU_SOURCES})

add_library(hearth_whisper_core STATIC "${WHISPER_ROOT}/src/whisper.cpp" ${GGML_SOURCES})
target_include_directories(hearth_whisper_core PUBLIC
  "${WHISPER_ROOT}/include"
  "${WHISPER_ROOT}/ggml/include"
  "${WHISPER_ROOT}/ggml/src"
  "${WHISPER_ROOT}/ggml/src/ggml-cpu"
  "${WHISPER_ROOT}/src")
target_compile_definitions(hearth_whisper_core PRIVATE
  GGML_USE_CPU=1
  WHISPER_VERSION="1.8.2"
  GGML_VERSION="0.9.4"
  GGML_COMMIT="19ceec8"
  GGML_SCHED_MAX_COPIES=4)
add_executable(hearth_whisper_host main.cpp)
target_link_libraries(hearth_whisper_host PRIVATE hearth_whisper_core)
