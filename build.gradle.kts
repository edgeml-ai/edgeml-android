// Top-level build file for EdgeML Android SDK
buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.21" apply false
    id("org.sonarqube") version "4.4.1.3373"
}

sonarqube {
    properties {
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.organization", "edgeml-ai")
        property("sonar.projectKey", "edgeml-ai_edgeml-android")
        property("sonar.projectName", "EdgeML Android SDK")
        property("sonar.sources", "edgeml/src/main/kotlin")
        property("sonar.tests", "edgeml/src/test/kotlin")
        property("sonar.java.binaries", "edgeml/build/intermediates/javac/debug/classes")
        property("sonar.coverage.jacoco.xmlReportPaths", "edgeml/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
        property("sonar.exclusions", "**/R.java,**/*.xml,**/BuildConfig.java,**/Manifest*.*,**/*Test*.*")
        property("sonar.coverage.exclusions", "**/*Test*.*,**/test/**")
        property("sonar.sourceEncoding", "UTF-8")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
