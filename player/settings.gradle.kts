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
        // Slice 7 spike only: sherpa-onnx publishes via JitPack, not
        // Maven Central (user-approved; throwaway module, never ships).
        maven("https://jitpack.io")
    }
}
rootProject.name = "Auloud"
include(":app")
include(":spike")
