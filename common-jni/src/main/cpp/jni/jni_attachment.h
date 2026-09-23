#ifndef HEARTH_JNI_ATTACHMENT_H
#define HEARTH_JNI_ATTACHMENT_H

#include <jni.h>

namespace jni {

// A JVM-owned thread is returned unchanged. A native thread is attached once
// and detached by its pthread-key destructor when that thread exits.
JNIEnv* getEnvForVm(JavaVM* vm) noexcept;

} // namespace jni

#endif // HEARTH_JNI_ATTACHMENT_H
