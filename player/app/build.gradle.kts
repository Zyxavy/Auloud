import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

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
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // VC2 (D-129): ARM-only native packaging. The sherpa-onnx AAR
        // ships four ABIs; x86/x86_64 are emulator-only and cost about
        // 73 MB raw. The Tab E needs armeabi-v7a; arm64-v8a covers
        // modern devices. No per-ABI splits: one universal APK keeps
        // sideloading and upgrades simple (see D-129 for the numbers).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
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
    // VC1 (D-126 option A): two licensed flavors on one dimension. `core`
    // is the main Apache-2.0 release and ships no
    // sherpa-onnx/onnxruntime/espeak code; `full` adds the bundled Piper
    // tier (sherpa-onnx with static espeak-ng) and is distributed under
    // GPL-3.0 (see player/LICENSE.full plus the in-APK assets). Both
    // flavors keep the same applicationId and version, so either one
    // upgrades a v1 install in place. minSdk 24, no new permission, no
    // new dependency on either flavor.
    flavorDimensions += "license"
    productFlavors {
        create("core") {
            dimension = "license"
        }
        create("full") {
            dimension = "license"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Slice 9 IN4 (D-079, D-085): jsoup needs core library desugaring
        // with the NIO spec on Android (see https://jsoup.org/download).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

// IN1: export the Room schema so migrations have history to diff against.
// Schemas land in player/app/schemas/<db>/<version>.json and are committed.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    // Slice 9 IN4: jsoup HTML walker for on-device EPUB ingestion (D-079).
    implementation(libs.jsoup)
    // Build-time only: desugared Java 8 + NIO classes for the jsoup pin
    // (D-085). Never referenced from source; AGP bundles what is needed.
    coreLibraryDesugaring(libs.desugar.nio)
    // PW7b (Slice 7 S7B approval covers this artifact): sherpa-onnx JitPack
    // AAR for the Piper tier (minSdk 21, Apache-2.0 POM). Native libs
    // statically link espeak-ng (D-126: option A - full ships GPL-3.0).
    // VC1: full-flavor only, so `core` builds with zero sherpa files.
    // Pin: com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8 via JitPack.
    // String form (not the type-safe accessor) so the script compiles
    // without a prior sync generating the flavor configuration.
    "fullImplementation"(libs.sherpa.onnx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    // VC5: virtual time for the reader debounce tests (test-only).
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit.ext)
}

// VC1 (D-126 option A): license scan over the built core/full archives.
// Run: .\gradlew.bat --no-daemon :app:assembleCoreDebug :app:assembleFullDebug :app:licenseScan
// (from player/; release archives under outputs/bundle are scanned too when present).
// Core must carry no sherpa/onnxruntime/espeak files or library markers;
// full must carry the sherpa files plus its notices (GPL text, source
// offer, NOTICE assets). App-level prose mentions (own class names, help
// text, Scribe-side license rows) are not library code: entry names and
// the com/k2fsa + onnxruntime + espeak-ng-data dex markers below are what
// prove the native libs ship. Uses only the JDK zip API (no dependency).
tasks.register("licenseScan") {
    group = "verification"
    description = "VC1: fail when core ships sherpa/onnxruntime/espeak files or markers, or full lacks its notices."
    doLast {
        val outputs = layout.buildDirectory.dir("outputs").get().asFile
        val fileMarkers = listOf("sherpa", "onnx", "espeak")
        val dexMarkers = listOf("com/k2fsa", "onnxruntime", "espeak-ng-data")
        val fullAssets = listOf("assets/gpl-3.0.txt", "assets/SOURCE_OFFER.txt", "assets/NOTICE.txt")
        fun archives(flavor: String): List<File> {
            val roots = listOf(outputs.resolve("apk/$flavor"), outputs.resolve("bundle/$flavor"))
            return roots.filter { it.isDirectory }.flatMap { root ->
                root.walkTopDown().filter { it.isFile && (it.extension == "apk" || it.extension == "aab") }.toList()
            }
        }
        fun entriesOf(archive: File): Pair<List<String>, ByteArray> {
            val names = mutableListOf<String>()
            val dex = ByteArrayOutputStream()
            ZipFile(archive).use { zip ->
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val e = enumeration.nextElement()
                    names.add(e.name)
                    if (!e.isDirectory && e.name.endsWith(".dex")) {
                        zip.getInputStream(e).use { it.copyTo(dex) }
                    }
                }
            }
            return names to dex.toByteArray()
        }
        // Latin-1 keeps every byte readable for marker search (no decode fail).
        fun ByteArray.asSearchable(): String = String(this, Charsets.ISO_8859_1)
        var failures = 0
        for (flavor in listOf("core", "full")) {
            val found = archives(flavor)
            if (found.isEmpty()) {
                logger.error(
                    "licenseScan: no .apk/.aab for flavor '$flavor' under ${outputs.path}. " +
                        "Build first: :app:assembleCoreRelease :app:assembleFullRelease (or the Debug pair)"
                )
                failures++
                continue
            }
            for (archive in found) {
                val (names, dexBytes) = entriesOf(archive)
                val dexText = dexBytes.asSearchable()
                if (flavor == "core") {
                    val badEntries = names.filter { n -> fileMarkers.any { n.contains(it, ignoreCase = true) } }
                    val badDex = dexMarkers.filter { dexText.contains(it) }
                    val fullNotice = names.filter { it.startsWith("assets/") &&
                        (it.endsWith("gpl-3.0.txt") || it.endsWith("SOURCE_OFFER.txt")) }
                    if (badEntries.isNotEmpty() || badDex.isNotEmpty() || fullNotice.isNotEmpty()) {
                        logger.error("licenseScan FAIL core ${archive.name}: entries=$badEntries dex=$badDex notices=$fullNotice")
                        failures++
                    } else {
                        logger.lifecycle("licenseScan PASS core ${archive.name} (${names.size} entries, no lib markers)")
                    }
                } else {
                    val libEntries = names.filter { n -> fileMarkers.any { n.contains(it, ignoreCase = true) } }
                    val missingAssets = fullAssets.filter { want -> names.none { it == want } }
                    if (libEntries.isEmpty() || missingAssets.isNotEmpty()) {
                        logger.error("licenseScan FAIL full ${archive.name}: libEntries=${libEntries.size} missing=$missingAssets")
                        failures++
                    } else {
                        logger.lifecycle("licenseScan PASS full ${archive.name} (${libEntries.size} lib entries, notices present)")
                    }
                }
            }
        }
        if (failures > 0) throw GradleException("licenseScan: $failures failing archive(s), see errors above")
    }
}

