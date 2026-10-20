plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":hearth-core"))
    implementation(project(":desktop-media"))
    implementation("androidx.datastore:datastore-preferences-core:1.1.7")
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
