pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK the test toolchain pins (the differential oracle needs 21+).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "kormium-decimal"

include(":decimal")
include(":benchmarks")
