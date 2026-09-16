import haive.build.BrandAssets
import haive.build.BrandLoaderVerifier

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

android {
    namespace = "com.hereliesaz.haive"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hereliesaz.haive"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("app.versionCode").get().toInt()
        versionName = providers.gradleProperty("app.versionName").get()
    }

    // AGP 9.4 no longer permits Provider instances through the legacy SourceSet API.
    // Resolve only the deterministic build-directory path here; preBuild below carries the task dependency.
    sourceSets.getByName("main").res.srcDir(generatedAndroidBrandResDir.get().asFile)

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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") {
    dependsOn(generateAndroidBrandAssets)
}

dependencies {
    implementation(projects.shared)
    implementation(projects.providers.jules)
    implementation(projects.providers.llm)
    implementation(libs.androidx.activity.compose)
    implementation(compose.material3)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.onnxruntime.android)
    implementation(libs.commons.compress)
    implementation(libs.djl.huggingface.tokenizers)
    runtimeOnly(libs.djl.android.tokenizer.native)
    testImplementation(kotlin("test-junit"))
}
