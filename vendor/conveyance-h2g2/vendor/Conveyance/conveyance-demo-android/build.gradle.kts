// A plain Android application, deliberately not a Kotlin Multiplatform module -- AGP 9 does not
// allow com.android.application and the KMP library plugin in the same module, and this module's
// only job is to be the launcher: an Activity, a manifest, and a dependency on conveyance-demo's
// shared commonMain, where Gallery actually lives.
// AGP 9's application plugin carries its own built-in Kotlin support; applying
// org.jetbrains.kotlin.android alongside it is now a hard error rather than a no-op.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

android {
    namespace = "com.hereliesaz.conveyance.demo.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hereliesaz.conveyance.demo.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    implementation(project(":conveyance-demo"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
}
