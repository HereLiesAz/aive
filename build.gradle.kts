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
                substitute(module("com.github.HereLiesAz:conveyance-h2g2:8566a9db02d533d9534327df40424ee7b88ebe88"))
                    .using(module("com.hereliesaz.conveyance:conveyance-h2g2:0.1.0"))
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-core:b3e13674df9dfbcc0b35f800b57d78a305d07b03"))
                    .using(module("com.hereliesaz.conveyance:conveyance-core:0.1.0"))
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-compose:b3e13674df9dfbcc0b35f800b57d78a305d07b03"))
                    .using(module("com.hereliesaz.conveyance:conveyance-compose:0.1.0"))
            }
        }
    }
}
