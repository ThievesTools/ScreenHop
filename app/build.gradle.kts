plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.screenhop"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.screenhop"
        minSdk = 30          // needed for per-display window info
        targetSdk = 35
        // Bump both for every release. The build workflow names the release after versionName.
        versionCode = 9
        versionName = "1.6.0"
    }

    // A fixed key in the project, so every new build installs over the previous one.
    // Fine for a personal app; anyone with this project could sign an "update" with it,
    // so only install ScreenHop builds from your own repository.
    signingConfigs {
        create("screenhop") {
            storeFile = file("screenhop.keystore")
            storePassword = "screenhop"
            keyAlias = "screenhop"
            keyPassword = "screenhop"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("screenhop")
        }
        debug {
            signingConfig = signingConfigs.getByName("screenhop")
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

// No dependencies on purpose: plain Android framework + Kotlin standard library only.
