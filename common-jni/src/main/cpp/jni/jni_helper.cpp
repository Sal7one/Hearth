#include "jni_helper.h"
#include <mutex>
#include <pthread.h>

namespace jni {

// Global JVM reference - set in JNI_OnLoad
JavaVM* g_jvm = nullptr;

namespace {
std::once_flag g_keyOnce;
pthread_key_t g_attachmentKey;
bool g_keyReady = false;

void detachOnThreadExit(void* rawVm) {
    if (rawVm) static_cast<JavaVM*>(rawVm)->DetachCurrentThread();
}

void initializeKey() {
    g_keyReady = pthread_key_create(&g_attachmentKey, detachOnThreadExit) == 0;
}
} // namespace

JNIEnv* getEnvForVm(JavaVM* vm) noexcept {
    if (!vm) return nullptr;
    JNIEnv* env = nullptr;
    const int status = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) return env;
    if (status != JNI_EDETACHED) return nullptr;

    try {
        std::call_once(g_keyOnce, initializeKey);
    } catch (...) {
        return nullptr;
    }
    if (!g_keyReady) return nullptr;
    JavaVMAttachArgs args{JNI_VERSION_1_6, const_cast<char*>("HearthNative"), nullptr};
#ifdef __ANDROID__
    if (vm->AttachCurrentThreadAsDaemon(&env, &args) != JNI_OK) return nullptr;
#else
    if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) != JNI_OK) return nullptr;
#endif
    if (pthread_setspecific(g_attachmentKey, vm) != 0) {
        vm->DetachCurrentThread();
        return nullptr;
    }
    return env;
}

JNIEnv* getEnv() { return getEnvForVm(g_jvm); }

} // namespace jni
