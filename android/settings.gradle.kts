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

// SPEC: Gradle/AGP must run on JDK 21 (the machine default, JDK 25, is not supported by this toolchain).
// Fail fast with an actionable message instead of an obscure plugin error later.
val gradleJvm: JavaVersion = JavaVersion.current()
check(gradleJvm >= JavaVersion.VERSION_17 && gradleJvm <= JavaVersion.VERSION_21) {
    "Gradle is running on JDK $gradleJvm. Use JDK 21, e.g. " +
        "export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
}

rootProject.name = "eldering"
include(":app", ":detection")
