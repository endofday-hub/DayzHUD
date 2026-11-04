import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// android/secrets.properties → BuildConfig.TMAP_*, KAKAO_* (파일 없으면 빈 문자열)
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
        versionCode = 2
        versionName = "0.1.1"

        for (key in listOf("TMAP_CLIENT_ID", "TMAP_API_KEY", "TMAP_USER_KEY", "TMAP_DEVICE_KEY", "KAKAO_APP_KEY")) {
            buildConfigField("String", key, "\"${secrets.getProperty(key, "").trim()}\"")
        }
        // 카카오내비 SDK 네이티브 라이브러리 (폰은 arm)
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
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
    implementation("com.kakaomobility.knsdk:knsdk_ui:1.12.7")
    implementation("androidx.databinding:viewbinding:8.7.3") // KNSDK 화면이 쓰는데 의존성에 없음
    implementation("androidx.car.app:app:1.4.0") // Android Auto (CarNav.kt)
    testImplementation("junit:junit:4.13.2")
}
