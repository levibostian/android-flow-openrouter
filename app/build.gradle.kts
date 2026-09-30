plugins {
    id("com.android.application")
}

android {
    namespace = "earth.levi.flowopenrouter"
    compileSdk = 37

    defaultConfig {
        applicationId = "earth.levi.flowopenrouter"
        minSdk = 26
        targetSdk = 35
        // CI sets these (see .github/workflows/deploy-app.yml) to stamp each release.
        versionCode = System.getenv("ANDROID_APP_BUILD_NUMBER")?.toInt() ?: 1
        versionName = System.getenv("ANDROID_APP_VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        create("release") {
            // CI provides these (see .github/workflows/deploy-app.yml). Fake path avoids a file() error during local dev.
            storeFile = file(System.getenv("ANDROID_SIGNING_KEY_FILE_PATH") ?: "/fake/path")
            keyAlias = "upload"
            storePassword = System.getenv("ANDROID_SIGNING_KEY_STORE_PASSWORD")
            keyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
