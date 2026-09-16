import haive.build.BrandAssets
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val brandSourceLogo = rootProject.layout.projectDirectory.file("branding/haive_logo.png")
val brandSourceAnimation = rootProject.layout.projectDirectory.file("branding/haive_splash.gif")
val generatedWebBrandDir = layout.buildDirectory.dir("generated/brand/web")
val generateWebBrandAssets = tasks.register("generateWebBrandAssets") {
    inputs.files(brandSourceLogo, brandSourceAnimation)
    outputs.dir(generatedWebBrandDir)
    doLast {
        val outputDir = generatedWebBrandDir.get().asFile
        BrandAssets.generateWeb(
            source = brandSourceLogo.asFile,
            outputDir = outputDir,
        )
        BrandAssets.generateLoader(
            logoSource = brandSourceLogo.asFile,
            animationSource = brandSourceAnimation.asFile,
            outputDir = outputDir,
        )
    }
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "haive.js"
            }
        }
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "haive.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(projects.providers.jules)
            implementation(projects.providers.llm)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }

        val webMain by creating {
            dependsOn(commonMain.get())
            resources.srcDir(generatedWebBrandDir.get().asFile)
        }
        jsMain.get().apply {
            dependsOn(webMain)
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
        wasmJsMain.get().apply {
            dependsOn(webMain)
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
    }
}

tasks.matching { task ->
    task.name.endsWith("ProcessResources") ||
        task.name.contains("BrowserProductionWebpack") ||
        task.name.contains("BrowserDevelopmentWebpack")
}.configureEach {
    dependsOn(generateWebBrandAssets)
}
