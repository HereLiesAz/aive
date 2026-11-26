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

// Dependency security floors (Guava, Jackson, BouncyCastle, ...) for every project: one script.
apply(from = "gradle/security-floors.settings.gradle.kts")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // The two upstream libraries share a group, but are published to distinct repositories.
        // Include variant modules as well as the KMP root metadata.
        exclusiveContent {
            forRepository {
                maven("https://maven.pkg.github.com/HereLiesAz/Conveyance") {
                    credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR").orNull
                        password = providers.environmentVariable("GH_TOKEN")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                }
            }
            filter { includeModuleByRegex("com\\.hereliesaz\\.conveyance", "conveyance-(core|compose)(-.*)?") }
        }
        exclusiveContent {
            forRepository {
                maven("https://maven.pkg.github.com/HereLiesAz/conveyance-h2g2") {
                    credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR").orNull
                        password = providers.environmentVariable("GH_TOKEN")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                }
            }
            filter { includeModuleByRegex("com\\.hereliesaz\\.conveyance", "conveyance-h2g2(-.*)?") }
        }
        // Scoped: JitPack only serves this account's own artifacts, so every other coordinate
        // is never requested from it (an unscoped JitPack made unrelated resolutions fail on its 504s).
        maven("https://jitpack.io") {
            content { includeGroupAndSubgroups("com.github.HereLiesAz") }
        }
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
include(":mcpBridge")
include(":cliApp")
