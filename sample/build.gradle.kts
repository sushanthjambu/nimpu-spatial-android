plugins {
    alias(libs.plugins.android.application)
}

import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun localOrEnv(name: String): String =
    (localProperties.getProperty(name) ?: System.getenv(name)).orEmpty()

fun localOrEnvBoolean(name: String, defaultValue: Boolean): Boolean =
    localOrEnv(name).trim().takeIf { it.isNotEmpty() }?.toBooleanStrictOrNull() ?: defaultValue

android {
    namespace = "com.example.spatialsdk.sample"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.spatialsdk.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "NIMPU_API_KEY", "\"${localOrEnv("NIMPU_API_KEY")}\"")
        buildConfigField(
            "boolean",
            "NIMPU_ENABLE_GEOSPATIAL_GUIDANCE",
            localOrEnvBoolean("NIMPU_ENABLE_GEOSPATIAL_GUIDANCE", true).toString()
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":sdk"))
    implementation(libs.arcore)
    implementation(libs.play.services.auth)
    implementation(libs.play.services.location)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
