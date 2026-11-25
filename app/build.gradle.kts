plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.geanalves.transmiplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geanalves.transmiplay"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        create("geanplay") {
            storeFile = file("release.jks")
            storePassword = "geanplay"
            keyAlias = "geanplay"
            keyPassword = "geanplay"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("geanplay")
        }
        debug {
            signingConfig = signingConfigs.getByName("geanplay")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
