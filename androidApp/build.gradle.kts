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

/** Registers a `cargo ndk` build of the Rust crate in [crateDir] for the app's Android ABIs. */
fun registerAndroidRustLibrary(
    name: String,
    description: String,
    crateDir: String,
    outputDir: Provider<Directory>,
) = tasks.register<Exec>(name) {
    group = "build"
    this.description = description
    val crate = rootProject.layout.projectDirectory.dir(crateDir)
    inputs.file(crate.file("Cargo.toml"))
    inputs.dir(crate.dir("src"))
    outputs.dir(outputDir)
    workingDir(crate.asFile)

    doFirst {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        // Google Play requires 16 KB ELF segment alignment for Android 15+ devices.
        environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
        commandLine(
            "cargo",
            "ndk",
            "-t", "arm64-v8a",
            "-t", "armeabi-v7a",
            "-t", "x86_64",
            "-o", output.absolutePath,
            "build",
            "--release",
        )
    }
}

val generatedAndroidBitcosJniDir = layout.buildDirectory.dir("generated/bitcos/jniLibs")
val buildAndroidBitcosNative = registerAndroidRustLibrary(
    name = "buildAndroidBitcosNative",
    description = "Builds the Rust BITCOS decoder for Android ABIs.",
    crateDir = "native/bitcos",
    outputDir = generatedAndroidBitcosJniDir,
)

// DJL's prebuilt tokenizer-native AAR is 4 KB-aligned; build the same JNI library from source.
val generatedAndroidDjlTokenizerJniDir = layout.buildDirectory.dir("generated/djl-tokenizer/jniLibs")
val buildAndroidDjlTokenizerNative = registerAndroidRustLibrary(
    name = "buildAndroidDjlTokenizerNative",
    description = "Builds DJL's HuggingFace tokenizer JNI library for Android ABIs.",
    crateDir = "native/djl-tokenizer",
    outputDir = generatedAndroidDjlTokenizerJniDir,
)

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
        jniLibs.srcDir(generatedAndroidBitcosJniDir.get().asFile)
        jniLibs.srcDir(generatedAndroidDjlTokenizerJniDir.get().asFile)
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
            // R8 shrinking; the resulting mapping.txt is uploaded to Play with each bundle.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    packaging {
        // DJL's tokenizer jar bundles desktop natives (~55 MB); Android loads libdjl_tokenizer.so.
        resources.excludes += "native/lib/**"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") {
    dependsOn(generateAndroidBrandAssets)
}

// BITCOS is the only Android native runtime. Mascots are pure Compose 2D puppets.
tasks.matching { task ->
    task.name.startsWith("merge") && task.name.endsWith("JniLibFolders")
}.configureEach {
    dependsOn(buildAndroidBitcosNative, buildAndroidDjlTokenizerNative)
}

dependencies {
    implementation(projects.shared)
    implementation(projects.providers.jules)
    implementation(projects.providers.llm)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.javascriptengine)
    implementation("androidx.core:core:1.17.0")
    add("playImplementation", "com.google.android.play:app-update:2.1.0")
    implementation(compose.material3)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.onnxruntime.android)
    implementation(libs.commons.compress)
    implementation(libs.djl.huggingface.tokenizers)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.ktor.client.mock)
}
