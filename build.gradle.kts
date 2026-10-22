// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// Explicit CLI/release selection is the policy; a conflicting IDE hint fails
// because AGP independently uses that hint to suppress native build tasks.
// One resolved list is consumed by every Android module, including CMake.
val abiSelection = listOf("hearthAbis", "hearth.abis", "android.injected.build.abi")
    .firstNotNullOfOrNull { key -> providers.gradleProperty(key).orNull?.let { value ->
        if (key == "android.injected.build.abi" && value.isBlank()) null else key to value
    } }
val knownHearthAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val activeHearthAbis = abiSelection?.let { (key, value) ->
    val names = value.split(',').map(String::trim)
    require(names.all { it in knownHearthAbis }) {
        "$key must contain Android ABI names (${knownHearthAbis.joinToString()}); got '$value'"
    }
    names.distinct()
} ?: knownHearthAbis
val ideAbiHint = providers.gradleProperty("android.injected.build.abi").orNull
    ?.takeIf(String::isNotBlank)?.split(',')?.map(String::trim)?.toSet()
if (abiSelection?.first in listOf("hearthAbis", "hearth.abis") && ideAbiHint != null) {
    require(ideAbiHint == activeHearthAbis.toSet()) {
        "Conflicting IDE ABI hint $ideAbiHint and ${abiSelection?.first}=$activeHearthAbis. " +
            "Remove android.injected.build.abi for a CLI/release build, or select the same ABIs and sync Gradle. " +
            "AGP otherwise silently omits requested native build tasks."
    }
}
extra["hearthActiveAbis"] = activeHearthAbis

abstract class AndroidAbiAudit : DefaultTask() {
    @get:Input abstract val activeAbis: ListProperty<String>
    @get:Input abstract val knownAbis: ListProperty<String>
    @get:Input abstract val packagingExcludes: MapProperty<String, String>
    @get:Input abstract val selectionSource: Property<String>
    @get:Input abstract val packagingAbis: MapProperty<String, String>
    @get:Input abstract val cmakeAbis: MapProperty<String, String>
    @get:Input abstract val nativeEnabled: MapProperty<String, Boolean>
    @get:Input abstract val jniDirectories: MapProperty<String, String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun verify() {
        val active = activeAbis.get().sorted()
        val tts = active.filter { it == "arm64-v8a" }
        val modules = listOf(":app", ":common-jni", ":hearth-tts", ":hearth-onnx-runtime")
        fun names(value: String) = value.split(',').filter(String::isNotEmpty).sorted()
        val snapshot = modules.associateWith { name ->
            check(packagingAbis.get().containsKey(name)) { "No finalized Android ABI model for $name" }
            val packaged = names(packagingAbis.get().getValue(name))
            val native = names(cmakeAbis.get().getValue(name))
            val enabled = nativeEnabled.get().getValue(name)
            val expectedPackaging = if (name == ":hearth-tts" && tts.isNotEmpty()) tts else active
            val expectedNative = when (name) { ":common-jni" -> active; ":hearth-tts" -> tts; else -> emptyList() }
            check(packaged == expectedPackaging) { "$name packages $packaged; shared ABI policy requires $expectedPackaging" }
            check(native == expectedNative && enabled == expectedNative.isNotEmpty()) {
                "$name CMake ABI configuration is inconsistent: enabled=$enabled, ABIs=$native, expected=$expectedNative"
            }
            val excludes = packagingExcludes.get().getValue(name).split('|').filter(String::isNotEmpty)
            val archiveAbis = if (name == ":hearth-tts") tts else active
            val excluded = knownAbis.get().filter { "**/$it/**" in excludes }.sorted()
            check(excluded == (knownAbis.get() - archiveAbis.toSet()).sorted()) {
                "$name native archive filters do not match the selected ABIs: excluded=$excluded, expected archive=$archiveAbis"
            }
            val directories = jniDirectories.get().getValue(name).split('|').filter(String::isNotEmpty)
            if (name == ":hearth-tts" && tts.isEmpty()) {
                check(directories.isEmpty()) { "Unsupported TTS ABI must not expose prebuilt JNI directories" }
            }
            mapOf("packagingAbis" to packaged, "nativeAbis" to native,
                "nativeBuildEnabled" to enabled, "archiveAbis" to archiveAbis,
                "excludedNativeAbis" to excluded, "packagingExcludes" to excludes, "jniDirectories" to directories)
        }
        val data = mapOf("schemaVersion" to 1, "selectionSource" to selectionSource.get(),
            "activeAbis" to active, "ttsSupportedAbis" to tts, "modules" to snapshot)
        report.get().asFile.apply { parentFile.mkdirs(); writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(data)) + "\n") }
        logger.lifecycle("Android ABI configuration verified: ${active.joinToString()} · TTS ${tts.ifEmpty { listOf("disabled") }.joinToString()}")
    }
}

