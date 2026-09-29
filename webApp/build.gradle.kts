import haive.build.BrandAssets
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val brandSourceLogo = rootProject.layout.projectDirectory.file("branding/haive_logo.png")
val generatedWebBrandDir = layout.buildDirectory.dir("generated/brand/web")
val generateWebBrandAssets = tasks.register("generateWebBrandAssets") {
    inputs.files(brandSourceLogo)
    outputs.dir(generatedWebBrandDir)
    doLast {
        // Start clean so assets dropped from the pipeline never linger.
        generatedWebBrandDir.get().asFile.deleteRecursively()
        val outputDir = generatedWebBrandDir.get().asFile
        BrandAssets.generateWeb(
            source = brandSourceLogo.asFile,
            outputDir = outputDir,
        )
        BrandAssets.generateSplashLogo(
            logoSource = brandSourceLogo.asFile,
            outputDir = outputDir,
            maxDimension = 256,
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

    if (name.contains("BrowserProductionWebpack") || name.contains("BrowserDevelopmentWebpack")) {
        doLast {
            val targetDirectory = when {
                name.startsWith("wasmJs") -> "wasmJs"
                name.startsWith("js") -> "js"
                else -> error("Unsupported web target: $name")
            }
            val executableDirectory = if (name.contains("Production")) {
                "productionExecutable"
            } else {
                "developmentExecutable"
            }
            val bundle = layout.buildDirectory.asFileTree.matching {
                include("**/$targetDirectory/$executableDirectory/haive.js")
            }.files.firstOrNull()
                ?: error("Could not locate $targetDirectory $executableDirectory web bundle after $name")

            copy {
                from(generatedWebBrandDir)
                into(bundle.parentFile)
            }

        }
    }
}

// The webpack tasks above copy the brand assets next to the bundle (GitHub Pages stages from there);
// the distribution also receives them from resources. The copies are identical.
tasks.matching { it.name.endsWith("BrowserDistribution") }.configureEach {
    (this as? AbstractCopyTask)?.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
