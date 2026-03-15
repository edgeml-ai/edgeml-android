pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "octomil-android"
include(":octomil")
include(":sample")

// llama.cpp Android library — composite build from reference project.
// The :lib module compiles llama.cpp from source via CMake (NDK 29 + CMake 3.31.6).
// First build is slow (~5 min); subsequent builds use the native cache.
includeBuild("../research/engines/llama.cpp/examples/llama.android") {
    dependencySubstitution {
        substitute(module("com.arm.aichat:lib")).using(project(":lib"))
    }
}
