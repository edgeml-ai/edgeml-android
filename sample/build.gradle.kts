import java.util.Properties

plugins {
    id("com.android.application")
}

// Load local.properties for optional config overrides
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "ai.edgeml.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.edgeml.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "EDGEML_SERVER_URL",
            "\"${localProperties.getProperty("EDGEML_SERVER_URL", "https://api.edgeml.ai")}\"",
        )
        buildConfigField(
            "String",
            "EDGEML_DEVICE_TOKEN",
            "\"${localProperties.getProperty("EDGEML_DEVICE_TOKEN", "")}\"",
        )
        buildConfigField(
            "String",
            "EDGEML_ORG_ID",
            "\"${localProperties.getProperty("EDGEML_ORG_ID", "")}\"",
        )
        buildConfigField(
            "String",
            "EDGEML_MODEL_ID",
            "\"${localProperties.getProperty("EDGEML_MODEL_ID", "")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":edgeml"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.8.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    implementation("com.google.android.material:material:1.13.0")
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
