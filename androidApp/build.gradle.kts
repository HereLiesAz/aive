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

// The hidden Linux runtime that runs the vendors' CLIs on a phone (docs/Local-CLI-Agents.md, "On a phone") needs proot, which
// Android only lets an app execute from the APK's native libraries. They are Termux's own proot packages, fetched here, checked
// against the SHA-256 pins below (a mismatch fails the build), unpacked with the host's `ar` and `tar`, and put in jniLibs/arm64-v8a.
// Nothing is committed to the repository. If the host is offline or lacks `ar`/`tar`, the task warns and the build has no runtime:
// the app then says "The Linux runtime is not available in this build". The loader's name is given to proot by PROOT_LOADER; libtalloc's
// NEEDED entry (`libtalloc.so.2`, which cannot be an APK library name) is rewritten in place to `libtalloc.so`.
// proot is GPL-2.0-or-later and libtalloc LGPL-3.0-or-later; see THIRD_PARTY_NOTICES.md.
val generatedAndroidProotJniDir = layout.buildDirectory.dir("generated/proot/jniLibs")
val fetchAndroidProotRuntime = tasks.register("fetchAndroidProotRuntime") {
    group = "build"
    description = "Fetches the pinned Termux proot packages into jniLibs/arm64-v8a."
    outputs.dir(generatedAndroidProotJniDir)
    doLast {
        data class Pin(val url: String, val sha256: String, val files: Map<String, String>)
        val base = "https://packages.termux.dev/apt/termux-main/pool/main"
        val termux = "data/data/com.termux/files/usr"
        val pins = listOf(
            Pin(
                "$base/p/proot/proot_5.1.107.102_aarch64.deb", "10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030",
                mapOf("$termux/bin/proot" to "libproot.so", "$termux/libexec/proot/loader" to "libproot-loader.so"),
            ),
            Pin(
                "$base/libt/libtalloc/libtalloc_2.5.0_aarch64.deb", "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7",
                mapOf("$termux/lib/libtalloc.so.2.5.0" to "libtalloc.so"),
            ),
            Pin(
                "$base/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb", "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6",
                mapOf("$termux/lib/libandroid-shmem.so" to "libandroid-shmem.so"),
            ),
        )
        val out = generatedAndroidProotJniDir.get().asFile.resolve("arm64-v8a")
        out.deleteRecursively()
        val work = temporaryDir
        work.deleteRecursively()
        work.mkdirs()
        try {
            pins.forEachIndexed { index, pin ->
                val deb = work.resolve("pin$index.deb")
                java.net.URI(pin.url).toURL().openStream().use { input -> deb.outputStream().use { input.copyTo(it) } }
                val actual = java.security.MessageDigest.getInstance("SHA-256").digest(deb.readBytes()).joinToString("") { "%02x".format(it) }
                if (actual != pin.sha256) throw GradleException("${pin.url} has SHA-256 $actual, not the pinned ${pin.sha256}")
                val unpacked = work.resolve("unpacked$index")
                unpacked.mkdirs()
                val process = ProcessBuilder("sh", "-c", "ar p \"\$0\" data.tar.xz | tar -xJ -C \"\$1\"", deb.absolutePath, unpacked.absolutePath)
                    .redirectErrorStream(true).start()
                val log = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) throw java.io.IOException("ar/tar failed: $log")
                out.mkdirs()
                pin.files.forEach { (from, to) ->
                    var bytes = unpacked.resolve(from).readBytes()
                    if (to == "libproot.so") {
                        // The NEEDED entry for talloc, in the same number of bytes: "libtalloc.so.2\0" becomes "libtalloc.so\0\0\0".
                        val needle = "libtalloc.so.2\u0000".toByteArray(Charsets.ISO_8859_1)
                        val patched = "libtalloc.so\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
                        var at = -1
                        for (i in 0..bytes.size - needle.size) {
                            if (needle.indices.all { bytes[i + it] == needle[it] }) { at = i; break }
                        }
                        if (at < 0) throw GradleException("libproot.so no longer names libtalloc.so.2; update the patch in fetchAndroidProotRuntime")
                        bytes = bytes.copyOf().also { patched.copyInto(it, at) }
                    }
                    out.resolve(to).writeBytes(bytes)
                    out.resolve(to).setExecutable(true)
                }
            }
        } catch (failure: java.io.IOException) {
            out.deleteRecursively()
            logger.warn("The Linux runtime for CLI agents is not in this build: ${failure.message}")
        }
    }
}

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
        jniLibs.srcDir(generatedAndroidProotJniDir.get().asFile)
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
        // The CLI runtime executes proot from the app's native library folder, so the libraries must be extracted there.
        jniLibs.useLegacyPackaging = true
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
    dependsOn(buildAndroidBitcosNative, buildAndroidDjlTokenizerNative, fetchAndroidProotRuntime)
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
