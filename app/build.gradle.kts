plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "io.github.myronl.ultiworldtv"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.myronl.ultiworldtv"
        minSdk = 26
        targetSdk = 36
        // Raise both for every release: a TV only installs an APK over one with a lower versionCode, and the
        // release workflow only publishes a tag named v<versionName>.
        versionCode = 2
        versionName = "0.2"
        // Whether the app updates itself from the GitHub releases; only the published, release-signed APK does.
        buildConfigField("boolean", "UPDATES", "false")
    }

    // The published APK is signed with one release key, kept out of the repo (docs/releasing.md), so each release
    // installs over the last. Built without it, as install.sh does, a release build uses this computer's debug key.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE")
    if (releaseKeystore != null) {
        fun env(name: String) = System.getenv(name) ?: error("RELEASE_KEYSTORE is set but $name isn't")
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = env("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = env("RELEASE_KEY_ALIAS")
            keyPassword = env("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
            buildConfigField("boolean", "UPDATES", (releaseKeystore != null).toString())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += "GradleDependency"
    }
}

ktlint {
    version.set("1.8.0")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.tv:tv-material:1.1.0")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    // QR code for logging in from a phone. Just the encoder: plain Java, no camera or Android parts.
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
