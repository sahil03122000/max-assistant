import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Server ka address aur token android/max.properties se aate hain (git mein nahi jaati).
val maxProps = Properties().apply {
    rootProject.file("max.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Version = git commits ki ginti — har nayi release apne aap bada number (App update isi se pehchanta hai).
val gitCommits = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toIntOrNull() ?: 1

android {
    namespace = "com.max.assistant"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.max.assistant"
        minSdk = 28
        targetSdk = 37
        versionCode = gitCommits
        versionName = "0.$gitCommits"

        // Sirf 64-bit ARM (aaj ke saare phones) — Voice ID/Vosk ki dusri CPU wali files se app 50+ MB bhaari hota.
        ndk { abiFilters += "arm64-v8a" }

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
    // Max ka apna camera (selfie coach, photo, video)
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.camera:camera-video:1.6.2")
    // Chehra pehchanna — phone par hi, offline (model app ke andar bundled)
    implementation("com.google.mlkit:face-detection:16.1.7")
    // "Hello Max" wake word — offline speech recognition (Apache 2.0, commercial use free)
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // "Sirf meri aawaz" (Voice ID) — offline speaker recognition (Apache 2.0). Bada hai, git mein nahi:
    //   bash android/download-voice-id.sh
    implementation(files("libs/sherpa-onnx.aar"))
    // Live mode (Gemini Live) ka WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
