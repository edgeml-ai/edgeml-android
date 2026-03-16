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

// llama.cpp Android library — composite build from reference project.
// The :lib module compiles llama.cpp from source via CMake (NDK 29 + CMake 3.31.6).
// First build is slow (~5 min); subsequent builds use the native cache.
// Conditional: skipped when this repo is a submodule (parent already includes it).
val llamaAndroidDir = file("../research/engines/llama.cpp/examples/llama.android")
if (llamaAndroidDir.exists()) {
    includeBuild(llamaAndroidDir) {
        dependencySubstitution {
            substitute(module("com.arm.aichat:lib")).using(project(":lib"))
        }
    }
}

// sherpa-onnx Android library — streaming speech-to-text.
// Conditional: only included when the sherpa-onnx repo is present.
val sherpaDir = file("../research/engines/sherpa-onnx/android/SherpaOnnxAar")
if (sherpaDir.exists()) {
    includeBuild(sherpaDir) {
        dependencySubstitution {
            substitute(module("com.k2fsa.sherpa:onnx")).using(project(":sherpa_onnx"))
        }
    }
}
