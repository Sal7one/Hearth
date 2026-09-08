#ifndef STT_LOGGING_H
#define STT_LOGGING_H

#include <string>

// Platform log sink.
//
// The core must compile off-Android (iOS, macOS, Linux, Windows host tests).
// Android uses logcat; every other platform gets a stderr sink with the same
// macro surface and compile-time gating. Call sites never branch on platform.
// os_log is deliberately NOT used on Apple: its format-string dialect differs
// from printf and would silently mangle existing call sites.
#if defined(__ANDROID__)
#include <android/log.h>
#define STT_LOG_ANDROID 1
#else
#include <cstdio>
#define STT_LOG_STDERR 1
#endif

#define TAG_STT     "STT"
#define TAG_WHISPER "Whisper"
#define TAG_VOSK    "Vosk"
#define TAG_ONNX    "ONNX"
#define TAG_FFMPEG  "FFmpeg"
#define TAG_ROUTER  "Router"

// Format string safety: GCC/Clang attribute for printf-style format checking
#ifdef __GNUC__
#define FORMAT_PRINTF(fmt_idx, arg_idx) __attribute__((format(printf, fmt_idx, arg_idx)))
#else
#define FORMAT_PRINTF(fmt_idx, arg_idx)
#endif

// --- Sink primitives ---------------------------------------------------------
// STT_LOG_EMIT(level_tag, tag, fmt, ...) emits one complete line. `tag` may be
// a runtime const char* (it is passed as an argument, never concatenated);
// `fmt` must be a string literal (enforced by FORMAT_PRINTF on wrappers and
// required for compiler format checking). Every platform appends the trailing
// newline so call sites never include one.
#if defined(STT_LOG_ANDROID)
#define STT_LOG_EMIT(android_level, level_tag, tag, fmt, ...) \
    __android_log_print(android_level, tag, fmt, ##__VA_ARGS__)
#elif defined(_MSC_VER)
// MSVC's traditional preprocessor drops the comma for empty __VA_ARGS__.
#define STT_LOG_EMIT(android_level, level_tag, tag, fmt, ...) \
    fprintf(stderr, "%s/%s: " fmt "\n", level_tag, tag, __VA_ARGS__)
#else
#define STT_LOG_EMIT(android_level, level_tag, tag, fmt, ...) \
    fprintf(stderr, "%s/%s: " fmt "\n", level_tag, tag, ##__VA_ARGS__)
#endif

// Safe logging macros with format checking.
//
// Compile-time gating:
//   - NDEBUG builds drop LOG_V / LOG_D to no-ops (varargs NOT evaluated).
//   - Define DEBUG_LOGGING to force-enable LOG_V / LOG_D in release builds.
//   - LOG_I / LOG_W / LOG_E always emit.
#if defined(DEBUG_LOGGING) || !defined(NDEBUG)
#define LOG_V(tag, fmt, ...) STT_LOG_EMIT(ANDROID_LOG_VERBOSE, "V", tag, fmt, ##__VA_ARGS__)
#define LOG_D(tag, fmt, ...) STT_LOG_EMIT(ANDROID_LOG_DEBUG,   "D", tag, fmt, ##__VA_ARGS__)
#else
#define LOG_V(tag, fmt, ...) ((void)0)
#define LOG_D(tag, fmt, ...) ((void)0)
#endif
#define LOG_I(tag, fmt, ...) STT_LOG_EMIT(ANDROID_LOG_INFO,  "I", tag, fmt, ##__VA_ARGS__)
#define LOG_W(tag, fmt, ...) STT_LOG_EMIT(ANDROID_LOG_WARN,  "W", tag, fmt, ##__VA_ARGS__)
#define LOG_E(tag, fmt, ...) STT_LOG_EMIT(ANDROID_LOG_ERROR, "E", tag, fmt, ##__VA_ARGS__)

#ifdef VERBOSE_LOGGING
#define LOG_VERBOSE(tag, ...) LOG_V(tag, __VA_ARGS__)
#else
#define LOG_VERBOSE(tag, ...) ((void)0)
#endif

namespace stt {

// Legacy ErrorStore - wraps ThreadLocalError for backward compatibility
class ErrorStore {
public:
    static void set(const std::string& error);
    static std::string get();
    static void clear();
private:
    static thread_local std::string lastError_;
};

} // namespace stt

// Legacy macros - use error_codes.h macros for new code
// Note: SET_ERROR is now defined in error_codes.h with ErrorCode support
// These are kept for backward compatibility only
#define GET_ERROR() stt::ErrorStore::get()
#ifndef CLEAR_ERROR
#define CLEAR_ERROR() stt::ErrorStore::clear()
#endif

#endif // STT_LOGGING_H
