import com.android.build.api.artifact.SingleArtifact
plugins {
 alias(libs.plugins.android.application)
 alias(libs.plugins.kotlin.android)
 alias(libs.plugins.kotlin.compose)
}
android {
 namespace = "com.sal7one.transiber"
 compileSdk = 36
 defaultConfig {
  applicationId = "com.sal7one.transiber"
  minSdk = 28
  targetSdk = 36
  versionCode = 22
  versionName = "0.10.0"
  ndk { abiFilters += "arm64-v8a" }
 }
 flavorDimensions += "distribution"
 productFlavors {
  create("foss") { dimension = "distribution"; buildConfigField("boolean", "FEATURE_BYOK", "false") }
  create("play") { dimension = "distribution"; buildConfigField("boolean", "FEATURE_BYOK", "true") }
 }
 buildTypes {
  debug { applicationIdSuffix = ".debug" }
  release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
  create("qa") { initWith(getByName("release")); applicationIdSuffix = ".qa"; signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release" }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true; buildConfig = true }
 packaging { jniLibs.useLegacyPackaging = true; jniLibs.keepDebugSymbols += setOf("**/libonnxruntime.so", "**/libvosk.so", "**/libhearth_qwen.so", "**/libhearth_nemotron.so", "**/libtransiber_translation.so"); resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(17) }
dependencies {
 implementation(project(":common-jni"))
 for (module in listOf("camera-core", "camera-camera2", "camera-lifecycle", "camera-view")) implementation("androidx.camera:$module:1.4.0")
 "playImplementation"("com.google.mlkit:translate:17.0.3")
 implementation(libs.androidx.core.ktx)
 implementation(libs.androidx.lifecycle.runtime.ktx)
 implementation(libs.androidx.activity.compose)
 implementation(platform(libs.androidx.compose.bom))
 implementation(libs.androidx.ui)
 implementation(libs.androidx.ui.graphics)
 implementation(libs.androidx.ui.tooling.preview)
 implementation(libs.androidx.material3)
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
 implementation("androidx.datastore:datastore-preferences:1.1.7")
 implementation("androidx.documentfile:documentfile:1.1.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.apache.commons:commons-compress:1.28.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
 testImplementation("org.json:json:20231013")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 debugImplementation(libs.androidx.ui.tooling)
}
androidComponents.onVariants { variant ->
 if (variant.productFlavors.any { it.second == "foss" }) {
  val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
  val guard = tasks.register("checkNoNetPerm${variant.name.replaceFirstChar { it.uppercase() }}") {
   inputs.file(manifest)
   doLast {
    val text = manifest.get().asFile.readText()
    check(!text.contains("android.permission.INTERNET") && !text.contains("android.permission.ACCESS_NETWORK_STATE")) { "Offline APK contains a network permission" }
   }
  }
  tasks.configureEach { if (name == "assemble${variant.name.replaceFirstChar { it.uppercase() }}" || name == "package${variant.name.replaceFirstChar { it.uppercase() }}") dependsOn(guard) }
 }
}
