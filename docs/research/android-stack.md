# Android build stack — verified versions (2026-10-13)

Scope: `android/` Gradle root with `:app` (com.eldering.safety) and `:detection` (pure Kotlin/JVM), per SPEC.md.
Everything in **Profile A** below was **built and tested** on this Mac (Gradle 9.8.1 on JDK 21) and the map/font parts were **run on the OnePlus CPH2717** (Android 16). Lines marked *(unverified)* were not exercised.

---

## 0. TL;DR — the one thing that bites

The newest AndroidX/OkHttp releases now require **compileSdk 37**. AGP 9 fails `checkDebugAarMetadata` if any dependency has `minCompileSdk` above your `compileSdk`. SPEC requires compileSdk 36, and only `android-35` and `android-36` are installed. So Profile A pins the **newest version of each library whose AAR still allows compileSdk 36**. I checked the `minCompileSdk` value in each AAR's `META-INF/com/android/build/gradle/aar-metadata.properties`:

| Library | Newest stable | minCompileSdk | **Use (compileSdk 36)** | minCompileSdk |
|---|---|---|---|---|
| Compose BOM | 2026.09.00 (ui 1.12.1) | 37 | **2026.06.01** (ui 1.11.4, material3 1.4.0) | 35 |
| androidx.core:core-ktx | 1.19.1 | 37 | **1.18.0** | 36 |
| lifecycle-*-compose | 2.11.0 | 37 | **2.10.0** | 35 |
| navigation-compose | 2.10.2 | 37 | **2.9.8** | 35 |
| OkHttp (okhttp-android) | 5.5.0 | 37 | **5.4.0** | 36 |
| ui-text-google-fonts | 1.12.1 | 37 | **1.11.4** (via BOM) | 35 |

