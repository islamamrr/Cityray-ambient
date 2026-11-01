plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.carapps.ambient"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.carapps.ambient"
        minSdk = 26
        // The head unit runs Android 11, so target it directly.
        targetSdk = 30
        versionCode = 2
        versionName = "0.2"
    }

    // A fixed key committed to the repo, so each new build installs over the last one.
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
    }
}

dependencies {
    implementation(project(":carlib"))
}
