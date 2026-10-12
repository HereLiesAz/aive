pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "conveyance-h2g2"

val localConveyance = file("vendor/Conveyance")
if (localConveyance.exists()) {
    includeBuild(localConveyance) {
        dependencySubstitution {
            substitute(module("com.github.HereLiesAz.Conveyance:conveyance-core"))
                .using(project(":conveyance-core"))
            substitute(module("com.github.HereLiesAz.Conveyance:conveyance-compose"))
                .using(project(":conveyance-compose"))
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
