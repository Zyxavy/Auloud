import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.auloud.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.auloud.player"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // USER: release signing (CP8). Generate your key ONCE (see
    // docs/ReleaseSigning.md), keep the .keystore file OUTSIDE the repo with
    // a backup, and point these four keys at it in player/local.properties
    // (gitignored, never commit). Without them the release build falls back
    // to debug signing with a loud warning, so CI/JVM tests never break.
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun releaseKey(name: String): String? =
        localProps.getProperty(name)?.takeIf { it.isNotBlank() }
    val keystorePath = releaseKey("auloud.keystore.path")
    val keystoreStorePassword = releaseKey("auloud.keystore.storePassword")
    val keystoreKeyAlias = releaseKey("auloud.keystore.keyAlias")
    val keystoreKeyPassword =
        releaseKey("auloud.keystore.keyPassword") ?: keystoreStorePassword
    val hasReleaseKey = !keystorePath.isNullOrBlank() &&
        !keystoreStorePassword.isNullOrBlank() &&
        !keystoreKeyAlias.isNullOrBlank() &&
        rootProject.file(keystorePath).exists()
    if (!hasReleaseKey) {
        println(
            "WARNING: [Auloud] no release keystore configured " +
                "(auloud.keystore.* in local.properties) — " +
                "signing the release APK with the debug key instead."
        )
    }
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystorePath!!)
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    // Slice 1 playback + storage stack (WP1 pins, WP6/WP3 use them later).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    // PW7b (Slice 7 S7B approval covers this artifact): sherpa-onnx JitPack
    // AAR for the Piper tier (minSdk 21, Apache-2.0 POM). Native libs
    // statically link espeak-ng — SHIP decision stays open (D-066).
    implementation(libs.sherpa.onnx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit.ext)
}
