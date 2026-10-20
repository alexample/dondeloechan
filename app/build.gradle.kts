plugins {
    id("com.android.application")
}

android {
    namespace = "es.dondeloechan.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.dondeloechan.app"
        minSdk = 24
        targetSdk = 35
        // GitHub Actions pasa el número de compilación para que cada APK nueva se instale encima de la anterior.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("fija") {
            // Clave fija guardada en el repo para poder actualizar la app sin desinstalar.
            // Antes de publicar en Google Play, cámbiala por una clave privada guardada como secreto.
            storeFile = rootProject.file("keystore/dondeloechan.keystore")
            storePassword = "android"
            keyAlias = "dondeloechan"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
        debug {
            signingConfig = signingConfigs.getByName("fija")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core:1.13.1")
}
