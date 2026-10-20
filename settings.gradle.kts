pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Hearth"
if (!providers.gradleProperty("hearthDesktopOnly").map(String::toBoolean).getOrElse(false)) {
    include(":app", ":common-jni")
    include(":hearth-tts", ":hearth-tts-supertonic", ":hearth-tts-magpie")
    include(":hearth-tts-omnivoice")
    include(":hearth-onnx-runtime")
    include(":hearth-tts-moss")
    include(":hearth-tts-oute")
    include(":hearth-tts-chatterbox")
}

// Desktop shares contracts and pipeline policy; Android keeps its existing platform adapters.
include(":hearth-core", ":desktop-runtime", ":desktop-data", ":desktop-integration", ":desktopApp")
include(":desktop-media")
