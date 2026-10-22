// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// One active-ABI source of truth for Android Studio, app, common-jni and TTS.
// Android Studio injects `android.injected.build.abi` for the selected device.
// CLI builds may set -PhearthAbis=...; otherwise all release ABIs are active.
val requestedHearthAbis = (providers.gradleProperty("android.injected.build.abi").orNull
    ?: providers.gradleProperty("hearthAbis").orNull
    ?: providers.gradleProperty("hearth.abis").orNull)
val knownHearthAbis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val activeHearthAbis = requestedHearthAbis
    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
    ?.filter { it in knownHearthAbis }?.distinct()
    ?.takeIf { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
extra["hearthActiveAbis"] = activeHearthAbis
