# Fold the desktop JNI adapter into the pinned NeMo-Speech.cpp TTS target.
#
# This file is consumed through CMAKE_PROJECT_TOP_LEVEL_INCLUDES by the standalone
# NeMo-Speech.cpp configure in scripts/desktop/build-native.py. It never edits the
# pinned source checkout; it only attaches Hearth's adapter and restricts exports.
# The C ABI and the JNI adapter live in one dylib, which statically owns the single
# pinned ggml build. No ggml/ORT/sherpa copy is linked into any other dylib.
function(hearth_finalize_magpie)
    if(NOT TARGET nemo_speech_tts)
        return()
    endif()
    get_target_property(already_finalized nemo_speech_tts HEARTH_MAGPIE_FINALIZED)
    if(already_finalized)
        return()
    endif()
    set_target_properties(nemo_speech_tts PROPERTIES HEARTH_MAGPIE_FINALIZED TRUE)
    set_target_properties(nemo_speech_tts PROPERTIES
        OUTPUT_NAME hearth_desktop_magpie
        BUILD_WITH_INSTALL_RPATH OFF
        INSTALL_RPATH "@loader_path")
    if(NOT EXISTS "${HEARTH_MAGPIE_JNI_SOURCE}")
        message(FATAL_ERROR "HEARTH_MAGPIE_JNI_SOURCE does not exist: ${HEARTH_MAGPIE_JNI_SOURCE}")
    endif()
    target_sources(nemo_speech_tts PRIVATE "${HEARTH_MAGPIE_JNI_SOURCE}")
    target_include_directories(nemo_speech_tts PRIVATE
        ${HEARTH_MAGPIE_JNI_INCLUDE_DIRS}
        "${HEARTH_MAGPIE_COMMON_CPP}"
        "${HEARTH_MAGPIE_ROOT}/hearth-tts-magpie/src/main/cpp")
    if(APPLE)
        get_target_property(options nemo_speech_tts LINK_OPTIONS)
        if(NOT options)
            set(options "")
        endif()
        list(FILTER options EXCLUDE REGEX "version-script")
        list(APPEND options
            "-Wl,-exported_symbols_list,${HEARTH_MAGPIE_EXPORTS}"
            "-Wl,-dead_strip")
        set_target_properties(nemo_speech_tts PROPERTIES LINK_OPTIONS "${options}")
    endif()
endfunction()
cmake_language(DEFER CALL hearth_finalize_magpie)
