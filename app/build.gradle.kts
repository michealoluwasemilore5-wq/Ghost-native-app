plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mgghost.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mgghost.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "6.2.1"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "API_BASE_URL", "\"https://mg-ghost-api.onrender.com\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"https://mg-ghost-api.onrender.com\"")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
