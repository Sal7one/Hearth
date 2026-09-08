#ifndef JNI_HELPER_H
#define JNI_HELPER_H

#include <jni.h>
#include <limits>
#include <new>
#include <string>
#include <vector>
#include <android/log.h>
#include "../common/utf8_utils.h"

namespace jni {

// ============================================================================
// Global JVM Reference (set in JNI_OnLoad)
// ============================================================================
extern JavaVM* g_jvm;

// Get JNIEnv for current thread (attaches if needed)
JNIEnv* getEnv();

// ============================================================================
// Safe conversion: Java UTF-16 → standard UTF-8
// ============================================================================
class JStringGuard {
public:
    JStringGuard(JNIEnv* env, jstring str) 
        : valid_(false) {
        if (!env || !str) return;

        const jsize length = env->GetStringLength(str);
        if (env->ExceptionCheck()) return;
        const jchar* chars = env->GetStringChars(str, nullptr);
        if (!chars) return;
        try {
            valid_ = common_jni::text::utf16ToUtf8(
                chars,
                static_cast<std::size_t>(length),
                value_,
                &error_
            );
        } catch (const std::bad_alloc&) {
            env->ReleaseStringChars(str, chars);
            jclass type = env->FindClass("java/lang/OutOfMemoryError");
            if (type) {
                env->ThrowNew(type, "Unable to allocate native UTF-8 string");
                env->DeleteLocalRef(type);
            }
            return;
        } catch (...) {
            env->ReleaseStringChars(str, chars);
            jclass type = env->FindClass("java/lang/RuntimeException");
            if (type) {
                env->ThrowNew(type, "Unable to convert Java UTF-16 string");
                env->DeleteLocalRef(type);
            }
            return;
        }
        env->ReleaseStringChars(str, chars);
    }

    ~JStringGuard() = default;
    
    const char* get() const { return valid_ ? value_.c_str() : nullptr; }
    const std::string& str() const { return value_; }
    const std::string& error() const { return error_; }
    bool valid() const { return valid_; }
    
    // Non-copyable
    JStringGuard(const JStringGuard&) = delete;
    JStringGuard& operator=(const JStringGuard&) = delete;

private:
    std::string value_;
    std::string error_;
    bool valid_;
};

// ============================================================================
// RAII Guard: jbyteArray → jbyte*
// ============================================================================
class JByteArrayGuard {
public:
    JByteArrayGuard(JNIEnv* env, jbyteArray arr, bool commit = false)
        : env_(env), arr_(arr), ptr_(nullptr), commit_(commit) {
        if (arr) {
            ptr_ = env->GetByteArrayElements(arr, nullptr);
            len_ = env->GetArrayLength(arr);
        }
    }
    
    ~JByteArrayGuard() {
        if (ptr_ && arr_) {
            env_->ReleaseByteArrayElements(arr_, ptr_, commit_ ? 0 : JNI_ABORT);
        }
    }
    
    jbyte* get() const { return ptr_; }
    jsize length() const { return len_; }
    bool valid() const { return ptr_ != nullptr; }
    
    JByteArrayGuard(const JByteArrayGuard&) = delete;
    JByteArrayGuard& operator=(const JByteArrayGuard&) = delete;

private:
    JNIEnv* env_;
    jbyteArray arr_;
    jbyte* ptr_;
    jsize len_ = 0;
    bool commit_;
};

// ============================================================================
// RAII Guard: jshortArray → jshort*
// ============================================================================
class JShortArrayGuard {
public:
    JShortArrayGuard(JNIEnv* env, jshortArray arr, bool commit = false)
        : env_(env), arr_(arr), ptr_(nullptr), commit_(commit) {
        if (arr) {
            ptr_ = env->GetShortArrayElements(arr, nullptr);
            len_ = env->GetArrayLength(arr);
        }
    }
    
    ~JShortArrayGuard() {
        if (ptr_ && arr_) {
            env_->ReleaseShortArrayElements(arr_, ptr_, commit_ ? 0 : JNI_ABORT);
        }
    }
    
    jshort* get() const { return ptr_; }
    jsize length() const { return len_; }
    bool valid() const { return ptr_ != nullptr; }
    
