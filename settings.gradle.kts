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
        mavenLocal() // For local engine AAR testing before Maven Central publish
    }
}

rootProject.name = "octomil-android"
include(":octomil")
include(":samples")

// Engine composite builds — substitute published Maven coordinates with local projects
// when engine repos are present on disk. This enables local development without
// requiring published AARs on Maven Central.
val llamaAndroidDir = file("../research/engines/llama.cpp/examples/llama.android")
if (llamaAndroidDir.exists()) {
    includeBuild(llamaAndroidDir) {
        dependencySubstitution {
            substitute(module("ai.octomil:octomil-runtime-llama-android")).using(project(":lib"))
        }
    }
}

val sherpaDir = file("../research/engines/sherpa-onnx/android/SherpaOnnxAar")
if (sherpaDir.exists()) {
    includeBuild(sherpaDir) {
        dependencySubstitution {
            substitute(module("ai.octomil:octomil-runtime-sherpa-android")).using(project(":sherpa_onnx"))
        }
    }
}
