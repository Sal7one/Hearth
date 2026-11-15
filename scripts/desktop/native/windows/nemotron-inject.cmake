# Windows x64 twin of scripts/speech/nemo-inject.cmake, loaded by CMAKE_PROJECT_nemo_speech_INCLUDE
# after upstream defines its targets. The upstream C ABI library becomes Hearth's backend DLL.
function(hearth_configure_nemo)
  target_sources(nemo_speech_asr_c PRIVATE "${HEARTH_CPP}/speech/backends/nemotron_backend.cpp")
  set_source_files_properties("${HEARTH_CPP}/speech/backends/nemotron_backend.cpp" TARGET_DIRECTORY nemo_speech_asr_c
    PROPERTIES COMPILE_OPTIONS "/FI${HEARTH_WINDOWS_COMPAT}/hearth_windows_compat.h")
  set_target_properties(nemo_speech_asr_c PROPERTIES OUTPUT_NAME hearth_nemotron)
  set_property(TARGET nemo_speech_asr_c PROPERTY SOVERSION)
  set_property(TARGET nemo_speech_asr_c PROPERTY VERSION)
  target_link_options(nemo_speech_asr_c PRIVATE /EXPORT:hearth_speech_backend_v1)
endfunction()
cmake_language(DEFER CALL hearth_configure_nemo)
