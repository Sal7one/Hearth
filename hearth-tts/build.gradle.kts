import java.security.MessageDigest

plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }

// The app, common-jni and TTS share one active ABI. TTS supports ARM64 only;
// selecting x86 intentionally produces no TTS native build.
val activeHearthAbis = rootProject.extra["hearthActiveAbis"] as List<String>
val ttsAbis = activeHearthAbis.filter { it == "arm64-v8a" }

android {
    namespace = "com.sal7one.hearth.tts"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        if (ttsAbis.isNotEmpty()) {
            ndk { abiFilters += ttsAbis }
            externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
        }
    }
    if (ttsAbis.isNotEmpty()) {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    }
    packaging { jniLibs.excludes += "**/libonnxruntime.so" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets {
        getByName("main") {
            java.srcDirs(
                "src/main/java",
                "../hearth-tts-supertonic/src/main/java",
                "../hearth-tts-magpie/src/main/java",
                "../hearth-tts-omnivoice/src/main/java",
                "../hearth-tts-moss/src/main/java",
                "../hearth-tts-oute/src/main/java",
                "../hearth-tts-chatterbox/src/main/java",
                "../hearth-tts-ming/src/main/java",
            )
            if (ttsAbis.isNotEmpty()) {
                jniLibs.srcDirs(
                    "src/main/jniLibs",
                    "../hearth-tts-magpie/src/main/jniLibs",
                    "../hearth-tts-omnivoice/src/main/jniLibs",
                    "../hearth-tts-moss/src/main/jniLibs",
                    "../hearth-tts-oute/src/main/jniLibs",
                    "../hearth-tts-chatterbox/src/main/jniLibs",
                    "../hearth-tts-ming/src/main/jniLibs",
                )
            }
            assets.srcDirs(
                "src/main/assets",
                "../hearth-tts-magpie/src/main/assets",
                "../hearth-tts-supertonic/src/main/assets",
                "../hearth-tts-omnivoice/src/main/assets",
                "../hearth-tts-moss/src/main/assets",
                "../hearth-tts-oute/src/main/assets",
                "../hearth-tts-chatterbox/src/main/assets",
                "../hearth-tts-ming/src/main/assets",
            )
        }
        getByName("test") {
            java.srcDirs(
                "src/test/java",
                "../hearth-tts-supertonic/src/test/java",
                "../hearth-tts-magpie/src/test/java",
                "../hearth-tts-omnivoice/src/test/java",
                "../hearth-tts-moss/src/test/java",
                "../hearth-tts-oute/src/test/java",
                "../hearth-tts-chatterbox/src/test/java",
                "../hearth-tts-ming/src/test/java",
            )
        }
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.json:json:20231013")
    implementation(project(":hearth-onnx-runtime"))
    testImplementation("junit:junit:4.13.2")
}

fun verifyPinnedNativeInventory(directory: File, pins: Map<String, String>) {
    val packaged = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "so" }.map { it.name }.toSet()
    check(packaged == pins.keys) { "Ming arm64 JNI set is incomplete or unexpected: $packaged" }
    pins.forEach { (name, expected) ->
        val digest = MessageDigest.getInstance("SHA-256")
        directory.resolve(name).inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Staged Ming JNI changed: $name ($actual); rebuild and review the provenance pins" }
    }
}

// Restore the pre-collapse fail-closed guard. Other app ABIs package no TTS JNI.
if (ttsAbis.isNotEmpty()) {
    val mingJniDirectory = layout.projectDirectory.dir("../hearth-tts-ming/src/main/jniLibs/arm64-v8a")
    val mingJniPinFile = layout.projectDirectory.file("provenance/jniLibs/ming-SHA256SUMS")
    val verifyMingAndroidLibs = tasks.register("verifyMingAndroidLibs") {
        inputs.dir(mingJniDirectory)
        inputs.file(mingJniPinFile)
        doLast {
            val entries = mingJniPinFile.asFile.readLines().filter(String::isNotBlank).map { line ->
                val fields = line.trim().split(Regex("\\s+"), limit = 2)
                check(fields.size == 2 && fields[0].matches(Regex("[a-f0-9]{64}"))) { "Invalid Ming JNI provenance pin" }
                fields[1] to fields[0]
            }
            val pins = entries.toMap()
            check(entries.size == 2 && pins.keys == setOf("libming_jni.so", "libming_jni_scalar.so")) {
                "Ming JNI provenance must pin exactly the dotprod and scalar libraries"
            }
            verifyPinnedNativeInventory(mingJniDirectory.asFile, pins)
        }
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyMingAndroidLibs) }
}

// Exercise the same preBuild verifier against healthy, changed, missing and extra staged files.
val testMingNativeIntegrity = tasks.register("testMingNativeIntegrity") {
    group = "verification"
    doLast {
        val directory = temporaryDir.resolve("staged").apply { deleteRecursively(); mkdirs() }
        val binary = directory.resolve("libfixture.so")
        binary.writeText("authenticated native fixture")
        val pins = mapOf(binary.name to MessageDigest.getInstance("SHA-256")
            .digest(binary.readBytes()).joinToString("") { "%02x".format(it) })
        verifyPinnedNativeInventory(directory, pins)
        fun rejects(action: () -> Unit) {
            action()
            var rejected = false
            try { verifyPinnedNativeInventory(directory, pins) } catch (_: IllegalStateException) { rejected = true }
            check(rejected) { "Native integrity verification accepted an invalid staged inventory" }
        }
        rejects { binary.appendText("changed") }
        rejects { binary.delete() }
        rejects { binary.writeText("authenticated native fixture"); directory.resolve("libunreviewed.so").writeText("extra") }
    }
}
tasks.matching { it.name == "check" || it.name == "test" }.configureEach { dependsOn(testMingNativeIntegrity) }
