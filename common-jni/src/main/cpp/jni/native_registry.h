#ifndef JNI_NATIVE_REGISTRY_H
#define JNI_NATIVE_REGISTRY_H

// JNI adapter surface for the portable handle registry. The registry itself
// lives in common/native_registry.h and has no JNI/Android dependencies; this
// header only adds JNI convenience macros for adapter translation units.
// Expand JNI_HANDLE_CHECK only where jni_helper.h (throwIllegalStateException)
// is also included.

#include <jni.h>

#include "../common/native_registry.h"

#ifndef __cplusplus
#error "This header requires C++"
#endif

#define JNI_HANDLE_CHECK(registry, registry_type, registry_kind, handle, retval) \
    jni::HandleGuard<registry_type, registry_kind> __handle_guard((registry), (handle)); \
    if (!__handle_guard) { \
        jni::throwIllegalStateException(env, "Invalid native handle"); \
        return retval; \
    } \
    auto* __obj = __handle_guard.get()

#define JNI_HANDLE_CHECK_VOID(registry, registry_type, registry_kind, handle) \
    jni::HandleGuard<registry_type, registry_kind> __handle_guard((registry), (handle)); \
    if (!__handle_guard) { \
        jni::throwIllegalStateException(env, "Invalid native handle"); \
        return; \
    } \
    auto* __obj = __handle_guard.get()

#endif // JNI_NATIVE_REGISTRY_H
