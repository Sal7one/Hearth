plugins { alias(libs.plugins.android.library) }
val activeHearthAbis = rootProject.extra["hearthActiveAbis"] as List<String>
android {
    namespace = "com.sal7one.hearth.onnxruntime"
    compileSdk = 36
    defaultConfig { minSdk = 28; ndk { abiFilters += activeHearthAbis } }
    packaging { jniLibs.keepDebugSymbols += "**/libonnxruntime.so" }
}
