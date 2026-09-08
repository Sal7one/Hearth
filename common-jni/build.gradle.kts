plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
 namespace = "com.sal7one.common_jni"
 compileSdk = 36
 ndkVersion = "27.0.12077973"
 defaultConfig {
  minSdk = 28
  ndk { abiFilters += "arm64-v8a" }
  externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
  consumerProguardFiles("consumer-rules.pro")
 }
 externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
 implementation("androidx.core:core-ktx:1.17.0")
 implementation("androidx.documentfile:documentfile:1.1.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20231013")
}
