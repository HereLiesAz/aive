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
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}

// Kotlin/JS/Wasm cannot safely walk the nested composite Haive -> H2G2 -> Conveyance package graph.
// Isolated web builds therefore publish the exact checked-in/pinned source revisions to Maven Local
// and substitute only those exact Git coordinates. Android/Desktop continue compiling source directly.
val isolatedWebBuild = gradle.startParameter.taskNames.any { task ->
    task.startsWith(":webApp:") && (
        task.contains("jsBrowser", ignoreCase = true) ||
            task.contains("wasmJsBrowser", ignoreCase = true)
        )
}
val useMavenLocalH2g2 = providers.gradleProperty("haive.useMavenLocalH2g2")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: isolatedWebBuild

if (useMavenLocalH2g2) {
    allprojects {
        configurations.configureEach {
            resolutionStrategy.dependencySubstitution {
                substitute(module("com.github.HereLiesAz:conveyance-h2g2:060231873e51d64082c43208a585a6ebb4a71ed5"))
                    .using(module("com.hereliesaz.conveyance:conveyance-h2g2:0.1.0"))
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-core:653122cd8ec79a4b3ceadd44d99fe80f66c9905d"))
                    .using(module("com.hereliesaz.conveyance:conveyance-core:0.1.0"))
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-compose:653122cd8ec79a4b3ceadd44d99fe80f66c9905d"))
                    .using(module("com.hereliesaz.conveyance:conveyance-compose:0.1.0"))
            }
        }
    }
}

allprojects {
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
