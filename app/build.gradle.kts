plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.surto.fundacauto"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.surto.fundacauto"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
    }

    // chave fixa: as próximas versões instalam por cima, sem precisar desinstalar
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.android.gms:play-services-tasks:18.0.2")
}
