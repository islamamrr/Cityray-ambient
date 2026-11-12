plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.carapps.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.carapps.probe"
        minSdk = 26
        targetSdk = 30
        versionCode = 17
        versionName = "0.17"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Kadb 1.2.1 was built with Kotlin 2.1.0-RC2, one step ahead of this project's compiler.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check", "-Xskip-prerelease-check")
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
    }
}

dependencies {
    implementation(project(":carlib"))
    // Shell-uid access for the vendor steering read (Probe only, not carlib).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Own adb client, so the head unit can read its steering angle without a laptop (SelfAdb).
    implementation("com.flyfishxu:kadb:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
