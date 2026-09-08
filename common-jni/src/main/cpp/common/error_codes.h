#ifndef ERROR_CODES_H
#define ERROR_CODES_H

#include <string>
#include <cstring>

namespace stt {

/**
 * Error codes matching Kotlin sealed class NativeError.
 * Keep in sync with CommonJni.kt
 */
enum class ErrorCode : int {
    OK = 0,
    
    // General errors (1-99)
    UNKNOWN = 1,
    INVALID_ARGUMENT = 2,
    INVALID_STATE = 3,
    NOT_INITIALIZED = 4,
    ALREADY_INITIALIZED = 5,
    OUT_OF_MEMORY = 6,
    
    // Handle errors (100-199)
    INVALID_HANDLE = 100,
    HANDLE_NOT_FOUND = 101,
    HANDLE_ALREADY_RELEASED = 102,
    
    // Engine errors (200-299)
    ENGINE_NOT_AVAILABLE = 200,
    ENGINE_LOAD_FAILED = 201,
    ENGINE_INFERENCE_FAILED = 202,
    ENGINE_CANCELLED = 203,
    ENGINE_CREATE_FAILED = 204,
    INIT_FAILED = 205,
    
    // Memory errors (250-299)
    MEMORY_ERROR = 250,
    
    // Model errors (300-399)
    MODEL_NOT_FOUND = 300,
    MODEL_LOAD_FAILED = 301,
    MODEL_INVALID_FORMAT = 302,
    MODEL_VERSION_MISMATCH = 303,
    
    // Audio errors (400-499)
    AUDIO_DECODE_FAILED = 400,
    AUDIO_ENCODE_FAILED = 401,
    AUDIO_INVALID_FORMAT = 402,
    AUDIO_FILE_NOT_FOUND = 403,
    
    // FFmpeg errors (500-599)
    FFMPEG_NOT_AVAILABLE = 500,
    FFMPEG_OPEN_FAILED = 501,
    FFMPEG_DECODE_FAILED = 502,
    FFMPEG_ENCODE_FAILED = 503,
    FFMPEG_NO_AUDIO_STREAM = 504,
    FFMPEG_ENCODER_NOT_FOUND = 505,
    
    // Threading errors (600-699)
    THREAD_ATTACH_FAILED = 600,
    THREAD_DETACH_FAILED = 601,
    DEADLOCK_DETECTED = 602,
};

/**
 * Thread-local error storage.
 * Each thread has its own error state to avoid races.
 */
class ThreadLocalError {
public:
    static ThreadLocalError& get() {
        thread_local ThreadLocalError instance;
        return instance;
    }
    
    void set(ErrorCode code, const std::string& message) {
        code_ = code;
        message_ = message;
    }
    
    void set(ErrorCode code, const char* message) {
        code_ = code;
        message_ = message ? message : "";
    }
    
    void clear() {
        code_ = ErrorCode::OK;
        message_.clear();
    }
    
    ErrorCode code() const { return code_; }
    const std::string& message() const { return message_; }
    bool hasError() const { return code_ != ErrorCode::OK; }
    
    // Format as "CODE: message"
    std::string format() const {
        if (!hasError()) return "";
        return std::to_string(static_cast<int>(code_)) + ": " + message_;
    }

private:
    ThreadLocalError() : code_(ErrorCode::OK) {}
    
    ErrorCode code_;
    std::string message_;
};

// Convenience macros for setting errors with error codes
#define SET_ERROR_CODE(code, msg) \
    stt::ThreadLocalError::get().set(stt::ErrorCode::code, msg)

#define SET_ERROR_FMT(code, fmt, ...) \
    do { \
        char __buf[512]; \
        snprintf(__buf, sizeof(__buf), fmt, ##__VA_ARGS__); \
        stt::ThreadLocalError::get().set(stt::ErrorCode::code, __buf); \
    } while(0)

#define CLEAR_THREAD_ERROR() \
    stt::ThreadLocalError::get().clear()

#define GET_ERROR_CODE() \
    stt::ThreadLocalError::get().code()

#define GET_ERROR_MESSAGE() \
    stt::ThreadLocalError::get().message()

#define HAS_ERROR() \
    stt::ThreadLocalError::get().hasError()

/**
 * RAII guard that clears error on scope exit (optional).
 */
class ErrorGuard {
public:
    ErrorGuard(bool clearOnExit = false) : clearOnExit_(clearOnExit) {
        CLEAR_THREAD_ERROR();
    }
    ~ErrorGuard() {
        if (clearOnExit_) CLEAR_THREAD_ERROR();
    }
private:
    bool clearOnExit_;
};

// Simple SET_ERROR for string messages (no error code)
#define SET_ERROR(msg) \
    stt::ThreadLocalError::get().set(stt::ErrorCode::UNKNOWN, msg)

} // namespace stt

#endif // ERROR_CODES_H

