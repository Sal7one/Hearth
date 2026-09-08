#ifndef STT_JNI_UTILS_H
#define STT_JNI_UTILS_H

#include <jni.h>
#include <string>
#include "logging.h"
#include "../jni/jni_helper.h"

namespace stt {

// ═══════════════════════════════════════════════════════════════════════════
// Exception Checking Macros
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Check for JNI exceptions and clear them.
 * Use after any JNI call that might throw (NewStringUTF, CallMethod, etc.)
 * Returns nullptr if exception occurred.
 */
#define JNI_CHECK_EXCEPTION(env) \
    do { \
        if ((env)->ExceptionCheck()) { \
            LOG_E("JNI", "Exception occurred at %s:%d", __FILE__, __LINE__); \
            (env)->ExceptionDescribe(); \
            (env)->ExceptionClear(); \
            return nullptr; \
        } \
    } while(0)

/**
 * Check for JNI exceptions - void return version.
 */
#define JNI_CHECK_EXCEPTION_VOID(env) \
    do { \
        if ((env)->ExceptionCheck()) { \
            LOG_E("JNI", "Exception occurred at %s:%d", __FILE__, __LINE__); \
            (env)->ExceptionDescribe(); \
            (env)->ExceptionClear(); \
            return; \
        } \
    } while(0)

/**
 * Check for JNI exceptions - returns specified value on exception.
 */
#define JNI_CHECK_EXCEPTION_RET(env, ret_val) \
    do { \
        if ((env)->ExceptionCheck()) { \
            LOG_E("JNI", "Exception occurred at %s:%d", __FILE__, __LINE__); \
            (env)->ExceptionDescribe(); \
            (env)->ExceptionClear(); \
            return (ret_val); \
        } \
    } while(0)

// ═══════════════════════════════════════════════════════════════════════════
// String Utilities
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Convert jstring to std::string safely.
 * Returns empty string if input is null.
 */
inline std::string jstringToString(JNIEnv* env, jstring str) {
    if (!str) return "";

    jni::JStringGuard value(env, str);
    if (!value.valid()) {
        if (!env->ExceptionCheck()) {
            const std::string message = value.error().empty()
                ? "Failed to read Java string"
                : value.error();
            jni::throwIllegalArgumentException(env, message.c_str());
        }
        return "";
    }
    return value.str();
}

/**
 * Create jstring from std::string safely.
 * Returns nullptr on error.
 */
inline jstring stringToJstring(JNIEnv* env, const std::string& str) {
    jstring result = jni::utf8ToJString(env, str);
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    return result;
}

// ═══════════════════════════════════════════════════════════════════════════
// Array Guards (RAII)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * RAII wrapper for JNI critical array access.
 * Uses GetPrimitiveArrayCritical for zero-copy access when possible.
 * 
 * IMPORTANT: While holding a critical array, you CANNOT:
 * - Call any JNI function that might allocate (NewStringUTF, etc.)
 * - Call any function that might block
 * Release the guard before doing any of these!
 */
template<typename JArrayType, typename ElementType>
class CriticalArrayGuard {
public:
    CriticalArrayGuard(JNIEnv* env, JArrayType array) 
        : env_(env), array_(array), ptr_(nullptr) {
        if (array) {
            ptr_ = static_cast<ElementType*>(
                env->GetPrimitiveArrayCritical(array, nullptr)
            );
        }
    }
    
    ~CriticalArrayGuard() {
        release();
    }
    
    void release() {
        if (ptr_) {
            env_->ReleasePrimitiveArrayCritical(array_, ptr_, JNI_ABORT);
            ptr_ = nullptr;
        }
    }
    
    ElementType* get() { return ptr_; }
    const ElementType* get() const { return ptr_; }
    operator bool() const { return ptr_ != nullptr; }
    
    // Non-copyable
    CriticalArrayGuard(const CriticalArrayGuard&) = delete;
    CriticalArrayGuard& operator=(const CriticalArrayGuard&) = delete;
    
    // Movable
    CriticalArrayGuard(CriticalArrayGuard&& other) noexcept 
        : env_(other.env_), array_(other.array_), ptr_(other.ptr_) {
        other.ptr_ = nullptr;
    }
    
private:
    JNIEnv* env_;
    JArrayType array_;
    ElementType* ptr_;
};

using ShortArrayGuard = CriticalArrayGuard<jshortArray, int16_t>;
using FloatArrayGuard = CriticalArrayGuard<jfloatArray, float>;
using ByteArrayGuard = CriticalArrayGuard<jbyteArray, int8_t>;

// ═══════════════════════════════════════════════════════════════════════════
// Global Reference Manager
// ═══════════════════════════════════════════════════════════════════════════

/**
 * RAII wrapper for JNI global references.
 * Automatically deletes the global reference when destroyed.
 */
template<typename T>
class GlobalRef {
public:
    GlobalRef() : env_(nullptr), ref_(nullptr) {}
    
    GlobalRef(JNIEnv* env, T localRef) : env_(env), ref_(nullptr) {
        if (localRef) {
            ref_ = static_cast<T>(env->NewGlobalRef(localRef));
        }
    }
    
    ~GlobalRef() {
        reset();
    }
    
    void reset(JNIEnv* env = nullptr, T localRef = nullptr) {
        if (ref_ && env_) {
            env_->DeleteGlobalRef(ref_);
        }
        env_ = env;
        if (localRef && env) {
            ref_ = static_cast<T>(env->NewGlobalRef(localRef));
        } else {
            ref_ = nullptr;
        }
    }
    
    T get() const { return ref_; }
    operator T() const { return ref_; }
    operator bool() const { return ref_ != nullptr; }
    
    // Non-copyable
    GlobalRef(const GlobalRef&) = delete;
    GlobalRef& operator=(const GlobalRef&) = delete;
    
    // Movable
    GlobalRef(GlobalRef&& other) noexcept : env_(other.env_), ref_(other.ref_) {
        other.ref_ = nullptr;
    }
    
private:
    JNIEnv* env_;
    T ref_;
};

// ═══════════════════════════════════════════════════════════════════════════
// JVM Access
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Get JNIEnv for current thread.
 * Attaches thread if necessary.
 * Returns nullptr on failure.
 */
inline JNIEnv* getEnvForCurrentThread(JavaVM* jvm) {
    if (!jvm) return nullptr;
    
    JNIEnv* env = nullptr;
    int status = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    
    if (status == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOG_E("JNI", "Failed to attach thread");
            return nullptr;
        }
    } else if (status != JNI_OK) {
        LOG_E("JNI", "Failed to get JNI environment: %d", status);
        return nullptr;
    }
    
    return env;
}

} // namespace stt

#endif // STT_JNI_UTILS_H
