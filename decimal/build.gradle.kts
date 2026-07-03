import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    explicitApi()

    jvm()

    js { nodejs() }
    wasmJs { nodejs() }
    wasmWasi { nodejs() }

    // Native: every tier of the Kotlin/Native target list — the library is pure Kotlin
    // (no cinterop), so each target is only compile time.
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    tvosArm64()
    tvosX64()
    tvosSimulatorArm64()
    watchosArm64()
    watchosX64()
    watchosSimulatorArm64()
    watchosDeviceArm64()
    mingwX64()
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// Forward the differential-suite knobs from the Gradle CLI into the test JVM
// (./gradlew :decimal:jvmTest -Ddecimal.differential.seed=...), and pin the test JVM to 21:
// the suite uses java.math.BigDecimal as its oracle, and JDK ≤17 has a movePointLeft(0)
// bug (returns a negative scale, violating its own javadoc) that JDK 21+ fixed.
tasks.withType<Test>().configureEach {
    listOf("decimal.differential.iterations", "decimal.differential.seed").forEach { p ->
        System.getProperty(p)?.let { systemProperty(p, it) }
    }
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// Failures must be readable straight from CI logs, message included.
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
    }
}

configure<MavenPublishBaseExtension> {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "decimal", version.toString())

    pom {
        name.set("kormium decimal")
        description.set(
            "A dependency-free arbitrary-precision decimal value type for Kotlin Multiplatform — " +
                "java.math.BigDecimal-compatible parsing, formatting, comparison, arithmetic and rounding " +
                "on JVM, JS, Wasm and all Kotlin/Native targets.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/kormium/decimal")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("knyazevs")
                name.set("Sergey Knyazev")
                email.set("sknyazev@vk.com")
                url.set("https://github.com/knyazevs")
            }
        }
        scm {
            url.set("https://github.com/kormium/decimal")
            connection.set("scm:git:https://github.com/kormium/decimal.git")
            developerConnection.set("scm:git:ssh://git@github.com/kormium/decimal.git")
        }
    }
}
