plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}

// CI publishes the pinned checkouts with their native coordinates. Rewriting only Maven
// metadata leaves embedded Kotlin/JS package versions inconsistent with the resolved graph.
if (providers.gradleProperty("haive.useMavenLocalH2g2").orNull == "true") {
    allprojects {
        configurations.configureEach {
            resolutionStrategy.dependencySubstitution {
                substitute(module("com.github.HereLiesAz:conveyance-h2g2:5667da6fd07d856684048622ddf7722034f78b6b"))
                    .using(module("com.hereliesaz.conveyance:conveyance-h2g2:0.1.0"))
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-core:b3e13674df9dfbcc0b35f800b57d78a305d07b03"))
                    .using(module("com.hereliesaz.conveyance:conveyance-core:0.1.0"))
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-compose:b3e13674df9dfbcc0b35f800b57d78a305d07b03"))
                    .using(module("com.hereliesaz.conveyance:conveyance-compose:0.1.0"))
            }
        }
    }
}
