import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: no Android imports allowed (SPEC). Unit-testable on the plain JVM and replayable from :app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Not jvmToolchain(17): Gradle does not auto-detect Homebrew JDKs and no toolchain download repo is configured.
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    explicitApi()
}

dependencies {
    testImplementation(kotlin("test"))            // kotlin-test-junit5 under useJUnitPlatform()
    testImplementation(platform(libs.junit.bom))  // aligns Jupiter/Platform to the catalog version
    testImplementation(libs.junit.jupiter)        // includes junit-jupiter-params
    testRuntimeOnly(libs.junit.platform.launcher) // required since Gradle 9
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
