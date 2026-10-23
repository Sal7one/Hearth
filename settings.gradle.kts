pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Hearth"
if (!providers.gradleProperty("hearthDesktopOnly").map(String::toBoolean).getOrElse(false)) {
    include(":app", ":common-jni")
    // One Android TTS library; the per-family adaptation sources are folded into it.
    include(":hearth-tts")
    // Shared ONNX Runtime remains the one cross-module runtime dependency.
    include(":hearth-onnx-runtime")
}

// Desktop shares contracts and pipeline policy; Android keeps its existing platform adapters.
include(":hearth-core", ":desktop-runtime", ":desktop-data", ":desktop-integration", ":desktopApp")
include(":desktop-media")
