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

// The declared H2G2 revision is authoritative. A vendored checkout is only substituted when a
// developer or CI step opts in explicitly; merely having vendor/conveyance-h2g2 present must not
// silently replace the pinned dependency with stale source.
val useLocalH2g2 = providers.gradleProperty("haive.useLocalH2g2")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: false
val useMavenLocalH2g2 = providers.gradleProperty("haive.useMavenLocalH2g2")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: false
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

rootProject.name = "haive"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":shared")
include(":providers:jules")
include(":providers:llm")
include(":androidApp")
include(":desktopApp")
include(":webApp")
