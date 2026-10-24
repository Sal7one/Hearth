package com.sal7one.common_jni.translation

/** The model completed normally but produced no visible translation for this specific input.
 * Runtime, authentication, missing-model and cancellation failures must not use this type.
 */
class EmptyTranslationOutputException(message: String) : IllegalStateException(message)
