#include "jni_helper.h"

namespace jni {

// Global JVM reference - set in JNI_OnLoad
JavaVM* g_jvm = nullptr;

JNIEnv* getEnv() {
    if (!g_jvm) return nullptr;
    
    JNIEnv* env = nullptr;
    int status = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    
    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
    }
    
    return env;
}

} // namespace jni

