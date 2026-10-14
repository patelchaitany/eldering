import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // No org.jetbrains.kotlin.android: AGP 9 compiles Kotlin itself ("built-in Kotlin").
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

/**
 * Public API origin (SPEC "Server"), https only. Used by release builds, and by debug builds unless a debug
 * override is set. Set `eldering.baseUrl` in local.properties (not committed) or the ELDERING_BASE_URL environment
 * variable.
 */
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun configValue(property: String, envVar: String): String? =
    (localProps.getProperty(property) ?: System.getenv(envVar))?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

val baseUrl = configValue("eldering.baseUrl", "ELDERING_BASE_URL") ?: "https://safety.example.com"
require(baseUrl.startsWith("https://")) {
    "eldering.baseUrl must be an https URL (it ships in release builds): $baseUrl. " +
        "For a local http backend set eldering.debugBaseUrl instead."
}

/**
 * Optional DEBUG-ONLY API origin, e.g. a local backend for the emulator: `eldering.debugBaseUrl=http://10.0.2.2:8095`
 * in local.properties (or ELDERING_DEBUG_BASE_URL). Release builds never see it. Cleartext http is accepted only for
 * 10.0.2.2 (the emulator's host loopback) and localhost (`adb reverse`), matching src/debug/res/xml/
 * network_security_config.xml and AppConfig's debug rule; anything else must be https.
 */
val debugBaseUrl = configValue("eldering.debugBaseUrl", "ELDERING_DEBUG_BASE_URL") ?: baseUrl
require(
    debugBaseUrl.startsWith("https://") ||
        Regex("""http://(?:10\.0\.2\.2|localhost)(?::(\d{1,5}))?""").matchEntire(debugBaseUrl)
            ?.groupValues?.get(1)?.let { it.isEmpty() || it.toInt() in 1..65535 } == true,
) {
    "eldering.debugBaseUrl must be https, or http://10.0.2.2[:port] / http://localhost[:port]: $debugBaseUrl"
}
val mapStyleUrl = "https://tiles.openfreemap.org/styles/liberty"

android {
    namespace = "com.eldering.safety"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.eldering.safety"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // MapLibre ships ~12 MB of native code per ABI: keep the phone ABI and the emulator ABI only.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        debug {
            // Deliberately no applicationIdSuffix: the package stays com.eldering.safety in debug builds
            // (adb/debug-inject commands in SPEC use that package name).
            buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")
            buildConfigField("String", "MAP_STYLE_URL", "\"$mapStyleUrl\"")
        }
        release {
            buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
            buildConfigField("String", "MAP_STYLE_URL", "\"$mapStyleUrl\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true // AGP 9: off by default
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Robolectric
            isReturnDefaultValues = true
            all {
                // Robolectric on JDK 17+ (Gradle runs on JDK 21). Without these every Robolectric test fails with
                // "Failed to interact with raw FileDescriptor internals". https://robolectric.org/getting-started/
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                )
            }
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*.md")
    }
}

// Built-in Kotlin: compiler options live in the top-level kotlin {} block (android.kotlinOptions was removed).
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

room {
    schemaDirectory("$projectDir/schemas") // commit app/schemas/*.json once a @Database exists
}

dependencies {
    implementation(project(":detection"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service) // LifecycleService for SafetyService
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services) // Task.await()
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.play.services.location)
    implementation(libs.maplibre.android.sdk)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
