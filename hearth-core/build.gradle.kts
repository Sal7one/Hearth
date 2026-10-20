import com.android.build.gradle.LibraryExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library) apply false
}
val desktopOnly = providers.gradleProperty("hearthDesktopOnly").map(String::toBoolean).getOrElse(false)
if (!desktopOnly) apply(plugin = "com.android.library")
kotlin {
    if (!desktopOnly) androidTarget()
    jvm("desktop")
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.coroutines.core) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
if (!desktopOnly) extensions.configure<LibraryExtension> {
    namespace = "com.sal7one.hearth.core"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
