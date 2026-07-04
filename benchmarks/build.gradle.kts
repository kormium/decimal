import kotlinx.benchmark.gradle.JvmBenchmarkTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.allopen")
    id("org.jetbrains.kotlinx.benchmark")
}

// JMH requires benchmark classes to be open; kotlinx-benchmark shares the annotation.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

kotlin {
    jvm()
    // The native pair covers the two hosts we actually measure on. java.math exists only
    // on the JVM, so native runs compare Decimal against ionspin alone.
    macosArm64()
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":decimal"))
                implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:0.4.17")
                implementation("com.ionspin.kotlin:bignum:0.3.10")
            }
        }
    }
}

benchmark {
    targets {
        register("jvm") {
            this as JvmBenchmarkTarget
            jmhVersion = "1.37"
        }
        register("macosArm64")
        register("linuxX64")
    }
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "us"
        }
        // Wiring check: one short iteration of everything (used in CI and for local sanity).
        register("smoke") {
            warmups = 1
            iterations = 1
            iterationTime = 200
            iterationTimeUnit = "ms"
            mode = "avgt"
            outputTimeUnit = "us"
        }
    }
}
