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
    }
}
rootProject.name = "DayzHUD"
include(":app")
