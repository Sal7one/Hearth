import java.security.MessageDigest

plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
    namespace = "com.sal7one.hearth.tts.ming"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":hearth-tts"))
    testImplementation("junit:junit:4.13.2")
}

// The two prebuilt Ming JNI artifacts are experimental and arm64-only. Pinning them here keeps
// the APK honest: a missing or swapped library fails the build instead of claiming availability.
val mingJniLibs = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
val mingJniPins = mapOf(
    "libming_jni.so" to "3c45361ee6e7d297a470b5b43ac34378407e45f8d4ce6d661cbf561cc52f0178",
    "libming_jni_scalar.so" to "2703bb050a7dc3e33162ac7aaadbbfc856dbe5dc0d44b2d0fbd3de7023380744",
)
val verifyMingAndroidLibs = tasks.register("verifyMingAndroidLibs") {
    inputs.dir(mingJniLibs)
    doLast {
        val actualNames = mingJniLibs.asFile.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
        val packaged = actualNames.filter { it.endsWith(".so") }.toSet()
        check(packaged == mingJniPins.keys) { "Ming arm64 JNI set is incomplete: $packaged" }
        mingJniPins.forEach { (name, expected) ->
            val file = mingJniLibs.file(name).asFile
            check(file.isFile) { "Missing staged Ming JNI: ${file.absolutePath}" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Staged Ming JNI changed: $name ($actual); rebuild scripts/desktop/build-ming-android.sh and update the pins" }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyMingAndroidLibs) }