If SPEC is later changed to `compileSdk = 37` (Android 17 platform `android-37.0` is stable in Google's SDK repo; keep `targetSdk = 36`), use **Profile B** (§2). Install it with `sdkmanager "platforms;android-37.0"`. *(Profile B is unverified because platform 37 is not installed.)*

---

## 1. Profile A — verified version set (compileSdk 36 / targetSdk 36 / minSdk 29)

| Component | Version | Published | Notes / source |
|---|---|---|---|
| Gradle | 9.8.1 | 2026-10-07 | installed via brew |
| JDK for Gradle | 21 (Homebrew openjdk@21) | — | **not** JDK 25 |
| Android Gradle Plugin | **9.4.1** | 2026-09-18 | Needs Gradle ≥ 9.6.0 and JDK 17+. Supports API up to 37. Build-tools 36.0.0. [AGP 9.4 notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| Kotlin (KGP, Compose compiler, serialization plugin) | **2.4.21** | 2026-10-08 | GitHub JetBrains/kotlin. Kotlin 2.5.0 is still Beta1. |
| KSP | **2.3.12** | 2026-09-09 | KSP 2.3.x is versioned separately from Kotlin. 2.3.10 fixed "R-class resolution with AGP 9 built-in Kotlin" and "Kotlin 2.4.0 module names". [google/ksp releases](https://github.com/google/ksp/releases) |
| Compose BOM | **2026.06.01** | 2026-07-01 | ui/foundation/runtime 1.11.4, material3 1.4.0, material-icons 1.7.8 (frozen) |
| Material3 | 1.4.0 (from BOM) | — | Latest stable. 1.5.0 is beta01. |
| activity-compose | **1.13.0** | 2026-03-11 | minCompileSdk 36 |
| core-ktx | **1.18.0** | 2026-03-11 | |
| Lifecycle (runtime-ktx, runtime-compose, viewmodel-compose, service, process) | **2.10.0** | 2025-11-19 | keep **all** lifecycle artifacts on the same version |
| navigation-compose | **2.9.8** | 2026-04-22 | type-safe `@Serializable` routes |
| Room (+ KSP compiler, Gradle plugin `androidx.room`) | **2.8.5** | 2026-09-09 | Room 3 (`androidx.room3:*` 3.0.3) exists but uses a new package. Not chosen. |
| DataStore Preferences | **1.2.1** | 2026-03-11 | 1.3.0 is rc01 |
| WorkManager | **2.12.0** | 2026-09-23 | minCompileSdk 35 |
| kotlinx-coroutines | **1.11.0** | 2026-05-08 | includes `-play-services` for `Task.await()` |
| kotlinx-serialization-json | **1.11.0** | 2026-04-09 | 1.12.0 is RC only |
| Retrofit + `converter-kotlinx-serialization` | **3.0.0** | 2025-05-15 | Official Square converter. Do **not** use the old JakeWharton converter. |
| OkHttp (BOM, logging-interceptor, mockwebserver3) | **5.4.0** | — | Retrofit 3.0.0 and MapLibre both declare OkHttp 4.12. Gradle resolves them to 5.4.0, which works (tested, and the map renders on device). |
| play-services-location | **21.4.0** | 2026-06-25 | Fused location + Activity Recognition transitions |
| MapLibre Native Android `org.maplibre.gl:android-sdk` | **13.6.1** | 2026-09-08 | **Default artifact = Vulkan backend**. See §6. |
| ui-text-google-fonts | 1.11.4 (from BOM) | — | Design font is **Figtree** (docs/design/*.html) |
| JUnit 4 (`:app`, Robolectric) | 4.13.2 | — | Robolectric runs on JUnit 4 |
| JUnit 6 / Jupiter (`:detection`) | **6.1.3** (BOM) | 2026-08-07 | + `kotlin("test")` |
| Robolectric | **4.17** | 2026-09-10 | supports SDK 36/37. Needs `--add-opens` on JDK 17+ (§4) |
| Turbine | 1.2.1 | 2025-06-11 | |
| androidx.test core-ktx / ext-junit / runner / espresso | 1.7.0 / 1.3.0 / 1.7.0 / 3.7.0 | — | |

Ktor client 3.6.0 is the alternative to Retrofit + OkHttp. It was not chosen or verified. Retrofit is simpler with the MockWebServer tests.

### Verification actually run (scratch copy of the files below)
The scratch project is at `<temporary scratch dir>`. It is temporary, so everything needed is reproduced in §3, §6 and §7.
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
gradle clean :detection:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-build-cache
→ BUILD SUCCESSFUL in 30s
  :detection  PeakTest   3 tests, 0 failures   (JUnit 6.1.3 + kotlin.test, incl. @ParameterizedTest)
  :app        StackTest  3 tests, 0 failures   (Robolectric 4.17 @Config(sdk=[36]): Room+Turbine Flow,
                                                Retrofit3+kotlinx.serialization vs mockwebserver3, DataStore)
  assembleRelease with R8 (minify+shrinkResources): OK, no keep-rule errors. 27 MB with abiFilters arm64-v8a+x86_64
gradle buildEnvironment → kotlin-gradle-plugin 2.2.10 -> 2.4.21, symbol-processing-gradle-plugin 2.2.10-2.0.2 -> 2.3.12
Device (CPH2717, mt6897, Vulkan 1.3): MapLibre 13.6.1 rendered the OpenFreeMap Liberty style with a GeoJSON trail line and
  person dots in textureMode. Survived the background→foreground lifecycle.
  GoogleFont provider isAvailableOnDevice=true, Figtree preload=true. The scratch app was uninstalled afterwards.
```
Known noise: Gradle warns `Configuration.setVisible(boolean) has been deprecated`. The warning comes from AGP itself (`com.android.internal.application`) and is harmless. `Unable to strip … libmaplibre.so` appears because no NDK is installed. It is harmless, or you can install `ndk;28.2.13676358`.

---

## 2. Profile B — the latest set if compileSdk moves to 37 *(unverified)*
`compileSdk = 37` with `targetSdk = 36`. Version changes: `composeBom = "2026.09.00"` (ui 1.12.1), `coreKtx = "1.19.1"`, `lifecycle = "2.11.0"`, `navigationCompose = "2.10.2"`, `okhttp = "5.5.0"`. Everything else stays the same. AGP 9.4.1 supports API 37.

---

## 3. Files (ready to paste into `android/`)

### `android/settings.gradle.kts`
```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "eldering"
include(":app", ":detection")
```

### `android/build.gradle.kts` (root)
```kotlin
// Declare every plugin once with `apply false`, so all of them share one classloader.
// kotlin-jvm / kotlin-compose at 2.4.21 here also lift AGP 9's built-in KGP (2.2.10) to 2.4.21 in every module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
```

### `android/gradle.properties`
```properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.parallel=true
kotlin.code.style=official
# AGP 9 defaults (do not set): android.useAndroidX=true, android.newDsl=true, android.builtInKotlin=true,
# android.nonTransitiveRClass=true, targetSdk defaults to compileSdk, unit tests only for the debug build type.
```
The configuration cache stores and reuses entries with AGP 9.4.1, KSP and the Room plugin (verified).

### `android/local.properties` (not committed)
```properties
sdk.dir=/path/to/Android/sdk
```

### Wrapper
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd android && gradle wrapper --gradle-version 9.8.1 --distribution-type bin && ./gradlew --version
```
(I tried pinning the daemon JVM with `gradle updateDaemonJvm --jvm-version=21`. It fails because it needs toolchain download repositories, such as the foojay plugin. Rely on `JAVA_HOME` instead.)

### `android/gradle/libs.versions.toml`
```toml
[versions]
agp = "9.4.1"
kotlin = "2.4.21"
ksp = "2.3.12"
# AndroidX: newest releases whose AAR minCompileSdk <= 36 (see §0)
coreKtx = "1.18.0"
activityCompose = "1.13.0"
composeBom = "2026.06.01"
lifecycle = "2.10.0"
navigationCompose = "2.9.8"
room = "2.8.5"
datastore = "1.2.1"
work = "2.12.0"
coroutines = "1.11.0"
serialization = "1.11.0"
retrofit = "3.0.0"
okhttp = "5.4.0"
playServicesLocation = "21.4.0"
maplibre = "13.6.1"
junit4 = "4.13.2"
junit6 = "6.1.3"
turbine = "1.2.1"
robolectric = "4.17"
androidxTestCore = "1.7.0"
androidxTestExtJunit = "1.3.0"
androidxTestRunner = "1.7.0"
espresso = "3.7.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-text-google-fonts = { module = "androidx.compose.ui:ui-text-google-fonts" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-service = { module = "androidx.lifecycle:lifecycle-service", version.ref = "lifecycle" }
androidx-lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
androidx-room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
androidx-work-testing = { module = "androidx.work:work-testing", version.ref = "work" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-play-services = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-play-services", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-converter-kotlinx-serialization = { module = "com.squareup.retrofit2:converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp-bom = { module = "com.squareup.okhttp3:okhttp-bom", version.ref = "okhttp" }
okhttp = { module = "com.squareup.okhttp3:okhttp" }
okhttp-logging-interceptor = { module = "com.squareup.okhttp3:logging-interceptor" }
okhttp-mockwebserver3 = { module = "com.squareup.okhttp3:mockwebserver3" }
play-services-location = { module = "com.google.android.gms:play-services-location", version.ref = "playServicesLocation" }
maplibre-android-sdk = { module = "org.maplibre.gl:android-sdk", version.ref = "maplibre" }
junit4 = { module = "junit:junit", version.ref = "junit4" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit6" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core-ktx = { module = "androidx.test:core-ktx", version.ref = "androidxTestCore" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "espresso" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
```
There is deliberately **no** `kotlin-android` plugin entry; see §5. `material-icons-extended` is left out because it is frozen at 1.7.8 and adds a lot of debug dex (the debug APK was 92 MB). Add Material Symbols as vector drawables instead.

### `android/app/build.gradle.kts`
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // NO org.jetbrains.kotlin.android: AGP 9 compiles Kotlin itself ("built-in Kotlin").
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

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
        buildConfigField("String", "BASE_URL", "\"https://safety.example.com\"")
        buildConfigField("String", "MAP_STYLE_URL", "\"https://tiles.openfreemap.org/styles/liberty\"")
        // MapLibre ships ~12 MB of native code per ABI. Keep the phone ABI and the emulator ABI only.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
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
                // Robolectric on JDK 17+ (Gradle runs on JDK 21). Without these flags every test fails with
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

// Built-in Kotlin: use the top-level kotlin {} block (android.kotlinOptions {} was removed).
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

room {
    schemaDirectory("$projectDir/schemas") // commit app/schemas/*.json
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
    implementation(libs.androidx.lifecycle.service)   // LifecycleService for SafetyService
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

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
```
`app/proguard-rules.pro` can start empty. Retrofit 3, kotlinx.serialization and MapLibre ship consumer rules, and the R8 release build passed. With AGP 9's `android.r8.strictFullModeForKeepRules=true`, use `-keep class X { <init>(); }` when you need a default constructor kept.

### `android/detection/build.gradle.kts` (pure Kotlin/JVM, no Android)
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Do NOT use jvmToolchain(17): Gradle won't auto-detect Homebrew JDKs and toolchain download isn't configured.
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    // explicitApi() // optional; verified working
}

dependencies {
    testImplementation(kotlin("test"))            // becomes kotlin-test-junit5 under useJUnitPlatform()
    testImplementation(platform(libs.junit.bom))  // aligns Jupiter/Platform to 6.1.3 (overrides kotlin-test's 5.10.1)
    testImplementation(libs.junit.jupiter)        // includes params (@ParameterizedTest)
    testRuntimeOnly(libs.junit.platform.launcher) // required since Gradle 9
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
```
Verified test style: `org.junit.jupiter.api.Test` + `kotlin.test.assertEquals(expected, actual, absoluteTolerance = 1e-4f)`.

---

## 4. Testing notes (verified)
- **Robolectric + MapLibre:** never call `MapLibre.getInstance()` in `Application.onCreate()`. It loads `libmaplibre.so`, and every Robolectric test then fails with `UnsatisfiedLinkError: NativeConnectivityListener.initialize()`. It also slows the startup of the `SafetyService` process. Call it lazily just before creating a `MapView` (it is idempotent), as in §6.
- Robolectric: `@RunWith(AndroidJUnit4::class) @Config(sdk = [36])`. Use Room `inMemoryDatabaseBuilder(...).allowMainThreadQueries()` and Turbine `flow.test { awaitItem() }`. Both work.
- MockWebServer (OkHttp 5 "mockwebserver3" API, package `mockwebserver3`):
  ```kotlin
  val server = MockWebServer(); server.start()            // in @Before; server.close() in @After
  server.enqueue(MockResponse.Builder().code(200).body("""{"status":"ok"}""").build())
  val api = Retrofit.Builder().baseUrl(server.url("/v1/"))
      .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(Api::class.java)
  assertEquals("/v1/healthz", server.takeRequest().url.encodedPath)
  ```
  `mockwebserver3` depends on `okhttp-jvm`. In the `:app` unit-test classpath this coexists with `okhttp-android` without problems (verified).
- AGP 9 only creates unit tests for the **debug** build type: run `:app:testDebugUnitTest`. `./gradlew test` runs that task plus `:detection:test`.
- SPEC test-safety: no test may touch `SmsManager` or `Intent.ACTION_CALL`. Keep SMS behind an interface with a dry-run implementation in tests.

---

## 5. AGP 9 gotchas (from the [AGP 9.0 notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes) and [built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin); checked against this build)
1. **Built-in Kotlin is on by default.** Do not apply `org.jetbrains.kotlin.android`; it fails with "no longer required … since AGP 9.0". `kotlin-kapt` is incompatible, so use KSP (Room). If a library has no KSP processor, `com.android.legacy-kapt` is available.
2. **KGP version.** AGP 9.4.1 has a runtime dependency on KGP **2.2.10** and KSP 2.2.10-2.0.2. To get Kotlin 2.4.21, declare `kotlin-jvm` / `kotlin-compose` / `kotlin-serialization` at 2.4.21 in the **root** `plugins {}` with `apply false`. Gradle then resolves KGP 2.2.10 to 2.4.21 (verified with `gradle buildEnvironment`). The `buildscript { classpath(...) }` form in the AGP docs is equivalent.
3. **`android.kotlinOptions {}` is gone.** Use the top-level `kotlin { compilerOptions { … } }`. Extra Kotlin source directories go only in `android.sourceSets["main"].kotlin.directories`. `src/main/kotlin` works out of the box.
4. **New DSL only (`android.newDsl=true`).** `applicationVariants`, `variantFilter`, `BaseExtension` and similar are gone; use `androidComponents { onVariants { } / beforeVariants { } }`. `testOptions.unitTests.all { }` still works (verified).
5. **`minCompileSdk` is enforced.** A library's `minCompileSdk` above your `compileSdk` fails `checkDebugAarMetadata` (see §0). AGP 9 also requires consumers to compile with at least the library's compileSdk.
6. **Defaults changed:** `buildConfig` is off, so enable it (`BuildConfig.BASE_URL`). `resValues` is off. `targetSdk` defaults to compileSdk; set it explicitly anyway. `getDefaultProguardFile("proguard-android.txt")` is disallowed; use `-optimize.txt`. R8 strict full mode is on.
7. **Compatibility matrix caveat.** The [Kotlin docs](https://kotlinlang.org/docs/gradle-configure-project.html) list KGP 2.4.20–2.4.21 as *tested* with Gradle ≤ 9.7.0 and AGP ≤ 9.3.1. Our Gradle 9.8.1 + AGP 9.4.1 is newer than that range, but it built, tested and ran **without any KGP warning**. Fallback if something breaks: AGP **9.3.3** (needs Gradle ≥ 9.5.0, so it also works on 9.8.1).
8. AGP 9.4 itself: Gradle ≥ 9.6.0, build-tools 36.0.0, JDK 17+. `android.newDsl.optOut=:module` exists for gradual migration (not needed here).

---

## 6. MapLibre in Compose (verified on device)

**Artifact choice:** `org.maplibre.gl:android-sdk:13.6.1` is the **Vulkan** renderer. Its merged manifest adds `<uses-feature android:name="android.hardware.vulkan.version" android:version="0x400003" android:required="true"/>` together with INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, ACCESS_COARSE_LOCATION and ACCESS_FINE_LOCATION. The CPH2717 (MediaTek mt6897, Vulkan 1.3) renders it correctly. For non-Vulkan devices, swap to `org.maplibre.gl:android-sdk-opengl:13.6.1`, or to `android-sdk-vulkan-opengl` (switches backend at runtime). Same API. Note that `android-v13.5.3` (2026-10-08) backported MapRenderer race and Vulkan-surface crash fixes (#4686, #4705) that are not yet in a 13.6.x release. Watch for 13.6.2 or 13.7.0.

**Style:** `https://tiles.openfreemap.org/styles/liberty`. No API key and no MapLibre API key. Keep the attribution button enabled: OpenFreeMap and OpenStreetMap attribution comes from the style.

```kotlin
// MapLibreMap.kt — compiled, and run on CPH2717 (trail line + people dots, textureMode=true)
const val OPENFREEMAP_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
private const val TRAIL_SRC = "trail-src"
private const val PEOPLE_SRC = "people-src"
data class MapPerson(val id: String, val lat: Double, val lng: Double)

@Composable
fun MapLibreMap(
    modifier: Modifier = Modifier,
    trail: List<LatLng> = emptyList(),
    people: List<MapPerson> = emptyList(),
    styleUrl: String = OPENFREEMAP_LIBERTY,
    textureMode: Boolean = true, // TextureView: clips and animates correctly inside rounded cards and sheets
) {
    val context = LocalContext.current
    val mapView = remember { createMapView(context, textureMode) }
    var style by remember { mutableStateOf<Style?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle // androidx.lifecycle.compose.LocalLifecycleOwner
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); style = null; map = null; mapView.onDestroy() }
    }

    AndroidView(modifier = modifier, factory = {
        mapView.getMapAsync { m ->
            map = m
            m.setStyle(
                Style.Builder().fromUri(styleUrl)
                    .withSource(GeoJsonSource(TRAIL_SRC))
                    .withSource(GeoJsonSource(PEOPLE_SRC))
                    .withLayer(LineLayer("trail-line", TRAIL_SRC).withProperties(
                        lineColor("#E5484D"), lineWidth(4f),
                        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
                    .withLayer(CircleLayer("people-dot", PEOPLE_SRC).withProperties(
                        circleRadius(8f), circleColor("#2563EB"),
                        circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f))),
            ) { loaded -> style = loaded }
        }
        mapView
    })

    // Data changes update the GeoJSON sources in place. The view is never recreated.
    LaunchedEffect(style, trail, people) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(TRAIL_SRC)?.setGeoJson(
            if (trail.size < 2) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(
                LineString.fromLngLats(trail.map { Point.fromLngLat(it.longitude, it.latitude) }))))
        s.getSourceAs<GeoJsonSource>(PEOPLE_SRC)?.setGeoJson(FeatureCollection.fromFeatures(
            people.map { p -> Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).also { it.addStringProperty("id", p.id) } }))
        val pts = trail + people.map { LatLng(it.lat, it.lng) }
        when {
            pts.size >= 2 -> map?.easeCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.fromLatLngs(pts), 96), 600)
            pts.size == 1 -> map?.easeCamera(CameraUpdateFactory.newLatLngZoom(pts[0], 15.0), 600)
        }
    }
}

private fun createMapView(context: Context, textureMode: Boolean): MapView {
    MapLibre.getInstance(context.applicationContext) // lazy native init; NOT in Application.onCreate (see §4)
    val options = MapLibreMapOptions.createFromAttributes(context).textureMode(textureMode)
    return MapView(context, options).apply { onCreate(null) }
}
```
Imports used: `org.maplibre.android.{MapLibre, camera.CameraUpdateFactory, geometry.LatLng, geometry.LatLngBounds, maps.MapLibreMap, maps.MapLibreMapOptions, maps.MapView, maps.Style, style.layers.*, style.layers.PropertyFactory.*, style.sources.GeoJsonSource}` and `org.maplibre.geojson.{Feature, FeatureCollection, LineString, Point}`.
- Landmarks: add a `SymbolLayer` on a third GeoJSON source with `textField(get("name"))`. The Liberty style provides glyphs and sprites.
- Avoid the old annotation plugin (`android-plugin-annotation-v9` 3.0.2, last released in 2024). Use GeoJSON sources and layers as shown.
- `MapView.onSaveInstanceState(Bundle)` and `onLowMemory()` exist. Forward them if a screen needs to restore its camera position.
- *(unverified)* `org.maplibre.compose` (a declarative Compose wrapper) exists. SPEC asks for AndroidView, so it was not evaluated.

---

## 7. Google Fonts (Figtree, matching the design) — verified on device
1. Copy `font_certs.xml` into `app/src/main/res/values/`. Take it from [android/compose-samples Jetchat](https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/res/values-v23/font_certs.xml) (Apache-2.0); it defines `@array/com_google_android_gms_fonts_certs`.
2. Code:
```kotlin
val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)
val Figtree = FontFamily(
    Font(GoogleFont("Figtree"), provider, FontWeight.Normal),
    Font(GoogleFont("Figtree"), provider, FontWeight.Medium),
    Font(GoogleFont("Figtree"), provider, FontWeight.SemiBold),
    Font(GoogleFont("Figtree"), provider, FontWeight.Bold),
    Font(GoogleFont("Figtree"), provider, FontWeight.ExtraBold),
)
// Diagnostics: provider.isAvailableOnDevice(ctx) is an extension, so
// `import androidx.compose.ui.text.googlefonts.isAvailableOnDevice`.
```
On the CPH2717, `isAvailableOnDevice` returned true and `createFontFamilyResolver(ctx).preload(Figtree)` succeeded. No `<queries>` entry was needed. Downloadable fonts need Google Play services and a first download over the network; until then the system font is shown. That is acceptable, because the alert UI must never wait on a font.

---

## 8. Snippets for the remaining libraries

**Retrofit + kotlinx.serialization (verified):**
```kotlin
val AppJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl.trimEnd('/') + "/")             // BuildConfig.BASE_URL; paths like "v1/me"
    .client(client)
    .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType())) // retrofit2.converter.kotlinx.serialization
    .build()
```
Use an OkHttp `Authenticator` for the 401 → `/v1/auth/refresh` rotation. Use `HttpLoggingInterceptor` only in debug builds, at `Level.BASIC`, so tokens are never logged.

**Room (verified):** `@Database(entities=[…], version=1, exportSchema=true)`. The Room Gradle plugin writes `app/schemas/<db>/1.json`; commit it. `Flow` DAO queries and `suspend` inserts work.

**DataStore (verified):** `val Context.settings by preferencesDataStore("settings")`. Keep it top-level, one per process.

**Navigation 2.9.8:** type-safe routes use `@Serializable data object Home` / `@Serializable data class TripToPast(val incidentId: String)` with `composable<Home> { }`. These need the `kotlin-serialization` plugin, which is already applied. *(The dependency resolves and builds with this set, but no NavHost code was written in the scratch build.)*

**Fused location (play-services-location 21.4.0):**
```kotlin
val fused = LocationServices.getFusedLocationProviderClient(ctx)
// SPEC battery tiers:
fun walkingReq() = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000)
    .setMinUpdateDistanceMeters(20f).build()
fun vehicleReq() = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 7_500).build()
fun incidentReq() = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000).setMinUpdateIntervalMillis(1_000).build()
fun stillReq() = LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 60_000).build()
fused.requestLocationUpdates(walkingReq(), executor, listener)   // in SafetyService
val fix = fused.getCurrentLocation(CurrentLocationRequest.Builder()
    .setPriority(Priority.PRIORITY_HIGH_ACCURACY).build(), null).await() // kotlinx-coroutines-play-services (verified)
```
**Activity Recognition transitions** *(unverified here; based on platform rules)*: `ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pi)`. The PendingIntent must be **explicit** (`Intent(ctx, ActivityTransitionReceiver::class.java)`) and use `FLAG_UPDATE_CURRENT or FLAG_MUTABLE`, because Play services fills in the result extras. Android 14+ rejects mutable implicit PendingIntents. Parse the result with `ActivityTransitionResult.hasResult(intent)` / `extractResult(intent)`. Requires the `ACTIVITY_RECOGNITION` runtime permission (API 29+).

**Manifest entries implied by this stack** (FGS type and Play policy reasoning is in `research_notes/Android crash detection market/android_platform_policy.md`):
```xml
<uses-permission android:name="android.permission.INTERNET" />                 <!-- also merged from MapLibre -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />     <!-- also merged from MapLibre -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.SEND_SMS" />
<!-- Only if sampling > 200 Hz ("possible accident → full rate"): -->
<uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />
<uses-feature android:name="android.hardware.telephony" android:required="false" />
<!-- WorkManager auto-initializes via androidx.startup; for a custom Configuration, remove
     androidx.work.WorkManagerInitializer from InitializationProvider with tools:node="remove". -->
```
`ACTION_DIAL` needs no permission. Never declare `CALL_PHONE` (SPEC: never auto-dial).

---

## 9. Sources
- AGP 9.4 / 9.3 / 9.0 release notes, built-in Kotlin migration: developer.android.com/build/releases/agp-9-4-0-release-notes, …/agp-9-3-0-release-notes, …/agp-9-0-0-release-notes, developer.android.com/build/migrate-to-built-in-kotlin (fetched 2026-10-12)
- Kotlin/Gradle/AGP tested matrix: kotlinlang.org/docs/gradle-configure-project.html
- Version listings: dl.google.com/android/maven2 `maven-metadata.xml` + AAR `aar-metadata.properties`; repo1.maven.org/maven2; SDK repository `dl.google.com/android/repository/repository2-3.xml` (android-37.0/37.1/37.2 are on the stable channel)
- GitHub releases: JetBrains/kotlin v2.4.21, google/ksp 2.3.10–2.3.12, maplibre/maplibre-native android-v13.6.1 / v13.5.3 + platform/android/CHANGELOG.md, Kotlin/kotlinx.serialization v1.11.0, Kotlin/kotlinx.coroutines 1.11.0, robolectric 4.17, junit-framework r6.1.3, cashapp/turbine 1.2.1
- robolectric.org/getting-started (JDK 17+ `--add-opens`), developer.android.com/develop/ui/compose/text/fonts
