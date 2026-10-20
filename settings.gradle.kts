pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Hearth"
include(":app", ":common-jni")
include(":hearth-tts", ":hearth-tts-supertonic", ":hearth-tts-magpie")
