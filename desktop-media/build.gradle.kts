plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
}
tasks.processResources { from(layout.projectDirectory.dir("native-libs")) { into("media") } }
tasks.test { systemProperty("hearth.media.required", providers.gradleProperty("hearthRequireMedia").orElse("false").get()) }