if (!providers.gradleProperty("hearthDesktopOnly").map(String::toBoolean).getOrElse(false)) {
    val audit = tasks.register<AndroidAbiAudit>("verifyAndroidAbiConfiguration") {
        group = "verification"
        description = "Check finalized app/JNI/TTS/ONNX ABI selection and emit a machine-readable report"
        activeAbis.set(activeHearthAbis)
        knownAbis.set(knownHearthAbis)
        selectionSource.set(abiSelection?.first ?: "default")
        report.set(layout.buildDirectory.file("reports/android/abi-configuration.json"))
    }
    subprojects {
        val module = this
        if (path in setOf(":app", ":common-jni", ":hearth-tts", ":hearth-onnx-runtime")) {
            fun constrainArchive(excludes: MutableSet<String>) {
                // Library ndk.abiFilters do not remove prebuilts from AARs.
                // Filter both staged and formerly compiled JNI inputs explicitly.
                val allowed = if (module.path == ":hearth-tts") activeHearthAbis.filter { it == "arm64-v8a" } else activeHearthAbis
                excludes += (knownHearthAbis - allowed.toSet()).map { "**/$it/**" }
            }
            fun capture(packaging: Set<String>, native: Set<String>, enabled: Boolean, directories: Set<String>, excludes: Set<String>) {
                audit.configure {
                    packagingAbis.put(module.path, packaging.sorted().joinToString(","))
                    packagingExcludes.put(module.path, excludes.sorted().joinToString("|"))
                    cmakeAbis.put(module.path, native.sorted().joinToString(","))
                    nativeEnabled.put(module.path, enabled)
                    jniDirectories.put(module.path, directories.map { rootProject.relativePath(module.file(it)) }.sorted().joinToString("|"))
                }
            }
            plugins.withId("com.android.application") {
                extensions.getByType(com.android.build.api.variant.ApplicationAndroidComponentsExtension::class.java).finalizeDsl { dsl ->
                    constrainArchive(dsl.packaging.jniLibs.excludes)
                    capture(dsl.defaultConfig.ndk.abiFilters, dsl.defaultConfig.externalNativeBuild.cmake.abiFilters,
                        dsl.externalNativeBuild.cmake.path != null, dsl.sourceSets.getByName("main").jniLibs.directories, dsl.packaging.jniLibs.excludes)
                }
            }
            plugins.withId("com.android.library") {
                extensions.getByType(com.android.build.api.variant.LibraryAndroidComponentsExtension::class.java).finalizeDsl { dsl ->
                    constrainArchive(dsl.packaging.jniLibs.excludes)
                    capture(dsl.defaultConfig.ndk.abiFilters, dsl.defaultConfig.externalNativeBuild.cmake.abiFilters,
                        dsl.externalNativeBuild.cmake.path != null, dsl.sourceSets.getByName("main").jniLibs.directories, dsl.packaging.jniLibs.excludes)
                }
            }
            tasks.configureEach {
                if (name == "preBuild" || name.startsWith("configureCMake") || name.startsWith("buildCMake") || name.startsWith("externalNativeBuild")) dependsOn(audit)
            }
        }
    }
}
