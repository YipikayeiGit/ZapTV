plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zap.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zap.iptv"
        minSdk = 24
        targetSdk = 34
        // Número de compilación de GitHub: así la app sabe si hay una versión más nueva
        val build = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.4 ($build)"
    }

    // La clave está cifrada; su contraseña solo existe en los Secrets de GitHub (KEYSTORE_PASSWORD)
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    if (System.getenv("CI") != null && keystorePassword.isNullOrEmpty()) {
        throw GradleException("Falta el secret KEYSTORE_PASSWORD en GitHub (Settings → Secrets and variables → Actions)")
    }
    signingConfigs {
        if (!keystorePassword.isNullOrEmpty()) {
            create("release") {
                storeFile = file("keystore/zap.jks")
                storePassword = keystorePassword
                keyAlias = "zap"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.6.1"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
}
