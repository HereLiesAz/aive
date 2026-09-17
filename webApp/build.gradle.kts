import haive.build.BrandAssets
import haive.build.BrandLoaderVerifier
import org.gradle.api.tasks.Exec
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
        BrandLoaderVerifier.verify(outputDir)
    }
}

val nodeCreatureManifest = rootProject.layout.projectDirectory.file("native/node-creatures/Cargo.toml")
val nodeCreatureSources = rootProject.layout.projectDirectory.dir("native/node-creatures/src")
val installNodeCreatureWasmTarget = tasks.register<Exec>("installNodeCreatureWasmTarget") {
    group = "build"
    description = "Ensures the Rust wasm32 target used by the node-creature renderer is installed."
    commandLine("rustup", "target", "add", "wasm32-unknown-unknown")
}
val buildWebNodeCreatureWasm = tasks.register<Exec>("buildWebNodeCreatureWasm") {
    group = "build"
    description = "Builds the Rust node-creature renderer as raw WebAssembly."
    dependsOn(installNodeCreatureWasmTarget)
    inputs.file(nodeCreatureManifest)
    inputs.dir(nodeCreatureSources)
    outputs.file(generatedWebBrandDir.map { it.file("haive_node_creatures.wasm") })
    commandLine(
        "cargo",
        "build",
        "--release",
        "--target", "wasm32-unknown-unknown",
        "--manifest-path", nodeCreatureManifest.asFile.absolutePath,
    )
    doLast {
        val source = rootProject.layout.projectDirectory
            .file("native/node-creatures/target/wasm32-unknown-unknown/release/haive_node_creatures.wasm")
            .asFile
        check(source.isFile) { "Rust node-creature WASM was not produced at ${source.absolutePath}" }
        val destination = generatedWebBrandDir.get().file("haive_node_creatures.wasm").asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
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
    dependsOn(generateWebBrandAssets, buildWebNodeCreatureWasm)
}
