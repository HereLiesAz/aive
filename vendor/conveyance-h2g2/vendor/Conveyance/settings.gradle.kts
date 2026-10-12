pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    // `jvmToolchain(21)` (see libs.versions.toml) needs a real JDK 21 to compile against. Without
    // this, Gradle only ever looks at whatever JDKs happen to already be installed on the machine
    // running the build -- fine on a dev machine with one set up, but JitPack's build image ships
    // JDK 8-13 and nothing newer, and has no toolchain download repository configured on its own.
    // This plugin is exactly that: it lets Gradle fetch a matching JDK itself when none of the
    // locally installed ones satisfy the toolchain request, the same way it already does for the
    // Gradle wrapper's own distribution.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "conveyance"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        // The five composable-set libraries (conveyance-h2g2/-expressive/-liquid/-bacterium/-space)
        // are separate repos with no tagged release yet, resolved against `main` here the same way
        // they themselves resolve this repo's own artifacts.
        maven("https://jitpack.io")
    }
}

include(":conveyance-core")
include(":conveyance-compose")
include(":conveyance-demo")
include(":conveyance-demo-android")
include(":conveyance-auditor")
