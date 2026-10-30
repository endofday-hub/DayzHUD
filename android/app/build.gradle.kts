import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// android/secrets.properties → BuildConfig.TMAP_* (파일 없으면 빈 문자열)
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.dayz.hud"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dayz.hud"
        minSdk = 29 // MediaStore.Downloads
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        for (key in listOf("TMAP_CLIENT_ID", "TMAP_API_KEY", "TMAP_USER_KEY", "TMAP_DEVICE_KEY")) {
            buildConfigField("String", key, "\"${secrets.getProperty(key, "").trim()}\"")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.tmapmobility.tmap:tmap-edc-sdk:1.0.0.0051")
    testImplementation("junit:junit:4.13.2")
}
