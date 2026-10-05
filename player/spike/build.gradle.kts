plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Slice 7 throwaway: benchmark spike, deleted after the gate (S7D).
    // Debug only, never signed, never released, never in the Player.
    namespace = "app.auloud.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.auloud.spike"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "s7spike"
    }

    buildTypes {
        release {
            // No release: a release build of the spike is a mistake.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    // Slice 7 spike only (user-approved S7B): JitPack AAR, minSdk 21,
    // POM Apache-2.0; native libs statically link espeak-ng (Slice7.md
    // research) — throwaway module, never ships (D-066 open).
    implementation(libs.sherpa.onnx)
}
