import aive.build.BrandAssets
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val brandSourceLogo = rootProject.layout.projectDirectory.file("branding/aive_logo.png")
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

// ONNX Runtime Web is not bundled by webpack: its WebGPU build and the jsep .mjs/.wasm pair are copied
// from the npm package into ort/ and loaded by aive-ort.js with a dynamic import. The package comes
// from :shared's npm dependency, installed under the root project's build/wasm/node_modules.
val generatedOrtWebDir = layout.buildDirectory.dir("generated/ort/web")
val ortWebDistDir = rootProject.layout.buildDirectory.dir("wasm/node_modules/onnxruntime-web/dist")
val ortWebFiles = listOf(
    "ort.webgpu.min.mjs",
    "ort-wasm-simd-threaded.jsep.mjs",
    "ort-wasm-simd-threaded.jsep.wasm",
)
val generateWebOrtRuntime = tasks.register("generateWebOrtRuntime") {
    inputs.property("onnxruntimeWebVersion", libs.versions.onnxruntime.web.get())
    outputs.dir(generatedOrtWebDir)
    dependsOn(rootProject.tasks.matching { it.name == "kotlinWasmNpmInstall" })
    doLast {
        val distDirectory = ortWebDistDir.get().asFile
        val outputDirectory = generatedOrtWebDir.get().asFile.resolve("ort")
        outputDirectory.deleteRecursively()
        outputDirectory.mkdirs()
        if (distDirectory.isDirectory) {
            ortWebFiles.forEach { fileName ->
                distDirectory.resolve(fileName).copyTo(outputDirectory.resolve(fileName), overwrite = true)
            }
        } else {
            logger.warn("onnxruntime-web was not found at $distDirectory; the web app will ship without the ONNX runtime.")
        }
    }
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "aive.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(projects.providers.llm)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }

        val webMain by creating {
            dependsOn(commonMain.get())
            resources.srcDir(generatedWebBrandDir.get().asFile)
            resources.srcDir(generatedOrtWebDir.get().asFile)
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
    dependsOn(generateWebBrandAssets, generateWebOrtRuntime)

    if (name.contains("BrowserProductionWebpack") || name.contains("BrowserDevelopmentWebpack")) {
        doLast {
            val targetDirectory = when {
                name.startsWith("wasmJs") -> "wasmJs"
                else -> error("Unsupported web target: $name")
            }
            val executableDirectory = if (name.contains("Production")) {
                "productionExecutable"
            } else {
                "developmentExecutable"
            }
            val bundle = layout.buildDirectory.asFileTree.matching {
                include("**/$targetDirectory/$executableDirectory/aive.js")
            }.files.firstOrNull()
                ?: error("Could not locate $targetDirectory $executableDirectory web bundle after $name")

            copy {
                from(generatedWebBrandDir)
                from(generatedOrtWebDir)
                into(bundle.parentFile)
            }

        }
    }
}

// The webpack tasks above copy brand assets next to the JS/Wasm bundles for CI builds;
// the distribution also receives them from resources. The copies are identical.
tasks.matching { it.name.endsWith("BrowserDistribution") }.configureEach {
    (this as? AbstractCopyTask)?.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