    JShortArrayGuard(const JShortArrayGuard&) = delete;
    JShortArrayGuard& operator=(const JShortArrayGuard&) = delete;

private:
    JNIEnv* env_;
    jshortArray arr_;
    jshort* ptr_;
    jsize len_ = 0;
    bool commit_;
};

// ============================================================================
// RAII Guard: jfloatArray → jfloat*
// ============================================================================
class JFloatArrayGuard {
public:
    JFloatArrayGuard(JNIEnv* env, jfloatArray arr, bool commit = false)
        : env_(env), arr_(arr), ptr_(nullptr), commit_(commit) {
        if (arr) {
            ptr_ = env->GetFloatArrayElements(arr, nullptr);
            len_ = env->GetArrayLength(arr);
        }
    }
    
    ~JFloatArrayGuard() {
        if (ptr_ && arr_) {
            env_->ReleaseFloatArrayElements(arr_, ptr_, commit_ ? 0 : JNI_ABORT);
        }
    }
    
    jfloat* get() const { return ptr_; }
    jsize length() const { return len_; }
    bool valid() const { return ptr_ != nullptr; }
    
    JFloatArrayGuard(const JFloatArrayGuard&) = delete;
    JFloatArrayGuard& operator=(const JFloatArrayGuard&) = delete;

private:
    JNIEnv* env_;
    jfloatArray arr_;
    jfloat* ptr_;
    jsize len_ = 0;
    bool commit_;
};

// ============================================================================
// RAII Guard: Critical Array Access (blocks GC - use sparingly!)
// ============================================================================
class JCriticalArrayGuard {
public:
    JCriticalArrayGuard(JNIEnv* env, jarray arr)
        : env_(env), arr_(arr), ptr_(nullptr) {
        if (arr) {
            ptr_ = env->GetPrimitiveArrayCritical(arr, nullptr);
        }
    }
    
    ~JCriticalArrayGuard() {
        if (ptr_ && arr_) {
            env_->ReleasePrimitiveArrayCritical(arr_, ptr_, 0);
        }
    }
    
    void* get() const { return ptr_; }
    bool valid() const { return ptr_ != nullptr; }
    
    template<typename T>
    T* as() const { return static_cast<T*>(ptr_); }
    
    JCriticalArrayGuard(const JCriticalArrayGuard&) = delete;
    JCriticalArrayGuard& operator=(const JCriticalArrayGuard&) = delete;

private:
    JNIEnv* env_;
    jarray arr_;
    void* ptr_;
};

// ============================================================================
// RAII Guard: Global Reference
// ============================================================================
class GlobalRefGuard {
public:
    GlobalRefGuard(JNIEnv* env, jobject obj)
        : env_(env), ref_(nullptr) {
        if (obj) {
            ref_ = env->NewGlobalRef(obj);
        }
    }
    
    ~GlobalRefGuard() {
        if (ref_) {
            env_->DeleteGlobalRef(ref_);
        }
    }
    
    jobject get() const { return ref_; }
    bool valid() const { return ref_ != nullptr; }
    
    // Release ownership (caller takes responsibility)
    jobject release() {
        jobject tmp = ref_;
        ref_ = nullptr;
        return tmp;
    }
    
    GlobalRefGuard(const GlobalRefGuard&) = delete;
    GlobalRefGuard& operator=(const GlobalRefGuard&) = delete;

private:
    JNIEnv* env_;
    jobject ref_;
};

// ============================================================================
// RAII Guard: Thread Attachment
// ============================================================================
class ThreadAttacher {
public:
    ThreadAttacher() : env_(nullptr), attached_(false) {
        if (!g_jvm) return;
        
        int status = g_jvm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
                attached_ = true;
            }
        }
    }
    
    ~ThreadAttacher() {
        if (attached_ && g_jvm) {
            g_jvm->DetachCurrentThread();
        }
    }
    
    JNIEnv* env() const { return env_; }
    bool valid() const { return env_ != nullptr; }
    
    ThreadAttacher(const ThreadAttacher&) = delete;
    ThreadAttacher& operator=(const ThreadAttacher&) = delete;

private:
    JNIEnv* env_;
    bool attached_;
};

// ============================================================================
// Exception Helpers
// ============================================================================

// Check if exception occurred, clear it and return true
inline bool checkException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

// Throw a Java RuntimeException with message
inline void throwRuntimeException(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) {
        env->ThrowNew(cls, msg);
        env->DeleteLocalRef(cls);
    }
}

// Throw a Java IllegalArgumentException
inline void throwIllegalArgumentException(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls) {
        env->ThrowNew(cls, msg);
        env->DeleteLocalRef(cls);
    }
}

