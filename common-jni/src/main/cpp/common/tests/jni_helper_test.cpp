#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

#include "../../jni/jni_helper.h"

static int failures = 0;
#define CHECK(condition) do { if (!(condition)) { \
    ++failures; std::cerr << "FAIL " << __LINE__ << ": " #condition "\n"; \
} } while (0)

static std::string takeException(JNIEnv* env) {
    jthrowable error = env->ExceptionOccurred();
    CHECK(error != nullptr);
    env->ExceptionClear();
    if (!error) return {};
    jclass type = env->GetObjectClass(error);
    jmethodID getMessage = env->GetMethodID(type, "getMessage", "()Ljava/lang/String;");
    jstring message = static_cast<jstring>(env->CallObjectMethod(error, getMessage));
    jni::JStringGuard text(env, message);
    CHECK(text.valid());
    const std::string result = text.str();
    env->DeleteLocalRef(message);
    env->DeleteLocalRef(type);
    env->DeleteLocalRef(error);
    return result;
}

static int throwFromMacro(JNIEnv* env) {
    JNI_TRY_CATCH_BEGIN
    throw std::runtime_error(u8"macro failed: 🧪");
    JNI_TRY_CATCH_END(env, -1)
}

int main() {
    JavaVMOption option{const_cast<char*>("-Xcheck:jni"), nullptr};
    JavaVMInitArgs args{};
    args.version = JNI_VERSION_1_8;
    args.nOptions = 1;
    args.options = &option;
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    CHECK(JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &args) == JNI_OK);
    if (!env) return 1;

    jni::throwRuntimeException(env, u8"decode failed: 中文 😀");
    CHECK(takeException(env) == u8"decode failed: 中文 😀");

    jni::throwIllegalArgumentException(env, u8"wrong язык");
    CHECK(takeException(env) == u8"wrong язык");

    jni::throwIllegalStateException(env, "first exception");
    jni::throwRuntimeException(env, "must not replace the first exception");
    CHECK(takeException(env) == "first exception");

    CHECK(throwFromMacro(env) == -1);
    CHECK(takeException(env) == u8"macro failed: 🧪");

    jni::throwRuntimeException(env, "bad\xC0\xAF UTF-8");
    CHECK(takeException(env).find("UTF-8") != std::string::npos);

    CHECK(vm->DestroyJavaVM() == JNI_OK);
    if (failures) {
        std::cerr << "jni_helper_test: " << failures << " failures\n";
        return 1;
    }
    std::cout << "jni_helper_test: ALL PASS\n";
    return 0;
}
