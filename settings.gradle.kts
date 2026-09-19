pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
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
include(":providers:jules")
include(":providers:llm")
include(":androidApp")
include(":desktopApp")
include(":webApp")

include(":computeRelay")
