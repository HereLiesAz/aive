plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}

// Isolated web/desktop CI publishes Conveyance to Maven Local because Kotlin/JS metadata must resolve
// with its native coordinates. H2G2 itself remains on the exact revision declared by :shared; an
// arbitrary local H2G2 publication must never replace that revision.
if (providers.gradleProperty("haive.useMavenLocalH2g2").orNull == "true") {
    allprojects {
        configurations.configureEach {
            resolutionStrategy.dependencySubstitution {
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-core:b3e13674df9dfbcc0b35f800b57d78a305d07b03"))
                    .using(module("com.hereliesaz.conveyance:conveyance-core:0.1.0"))
                substitute(module("com.github.HereLiesAz.Conveyance:conveyance-compose:b3e13674df9dfbcc0b35f800b57d78a305d07b03"))
                    .using(module("com.hereliesaz.conveyance:conveyance-compose:0.1.0"))
            }
        }
    }
}
