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
        // Kadb 1.2.1 (Car Probe's own-adb steering read) needs its SPAKE2 library from JitPack.
        // Limited to that one group so nothing else can come from there.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.flyfishxu.spake2-java") }
        }
    }
}

rootProject.name = "CarAmbient"
include(":app")
include(":carlib")
include(":probe")
include(":tumbler")
