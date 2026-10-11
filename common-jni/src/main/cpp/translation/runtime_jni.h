#pragma once
#include <jni.h>
namespace transiber {
// Shared UTF-8-safe Java exception path for text and vision in this runtime.
void throwNativeError(JNIEnv* env, const char* error);
}
