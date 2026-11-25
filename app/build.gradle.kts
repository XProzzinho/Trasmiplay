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
        // o GitHub Actions passa o número do build: cada build novo é uma versão maior
        val build = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
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
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("shots", System.getProperty("shots") ?: "") }
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