// VC2 (D-129): merged-manifest INTERNET guard for the signed flavors.
// The unit test (ReleaseManifestTest) only sees the manifest source;
// this task reads the merged release manifests instead. Run after the
// release assembles (from player/):
// .\gradlew.bat --no-daemon :app:assembleCoreRelease :app:assembleFullRelease :app:releaseManifestCheck
tasks.register("releaseManifestCheck") {
    group = "verification"
    description = "VC2: fail when a merged release manifest requests INTERNET."
    doLast {
        val merged = layout.buildDirectory.dir("intermediates/merged_manifest").get().asFile
        val manifests = if (merged.isDirectory) {
            merged.walkTopDown().filter {
                it.isFile && it.name == "AndroidManifest.xml" && "Release" in it.path
            }.toList()
        } else {
            emptyList()
        }
        if (manifests.isEmpty()) {
            throw GradleException(
                "releaseManifestCheck: no merged release manifest under ${merged.path}. " +
                    "Build first: :app:assembleCoreRelease :app:assembleFullRelease"
            )
        }
        var failures = 0
        for (manifest in manifests) {
            if (manifest.readText().contains("android.permission.INTERNET")) {
                logger.error("releaseManifestCheck FAIL ${manifest.parentFile.name}: INTERNET present")
                failures++
            } else {
                logger.lifecycle("releaseManifestCheck PASS ${manifest.parentFile.name} (no INTERNET)")
            }
        }
        if (failures > 0) throw GradleException("releaseManifestCheck: $failures failing manifest(s)")
    }
}
