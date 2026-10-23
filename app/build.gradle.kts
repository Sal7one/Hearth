import com.android.build.api.artifact.SingleArtifact
plugins {
 alias(libs.plugins.android.application)
 alias(libs.plugins.kotlin.android)
 alias(libs.plugins.kotlin.compose)
}

val activeHearthAbis = rootProject.extra["hearthActiveAbis"] as List<String>
val stageAria2Runtime = tasks.register<Exec>("stageAria2Runtime") {
 inputs.file(rootProject.file("scripts/downloads/build-aria2-android.py"))
 inputs.files("../scripts/native_abis.py", "../licenses/aria2/README.md", "../licenses/aria2/GPL-3.0.txt")
 inputs.property("abis", activeHearthAbis.joinToString(","))
 outputs.dir(layout.buildDirectory.dir("generated/aria2"))
 outputs.file(rootProject.layout.buildDirectory.file("aria2-corresponding-source.tar.gz"))
 commandLine("python3", rootProject.file("scripts/downloads/build-aria2-android.py"), "--abis", activeHearthAbis.joinToString(","))
}
android {
 packaging.jniLibs.keepDebugSymbols += "**/libhearth_aria2c.so"
 namespace = "com.sal7one.transiber"
 compileSdk = 36
 defaultConfig {
  applicationId = "com.sal7one.transiber"
  minSdk = 28
  targetSdk = 36
  versionCode = 101
  versionName = "0.30.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  ndk { abiFilters += activeHearthAbis }
 }
 flavorDimensions += "distribution"
 productFlavors {
  create("foss") { dimension = "distribution"; buildConfigField("boolean", "FEATURE_BYOK", "false") }
  create("play") { dimension = "distribution"; buildConfigField("boolean", "FEATURE_BYOK", "true") }
 }
 sourceSets.getByName("play") {
  jniLibs.srcDir(files(layout.buildDirectory.dir("generated/aria2/jniLibs")).builtBy(stageAria2Runtime))
  assets.srcDir(files(layout.buildDirectory.dir("generated/aria2/assets")).builtBy(stageAria2Runtime))
 }
 buildTypes {
  debug { applicationIdSuffix = ".debug" }
  release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
  create("qa") { initWith(getByName("release")); applicationIdSuffix = ".qa"; signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release" }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true; buildConfig = true }
 splits {
  abi {
   isEnable = true
   reset()
   include(*activeHearthAbis.toTypedArray())
   isUniversalApk = true
  }
 }
 testOptions {
  unitTests.isIncludeAndroidResources = true
  unitTests.all {
   // Android 16's host framework requires Java 21; production Kotlin/Java stays at 17.
   it.javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
   it.jvmArgs(
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.security=ALL-UNNAMED",
    "--add-opens=java.base/java.text=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
   )
  }
 }
 packaging { jniLibs.useLegacyPackaging = true; jniLibs.keepDebugSymbols += setOf("**/libming_jni.so", "**/libming_jni_scalar.so", "**/libonnxruntime.so", "**/libvosk.so", "**/libhearth_qwen.so", "**/libhearth_nemotron.so", "**/libhearth_transcribepsy.so", "**/libtransiber_translation.so", "**/libtransiber_translation_vulkan.so", "**/libtransiber_translation_opencl.so", "**/libhearth_transcribepsy_vulkan.so", "**/libhearth_transcribepsy_opencl.so", "**/libhearth_whisper_vulkan.so"); resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
tasks.configureEach {
 if (name.startsWith("mergePlay") && (name.endsWith("JniLibFolders") || name.endsWith("Assets"))) dependsOn(stageAria2Runtime)
 if (name.contains("Play") && name.contains("Lint", ignoreCase = true)) dependsOn(stageAria2Runtime)
}
kotlin { jvmToolchain(17) }
dependencies {
 implementation(project(":common-jni"))
 implementation(project(":hearth-tts"))
 for (module in listOf("camera-core", "camera-camera2", "camera-lifecycle", "camera-view")) implementation("androidx.camera:$module:1.4.0")
 "playImplementation"("com.google.mlkit:translate:17.0.3")
 implementation(libs.zxing.core)
 implementation(libs.androidx.core.ktx)
 implementation(libs.androidx.appcompat)
 implementation("androidx.media:media:1.7.0")
 implementation(libs.androidx.lifecycle.runtime.ktx)
 implementation(libs.androidx.activity.compose)
 implementation(platform(libs.androidx.compose.bom))
 implementation(libs.androidx.ui)
 implementation(libs.androidx.ui.graphics)
 implementation(libs.androidx.ui.tooling.preview)
 implementation(libs.androidx.material3)
 implementation("androidx.compose.material:material-icons-extended")
 implementation(libs.androidx.lifecycle.runtime.compose)
 implementation(libs.androidx.lifecycle.viewmodel.compose)
 implementation("androidx.datastore:datastore-preferences:1.1.7")
 implementation("androidx.documentfile:documentfile:1.1.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.apache.commons:commons-compress:1.28.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
 testImplementation("org.json:json:20231013")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 testImplementation("org.robolectric:robolectric:4.17")
 testImplementation(libs.androidx.ui.test.junit4)
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
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
