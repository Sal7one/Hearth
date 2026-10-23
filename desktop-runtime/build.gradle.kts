plugins { alias(libs.plugins.kotlin.jvm) }
kotlin {
    jvmToolchain(17)
    // These SDK sources contain no Android APIs; keep their pinned model and voice contracts shared.
    sourceSets.main {
        kotlin.srcDir("../hearth-tts/src/main/java")
        kotlin.srcDir("../hearth-tts-supertonic/src/main/java")
        kotlin.srcDir("../hearth-tts-moss/src/main/java")
        kotlin.srcDir("../hearth-tts-omnivoice/src/main/java")
        kotlin.srcDir("../hearth-tts-magpie/src/main/java")
        kotlin.srcDir("../hearth-tts-chatterbox/src/main/java")
        kotlin.exclude("**/SupertonicVoice.kt")
        kotlin.exclude("**/MossNative.kt")
        kotlin.exclude("**/OmniVoiceNative.kt")
        kotlin.exclude("**/MagpieNative.kt")
        kotlin.exclude("**/ChatterboxNative.kt")
    }
}
dependencies {
    api(project(":hearth-core"))
    implementation(project(":desktop-media"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.json:json:20231013")
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
// The desktop build script stages only the current host architecture, without model weights.
tasks.processResources {
    from("../common-jni/src/main/assets/ocr") { include("manga.json","manga-provenance.json");into("ocr") }
    from("../common-jni/src/main/assets/licenses/ocr") { into("licenses/ocr") }
    from("../common-jni/src/main/assets/licenses/android/Apache-2.0.txt") { into("licenses/ocr") }
    from(layout.projectDirectory.dir("native-libs")) {
        into("native")
        // Compose's macOS JAR processor re-signs .dylib entries even for unsigned apps.
        // An opaque envelope preserves the authenticated source bytes; extraction restores the name.
        rename { filename ->
            val lower = filename.lowercase()
            if (lower.endsWith(".dylib") || lower.endsWith(".dll") || lower.endsWith(".so") || ".so." in lower) "$filename.payload" else filename
        }
    }
}
