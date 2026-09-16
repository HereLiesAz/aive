import haive.build.BrandAssets

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
        BrandAssets.generateLoader(
            logoSource = brandSourceLogo.asFile,
            animationSource = brandSourceAnimation.asFile,
            outputDir = generatedAndroidBrandResDir.get().asFile.resolve("drawable"),
        )
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

    sourceSets.getByName("main").res.srcDir(generatedAndroidBrandResDir)

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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.onnxruntime.android)
    implementation(libs.commons.compress)
    implementation(libs.djl.huggingface.tokenizers)
    runtimeOnly(libs.djl.android.tokenizer.native)
    testImplementation(kotlin("test-junit"))
}
