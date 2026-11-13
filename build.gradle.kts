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
    // The Conveyance release h2g2 requests: its gradle/libs.versions.toml (`conveyance`), which Dependabot bumps
    // there. Read from the checkout so the two never drift; "0.5.0" is what h2g2 asked for before it had the entry.
    val conveyanceRevision = file("vendor/conveyance-h2g2/gradle/libs.versions.toml")
        .takeIf { it.isFile }
        ?.readLines()
        ?.firstNotNullOfOrNull { line -> Regex("^conveyance\\s*=\\s*\"([^\"]+)\"").find(line.trim())?.groupValues?.get(1) }
        ?: "0.5.0"
    allprojects {
        configurations.configureEach {
            resolutionStrategy.dependencySubstitution {
                substitute(module("com.github.HereLiesAz:conveyance-h2g2:main-SNAPSHOT"))
                    .using(module("com.hereliesaz.conveyance:conveyance-h2g2:$h2g2Local"))
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-core:$conveyanceRevision"))
                    .using(module("com.hereliesaz.conveyance:conveyance-core:$conveyanceLocal"))
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-compose:$conveyanceRevision"))
                    .using(module("com.hereliesaz.conveyance:conveyance-compose:$conveyanceLocal"))
            }
        }
    }
}

