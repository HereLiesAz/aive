import haive.build.BrandAssets
import haive.build.BrandLoaderVerifier
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val brandSourceLogo = rootProject.layout.projectDirectory.file("branding/haive_logo.png")
val brandSourceAnimation = rootProject.layout.projectDirectory.file("branding/haive_splash.gif")
val generatedAndroidBrandResDir = layout.buildDirectory.dir("generated/brand/android/res")
val generateAndroidBrandAssets = tasks.register("generateAndroidBrandAssets") {
    inputs.files(brandSourceLogo, brandSourceAnimation)
    outputs.dir(generatedAndroidBrandResDir)
    doLast {
        val outputDir = generatedAndroidBrandResDir.get().asFile.resolve("drawable")
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
val generatedAndroidNodeJniDir = layout.buildDirectory.dir("generated/node-creatures/jniLibs")
val buildAndroidNodeCreatureNative = tasks.register<Exec>("buildAndroidNodeCreatureNative") {
    group = "build"
    description = "Builds the Rust node-creature renderer for Android ABIs."
    inputs.file(nodeCreatureManifest)
    inputs.dir(nodeCreatureSources)
    outputs.dir(generatedAndroidNodeJniDir)
    workingDir(nodeCreatureManifest.asFile.parentFile)

    doFirst {
        val outputDir = generatedAndroidNodeJniDir.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        commandLine(
            "cargo",
            "ndk",
            "-t", "arm64-v8a",
            "-t", "armeabi-v7a",
            "-t", "x86_64",
            "-o", outputDir.absolutePath,
            "build",
            "--release",
        )
    }
}

val bitcosManifest = rootProject.layout.projectDirectory.file("native/bitcos/Cargo.toml")
val bitcosSources = rootProject.layout.projectDirectory.dir("native/bitcos/src")
val generatedAndroidBitcosJniDir = layout.buildDirectory.dir("generated/bitcos/jniLibs")
val buildAndroidBitcosNative = tasks.register<Exec>("buildAndroidBitcosNative") {
    group = "build"
    description = "Builds the Rust BITCOS decoder for Android ABIs."
    inputs.file(bitcosManifest)
    inputs.dir(bitcosSources)
    outputs.dir(generatedAndroidBitcosJniDir)
    workingDir(bitcosManifest.asFile.parentFile)

    doFirst {
        val outputDir = generatedAndroidBitcosJniDir.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        commandLine(
            "cargo",
            "ndk",
            "-t", "arm64-v8a",
            "-t", "armeabi-v7a",
            "-t", "x86_64",
            "-o", outputDir.absolutePath,
            "build",
            "--release",
        )
    }
}

android {
    namespace = "com.hereliesaz.aive"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hereliesaz.aive"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("app.versionCode").get().toInt()
        versionName = providers.gradleProperty("app.versionName").get()
    }

    // AGP 9.4 no longer permits Provider instances through the legacy SourceSet API.
    // Resolve only deterministic build-directory paths here; task dependencies are declared below.
    sourceSets.getByName("main").apply {
        res.srcDir(generatedAndroidBrandResDir.get().asFile)
        jniLibs.srcDir(generatedAndroidNodeJniDir.get().asFile)
        jniLibs.srcDir(generatedAndroidBitcosJniDir.get().asFile)
    }

    signingConfigs {
        create("release") {
            val keyStorePath = System.getenv("HAIVE_KEYSTORE_PATH")
            if (!keyStorePath.isNullOrBlank()) {
                storeFile = file(keyStorePath)
                storeType = System.getenv("HAIVE_KEYSTORE_TYPE") ?: "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD").takeUnless { it.isNullOrBlank() }
                    ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            val keyStorePath = System.getenv("HAIVE_KEYSTORE_PATH")
            if (!keyStorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            // Google Play package identity. Package IDs are immutable once published.
            applicationId = "com.hereliesaz.aive"
        }
        create("github") {
            dimension = "distribution"
            // CRITICAL DATA-COMPATIBILITY INVARIANT:
            // GitHub releases existed as com.hereliesaz.haive before the product rename.
            // Keep this applicationId forever so Android performs an in-place update and preserves
            // SharedPreferences, Keystore entries, workflow state, settings, roles, and tokens.
            applicationId = "com.hereliesaz.haive"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") {
    dependsOn(generateAndroidBrandAssets)
}

// Building/testing shared Kotlin does not require Rust. The Rust cross-build is required exactly
// when AGP assembles native libraries into an Android package.
tasks.matching { task ->
    task.name.startsWith("merge") && task.name.endsWith("JniLibFolders")
}.configureEach {
    dependsOn(buildAndroidNodeCreatureNative, buildAndroidBitcosNative)
}

dependencies {
    implementation(projects.shared)
    implementation(projects.providers.jules)
    implementation(projects.providers.llm)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation("androidx.core:core:1.17.0")
    add("playImplementation", "com.google.android.play:app-update:2.1.0")
    implementation(compose.material3)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.onnxruntime.android)
    implementation(libs.commons.compress)
    implementation(libs.djl.huggingface.tokenizers)
    runtimeOnly(libs.djl.android.tokenizer.native)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.ktor.client.mock)
}
