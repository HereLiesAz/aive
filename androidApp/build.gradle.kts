import aive.build.BrandAssets
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val brandSourceLogo = rootProject.layout.projectDirectory.file("branding/aive_logo.png")
val generatedAndroidBrandResDir = layout.buildDirectory.dir("generated/brand/android/res")
val generateAndroidBrandAssets = tasks.register("generateAndroidBrandAssets") {
    inputs.files(brandSourceLogo)
    outputs.dir(generatedAndroidBrandResDir)
    doLast {
        // Start clean so assets dropped from the pipeline never linger.
        generatedAndroidBrandResDir.get().asFile.deleteRecursively()
        val outputDir = generatedAndroidBrandResDir.get().asFile.resolve("drawable")
        BrandAssets.generateSplashLogo(
            logoSource = brandSourceLogo.asFile,
            outputDir = outputDir,
        )
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
    inputs.file(crate.file("Cargo.lock"))
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
            // Build exactly what Cargo.lock pins; fail rather than re-resolve dependencies.
            "--locked",
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

// Keystore file for release signing: AIVE_KEYSTORE_PATH, else the central workflow's KEYSTORE_FILE,
// else app/keystore.jks at the repository root. Null (unsigned release) when none exists.
fun releaseKeystorePath(): String? {
    val candidates = listOf(
        System.getenv("AIVE_KEYSTORE_PATH"),
        System.getenv("KEYSTORE_FILE"),
        rootProject.file("app/keystore.jks").path,
    )
    // Resolve every candidate the way storeFile does (project-relative; absolute paths unchanged)
    // so the existence check and the signing config always look at the same file.
    return candidates
        .filter { !it.isNullOrBlank() }
        .map { file(it!!) }
        .firstOrNull { it.isFile }
        ?.path
}

android {
    namespace = "com.hereliesaz.aive"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        // AGP 8+ defaults this off; the OAuth client ids below are generated string resources.
        resValues = true
    }

    defaultConfig {
        applicationId = "com.hereliesaz.aive"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Public OAuth app identifiers, never user secrets. Builds without registered apps
        // retain manual-token login and do not advertise a broken sign-in button.
        resValue("string", "aive_huggingface_oauth_client_id", providers.gradleProperty("aive.huggingfaceOAuthClientId").orElse("").get())
        resValue("string", "aive_pollinations_oauth_client_id", providers.gradleProperty("aive.pollinationsOAuthClientId").orElse("").get())
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

    // Release signing (GitHub APK in multiplatform.yml, Play bundle in the central android-play-release
    // run). multiplatform.yml's android-keystore action exports AIVE_KEYSTORE_PATH and
    // AIVE_KEYSTORE_TYPE; the central run writes app/keystore.jks and exports KEYSTORE_FILE. Both
    // supply KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD. Without a keystore (local builds, PRs) the
    // release build stays unsigned instead of failing.
    signingConfigs {
        create("release") {
            val keyStorePath = releaseKeystorePath()
            if (keyStorePath != null) {
                storeFile = file(keyStorePath)
                storeType = System.getenv("AIVE_KEYSTORE_TYPE")?.takeUnless { it.isBlank() }
                    ?: if (keyStorePath.endsWith(".jks", ignoreCase = true)) "JKS" else "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                // The android-keystore action rebuilds a PKCS12 keystore, and PKCS12 cannot hold a key
                // password different from the store password (keytool ignores -destkeypass), so for
                // that keystore the key is recovered with the store password.
                val rebuiltPkcs12 = System.getenv("AIVE_KEYSTORE_TYPE").equals("PKCS12", ignoreCase = true)
                keyPassword = if (rebuiltPkcs12) {
                    System.getenv("KEYSTORE_PASSWORD")
                } else {
                    System.getenv("KEY_PASSWORD").takeUnless { it.isNullOrBlank() }
                        ?: System.getenv("KEYSTORE_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystorePath() != null) {
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
            // The GitHub-release flavor shares the product application ID with Play. Installs made
            // under the previous (pre-rename) ID are a different package to Android and do not
            // upgrade in place; keepers must install this build fresh.
            applicationId = "com.hereliesaz.aive"
        }
    }

    packaging {
        // DJL's tokenizer jar bundles desktop natives (~55 MB); Android loads libdjl_tokenizer.so.
        resources.excludes += "native/lib/**"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
    implementation(libs.jgit)
    implementation(projects.providers.llm)
    implementation(libs.androidx.activity.compose)
    implementation(libs.play.billing.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.javascriptengine)
    implementation("androidx.core:core:1.19.1")
    add("playImplementation", "com.google.android.play:app-update:2.1.0")
    implementation(compose.material3)
    implementation(libs.ktor.client.cio)
    // The Store and model downloads use the platform TLS stack (OkHttp), not CIO's own.
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.onnxruntime.android)
    // LiteRT-LM small-task runtime; in both flavors, like ONNX Runtime.
    implementation(libs.litertlm.android)
    implementation(libs.commons.compress)
    implementation(libs.djl.huggingface.tokenizers)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.ktor.client.mock)
}
