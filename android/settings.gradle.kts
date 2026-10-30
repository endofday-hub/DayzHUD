pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://devrepo.tmapadmin.com/repository/tmap-sdk-release/") // TMAP EDC SDK
        maven("https://devrepo.kakaomobility.com/repository/kakao-mobility-android-knsdk-public/") // 카카오내비 SDK
        maven("https://www.jitpack.io") // 카카오내비 SDK 의존성
    }
}
rootProject.name = "DayzHUD"
include(":app")
