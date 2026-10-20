pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Hearth"
include(":app", ":common-jni")
include(":hearth-tts", ":hearth-tts-supertonic", ":hearth-tts-magpie")
include(":hearth-tts-omnivoice")
include(":hearth-onnx-runtime")
include(":hearth-tts-moss")
include(":hearth-tts-oute")
include(":hearth-tts-chatterbox")
