// Top-level build file for EdgeML Android SDK
buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    id("org.sonarqube") version "7.2.2.6593"
}

sonarqube {
    properties {
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.organization", "edgeml-ai")
        property("sonar.projectKey", "edgeml-ai_edgeml-android")
        property("sonar.projectName", "EdgeML Android SDK")
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.exclusions", "**/R.java,**/*.xml,**/BuildConfig.java,**/Manifest*.*,**/*Test*.*")
        property("sonar.coverage.exclusions", "**/*Test*.*,**/test/**")
    }
}

subprojects {
    sonarqube {
        properties {
            if (project.name == "edgeml") {
                property("sonar.coverage.jacoco.xmlReportPaths",
                    "${project.layout.buildDirectory.get()}/reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
            }
        }
    }
}

tasks.register("clean", Delete::class) {
    group = "build"
    description = "Deletes the root project build directory."
    delete(rootProject.layout.buildDirectory)
}
