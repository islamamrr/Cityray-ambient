plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.carapps.guestbook"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.carapps.guestbook"
        minSdk = 26
        // The head unit runs Android 11, so target it directly.
        targetSdk = 30
        versionCode = 1
        versionName = "0.1"
    }

    // Same fixed key as Car Ambient, so each new build installs over the last one.
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
