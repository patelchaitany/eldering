// Declare every plugin once with `apply false` so all modules share one classloader.
// Declaring kotlin-jvm / kotlin-compose / kotlin-serialization at the catalog version here also lifts
// AGP 9's built-in Kotlin Gradle plugin (runtime dependency 2.2.10) to that version in every module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
