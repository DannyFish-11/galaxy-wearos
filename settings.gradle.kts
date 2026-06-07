pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "galaxy-wearos"
include(":app")
// PR-SHARED-TRANSPORT: Include shared transport module from Android project
include(":shared-transport")
project(":shared-transport").projectDir =
    file("${settingsDir}/../ufo-galaxy-android/shared-transport")
// PR-SHARED-PROTOCOL: Include shared protocol module from Android project
include(":shared-protocol")
project(":shared-protocol").projectDir =
    file("${settingsDir}/../ufo-galaxy-android/shared-protocol")
