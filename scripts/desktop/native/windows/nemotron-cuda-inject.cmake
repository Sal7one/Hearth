# The NVIDIA CUDA build of Hearth's Nemotron backend for Windows x64, loaded by
# CMAKE_PROJECT_nemo_speech_INCLUDE after upstream defines its targets. The CPU backend
# (nemotron-inject.cmake) stays a separate DLL: this one imports the NVIDIA driver and is
# loaded only when a session asks for CUDA.
#
# Upstream gives its cuBLAS stand-in NVIDIA's own file name so that it replaces the real
# library at run time. Hearth links the stand-in directly, under its own name. Nothing in the
# bundle is then named like an NVIDIA library, a real cuBLAS loaded by another engine cannot be
# mistaken for it, and a call it does not implement fails this link instead of a user's session.
function(hearth_configure_nemo_cuda)
  if(NOT TARGET ggml-cuda OR NOT TARGET nemo_speech_cublas_shim)
    message(FATAL_ERROR "Pinned NeMo-Speech.cpp no longer defines ggml-cuda and its cuBLAS shim; review Hearth's CUDA build")
  endif()
  set_target_properties(nemo_speech_cublas_shim PROPERTIES OUTPUT_NAME hearth_nemotron_cublas)
  foreach(property LINK_LIBRARIES INTERFACE_LINK_LIBRARIES)
    get_target_property(libraries ggml-cuda ${property})
    string(REGEX MATCHALL "CUDA::cublas[A-Za-z_]*" matches "${libraries}")
    if(NOT matches STREQUAL "CUDA::cublas")
      message(FATAL_ERROR "ggml-cuda ${property} no longer names exactly CUDA::cublas (found: ${matches}); review Hearth's CUDA build")
    endif()
    string(REPLACE "CUDA::cublas" "nemo_speech_cublas_shim" libraries "${libraries}")
    set_property(TARGET ggml-cuda PROPERTY ${property} "${libraries}")
  endforeach()

  target_sources(nemo_speech_asr_c PRIVATE "${HEARTH_NEMOTRON_SOURCE}" "${HEARTH_WINDOWS_COMPAT}/nemotron_cuda.cpp")
  set_source_files_properties("${HEARTH_NEMOTRON_SOURCE}" TARGET_DIRECTORY nemo_speech_asr_c
    PROPERTIES COMPILE_OPTIONS "/FI${HEARTH_WINDOWS_COMPAT}/hearth_windows_compat.h")
  # The prepared adapter lives in the build tree; its sibling headers stay in the source tree.
  target_include_directories(nemo_speech_asr_c PRIVATE "${HEARTH_CPP}/speech/backends" "${HEARTH_CPP}/speech"
    "${HEARTH_WINDOWS_COMPAT}" "${CMAKE_SOURCE_DIR}/ggml/include" ${CUDAToolkit_INCLUDE_DIRS})
  set_property(TARGET nemo_speech_asr_c APPEND PROPERTY LINK_LIBRARIES CUDA::cudart_static)
  set_target_properties(nemo_speech_asr_c PROPERTIES OUTPUT_NAME hearth_nemotron_cuda)
  set_property(TARGET nemo_speech_asr_c PROPERTY SOVERSION)
  set_property(TARGET nemo_speech_asr_c PROPERTY VERSION)
  target_link_options(nemo_speech_asr_c PRIVATE /EXPORT:hearth_speech_backend_v1)
endfunction()
cmake_language(DEFER CALL hearth_configure_nemo_cuda)
