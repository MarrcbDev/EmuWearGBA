plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.marrdev.mgbawear"
    compileSdk = 34
    // En GitHub Actions usamos la versión del NDK preinstalada en el runner
    System.getenv("ANDROID_NDK_LATEST_HOME")?.let { ndkVersion = File(it).name }

    defaultConfig {
        applicationId = "com.marrdev.mgbawear"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    signingConfigs {
        // Firma fija para que cada APK nuevo se pueda instalar encima del anterior
        if (file("debug.keystore").exists()) {
            getByName("debug") {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // misma firma fija
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } } // extrae los .so para poder usar dlopen
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
