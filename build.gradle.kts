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
    // Each library publishes to Maven Local under the version in its own version.properties.
    fun localVersion(dir: String): String = java.util.Properties().run {
        file("$dir/version.properties").inputStream().use { load(it) }
        listOf("versionMajor", "versionMinor", "versionPatch", "versionBuild")
            .joinToString(".") { getProperty(it).trim() }
    }
    val h2g2Local = localVersion("vendor/conveyance-h2g2")
    val conveyanceLocal = localVersion("vendor/conveyance-h2g2/vendor/Conveyance")
    // The Conveyance coordinate h2g2 requests (its build.gradle.kts: conveyanceRevision).
    val conveyanceRevision = "0.5.0"
    allprojects {
        configurations.configureEach {
            resolutionStrategy.dependencySubstitution {
                substitute(module("com.github.HereLiesAz:conveyance-h2g2:b17d8d9b46f8aeb399321c10199f0d0d0a0cfaaa"))
                    .using(module("com.hereliesaz.conveyance:conveyance-h2g2:$h2g2Local"))
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-core:$conveyanceRevision"))
                    .using(module("com.hereliesaz.conveyance:conveyance-core:$conveyanceLocal"))
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-compose:$conveyanceRevision"))
                    .using(module("com.hereliesaz.conveyance:conveyance-compose:$conveyanceLocal"))
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
