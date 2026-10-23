#pragma once
#include <jni.h>
#include <stdexcept>
#include <string>

inline std::string desktopUtf8(JNIEnv* env, jbyteArray bytes, size_t maximum = 16384) {
    if (!bytes || size_t(env->GetArrayLength(bytes)) > maximum) throw std::invalid_argument("Missing or oversized UTF-8 input");
    std::string value(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, value.size(), reinterpret_cast<jbyte*>(value.data()));
    if (value.find('\0') != std::string::npos) throw std::invalid_argument("Embedded NUL in UTF-8 input");
    return value;
}
inline jbyteArray desktopBytes(JNIEnv* env, const std::string& value) {
    auto output = env->NewByteArray(value.size());
    if (output) env->SetByteArrayRegion(output, 0, value.size(), reinterpret_cast<const jbyte*>(value.data()));
    return output;
}
inline void desktopFail(JNIEnv* env, const char* error) {
    if (env->ExceptionCheck()) return;
    auto bytes = desktopBytes(env, error ? error : "Desktop native failure");
    if (!bytes) return;
    auto stringClass = env->FindClass("java/lang/String");
    auto charset = env->NewStringUTF("UTF-8");
    if (!stringClass || !charset) return;
    auto constructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    if (!constructor) return;
    auto message = env->NewObject(stringClass, constructor, bytes, charset);
    auto exception = env->FindClass("java/lang/IllegalStateException");
    if (!message || !exception) return;
    auto errorConstructor = env->GetMethodID(exception, "<init>", "(Ljava/lang/String;)V");
    if (!errorConstructor) return;
    auto result = static_cast<jthrowable>(env->NewObject(exception, errorConstructor, message));
    if (result) env->Throw(result);
}
