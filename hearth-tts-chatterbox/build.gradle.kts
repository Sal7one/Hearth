plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
    namespace = "com.sal7one.hearth.tts.chatterbox"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies { api(project(":hearth-tts")); implementation(project(":hearth-onnx-runtime")); testImplementation("junit:junit:4.13.2") }
