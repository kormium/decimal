plugins {
    // Applied in the library subprojects; declared here so all modules share one version.
    kotlin("multiplatform") version "2.4.0" apply false
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.8" apply false
    // Applied at the root: validates the public ABI of every subproject (JVM + klib).
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

apiValidation {
    // Also track the Kotlin/Native + js/wasm ABI, not just the JVM one.
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

allprojects {
    repositories {
        mavenCentral()
    }
}
