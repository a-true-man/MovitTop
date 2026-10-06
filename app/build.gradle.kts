import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing — keystore.properties (gitignored) holds the store/key
// passwords; it's absent on CI/other machines, so release signing there
// silently falls back to unsigned rather than failing the build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "iam699030.gmail.movitop"
    compileSdk = 36

    defaultConfig {
        applicationId = "iam699030.gmail.movitop"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Real phones are effectively 100% arm64-v8a — x86_64 only matters
        // for emulators/Chromebooks, which isn't who this app ships to (see
        // package_motis_data.sh: APKs are handed out directly, not through
        // Play Store's per-device delivery, so whatever ships here is the
        // one file every user installs). libmotis.so alone is ~95-100MB per
        // ABI, so a universal APK effectively pays for it twice. The
        // x86_64 .so under app/src/main/jniLibs/x86_64/ stays on disk
        // unchanged — compile_motis_graph.sh still uses it directly as its
        // dev-machine preprocessing binary, this filter only affects what
        // Gradle packages into the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // The MOTIS engine binary ships as app/src/main/jniLibs/<abi>/libmotis.so
    // (see build_motis_arm64.sh). It must land on disk under the app's
    // nativeLibraryDir as a real, installer-extracted file — Android 10+
    // forbids exec() of anything the app itself wrote (and can therefore
    // also make executable) inside its own writable data directory, so a
    // copy-to-filesDir-then-chmod approach is blocked on real devices.
    // useLegacyPackaging forces that on-disk extraction instead of leaving
    // native libs page-aligned/mmapped straight out of the (compressed) APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Mapsforge offline vector maps
    implementation(libs.mapsforge.core)
    implementation(libs.mapsforge.map)
    implementation(libs.mapsforge.map.reader)
    implementation(libs.mapsforge.themes)
    implementation(libs.mapsforge.map.android)
    implementation(libs.androidsvg)

    // Networking (MOTIS REST API)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}