pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// The settings classpath (plugin resolution) takes the same Guava floor as the build: see build.gradle.kts.
buildscript {
    configurations.configureEach {
        // CVE-2026-102554 (GHSA-xxph-c9ww-hj94): Guava before 33.7.2 over-allocates when deserializing.
        // Guava only arrives transitively; lift every older request to the fixed release, keeping its flavour.
        resolutionStrategy.eachDependency {
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                val version = requested.version.orEmpty()
                val (major, minor, patch) = (Regex("""^(\d+)\.(\d+)(?:\.(\d+))?""").find(version)?.destructured
                    ?: return@eachDependency)
                val old = major.toInt() < 33 || (major.toInt() == 33 && (minor.toInt() < 7 || (minor.toInt() == 7 && patch.ifEmpty { "0" }.toInt() < 2)))
                if (old) {
                    useVersion(if (version.endsWith("-android")) "33.7.2-android" else "33.7.2-jre")
                    because("CVE-2026-102554")
                }
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Kotlin/JS/Wasm package tasks cannot safely traverse the nested composite
// Aive -> H2G2 -> Conveyance. Production web tasks therefore resolve the exact Maven-local
// publications prepared by CI. Android/Desktop and ordinary development keep compiling H2G2 source.
val isolatedWebBuild = gradle.startParameter.taskNames.any { task ->
    task.startsWith(":webApp:") && (
        task.contains("jsBrowser", ignoreCase = true) ||
            task.contains("wasmJsBrowser", ignoreCase = true)
        )
}
val useLocalH2g2 = providers.gradleProperty("haive.useLocalH2g2")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: !isolatedWebBuild
val useMavenLocalH2g2 = providers.gradleProperty("haive.useMavenLocalH2g2")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: isolatedWebBuild
val localH2g2 = file("vendor/conveyance-h2g2")
if (useLocalH2g2 && localH2g2.exists()) {
    includeBuild(localH2g2) {
        dependencySubstitution {
            substitute(module("com.github.HereLiesAz:conveyance-h2g2"))
                .using(project(":"))
        }
    }
}

dependencyResolutionManagement {
    repositories {
        if (useMavenLocalH2g2) mavenLocal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "aive"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":shared")
include(":providers:llm")
include(":androidApp")
include(":desktopApp")
include(":webApp")

include(":computeRelay")
