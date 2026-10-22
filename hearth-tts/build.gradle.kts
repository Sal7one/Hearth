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
