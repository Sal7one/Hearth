import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    jvmToolchain(17)
    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":hearth-core"))
                implementation(project(":desktop-runtime"))
                implementation(project(":desktop-media"))
                implementation(project(":desktop-data"))
                implementation(project(":desktop-integration"))
                implementation(compose.desktop.currentOs)
                implementation("net.java.dev.jna:jna:5.17.0")
                implementation("net.java.dev.jna:jna-platform:5.17.0")
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
            }
        }
        val desktopTest by getting { dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        } }
    }
}

compose.desktop {
    application {
        // Keep Java 17 bytecode, but package Java 21: its Apple Silicon SHA intrinsics
        // avoid seconds of software hashing while preserving every model integrity check.
        // Android Studio's daemon JBR can also omit jpackage; use a full application JDK.
        val packagingJdk = project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        javaHome = providers.gradleProperty("hearthDesktopJavaHome")
            .orElse(packagingJdk.map { it.metadata.installationPath.asFile.absolutePath }).get()
        from(kotlin.targets["desktop"])
        mainClass = "com.sal7one.hearth.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Hearth"
            packageVersion = providers.gradleProperty("hearthDesktopVersion").orElse("1.0.4").get()
            description = "Independent speech recognition, translation and voice pipelines"
            vendor = "Hearth"
            modules("java.desktop", "java.net.http", "jdk.httpserver", "jdk.crypto.ec", "java.logging", "java.prefs", "jdk.unsupported")
            macOS {
                iconFile.set(project.file("packaging/icons/hearth.icns"))
                bundleID = "com.sal7one.hearth.desktop"
                infoPlist {
                    extraKeysRawXml = """
                        <key>LSMinimumSystemVersion</key>
                        <string>13.3</string>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Hearth uses your microphone only when you start speech recognition.</string>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("packaging/icons/hearth.ico"))
                menuGroup = "Hearth"
                shortcut = true
                dirChooser = true
                upgradeUuid = "eb6e31f8-5a0e-42ed-b709-69274df59778"
            }
        }
    }
}
