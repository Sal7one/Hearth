plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.sal7one.hearth.onnxruntime"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    packaging { jniLibs.keepDebugSymbols += "**/libonnxruntime.so" }
}