// Throw a Java IllegalStateException
inline void throwIllegalStateException(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) {
        env->ThrowNew(cls, msg);
        env->DeleteLocalRef(cls);
    }
}

/**
 * Create a Java UTF-16 string from standard UTF-8.
 *
 * Do not use this for Group C JSON output: native JSON is deliberately
 * ASCII-only and retains its explicit NewStringUTF boundary contract.
 */
inline jstring utf8ToJString(JNIEnv* env, const std::string& value) {
    if (!env) return nullptr;
    try {
        std::vector<std::uint16_t> utf16;
        std::string error;
        if (!common_jni::text::utf8ToUtf16(value, utf16, &error)) {
            throwRuntimeException(env, error.c_str());
            return nullptr;
        }
        if (utf16.size() > static_cast<std::size_t>(
                std::numeric_limits<jsize>::max()
            )) {
            throwRuntimeException(env, "Native UTF-8 string is too large for Java");
            return nullptr;
        }
        std::vector<jchar> chars;
        chars.reserve(utf16.size());
        for (const std::uint16_t unit : utf16) {
            chars.push_back(static_cast<jchar>(unit));
        }
        const jchar empty = 0;
        return env->NewString(
            chars.empty() ? &empty : chars.data(),
            static_cast<jsize>(chars.size())
        );
    } catch (const std::bad_alloc&) {
        jclass type = env->FindClass("java/lang/OutOfMemoryError");
        if (type) {
            env->ThrowNew(type, "Unable to allocate Java UTF-16 string");
            env->DeleteLocalRef(type);
        }
        return nullptr;
    } catch (...) {
        throwRuntimeException(env, "Unable to convert native UTF-8 string");
        return nullptr;
    }
}

// ============================================================================
// Macros for safe JNI function wrapping
// ============================================================================

#define JNI_TRY_CATCH_BEGIN try {

#define JNI_TRY_CATCH_END(env, retval) \
    } catch (const std::exception& e) { \
        jni::throwRuntimeException(env, e.what()); \
        return retval; \
    } catch (...) { \
        jni::throwRuntimeException(env, "Unknown native exception"); \
        return retval; \
    }

#define JNI_TRY_CATCH_END_VOID(env) \
    } catch (const std::exception& e) { \
        jni::throwRuntimeException(env, e.what()); \
        return; \
    } catch (...) { \
        jni::throwRuntimeException(env, "Unknown native exception"); \
        return; \
    }

// Null check macro
#define JNI_NULL_CHECK(env, ptr, msg, retval) \
    if (!(ptr)) { \
        jni::throwIllegalArgumentException(env, msg); \
        return retval; \
    }

#define JNI_NULL_CHECK_VOID(env, ptr, msg) \
    if (!(ptr)) { \
        jni::throwIllegalArgumentException(env, msg); \
        return; \
    }

// ============================================================================
// Buffer Alignment Utilities
// ============================================================================

/**
 * Check if a pointer is aligned to N bytes.
 * Use 16 for NEON SIMD optimal alignment.
 */
template<size_t N>
inline bool isAligned(const void* ptr) {
    return (reinterpret_cast<uintptr_t>(ptr) % N) == 0;
}

/**
 * Check if DirectByteBuffer is aligned for NEON (16 bytes).
 */
inline bool isBufferAlignedForNeon(JNIEnv* env, jobject buffer) {
    if (!buffer) return false;
    void* addr = env->GetDirectBufferAddress(buffer);
    if (!addr) return false;
    return isAligned<16>(addr);
}

/**
 * Get aligned pointer (rounds up to next aligned address).
 * WARNING: Caller must ensure buffer has enough space!
 */
template<size_t N, typename T>
inline T* alignPointer(T* ptr) {
    uintptr_t addr = reinterpret_cast<uintptr_t>(ptr);
    uintptr_t aligned = (addr + N - 1) & ~(N - 1);
    return reinterpret_cast<T*>(aligned);
}

/**
 * Calculate padding needed for alignment.
 */
template<size_t N>
inline size_t alignmentPadding(const void* ptr) {
    uintptr_t addr = reinterpret_cast<uintptr_t>(ptr);
    uintptr_t aligned = (addr + N - 1) & ~(N - 1);
    return aligned - addr;
}

} // namespace jni

#endif // JNI_HELPER_H
