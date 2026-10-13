import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Server ka address aur token android/max.properties se aate hain (git mein nahi jaati).
val maxProps = Properties().apply {
    rootProject.file("max.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.max.assistant"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.max.assistant"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MAX_SERVER_URL", "\"${maxProps.getProperty("max.serverUrl", "http://192.168.1.11:3000")}\"")
        buildConfigField("String", "MAX_API_TOKEN", "\"${maxProps.getProperty("max.apiToken", "")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // "Hello Max" wake word — offline speech recognition (Apache 2.0, commercial use free)
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
