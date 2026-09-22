@file:Suppress("UnusedImport")
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val isMacHost = System.getProperty("os.name").lowercase().contains("mac")

kotlin {
    androidLibrary {
        namespace = "com.hereliesaz.geministrator.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        androidResources {
            enable = true
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation("com.github.HereLiesAz:conveyance-h2g2:8566a9db02d533d9534327df40424ee7b88ebe88")
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatform.settings.no.arg)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kmp.zip)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }

        getByName("androidMain").dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.onnxruntime.android)
            implementation(libs.cryptography.provider.jdk.bc)
        }

        getByName("desktopMain").dependencies {
            if (isMacHost) {
                implementation(libs.onnxruntime)
            } else {
                implementation(libs.onnxruntime.gpu)
            }
        }

        getByName("jsMain").dependencies {
            implementation(npm("onnxruntime-web", libs.versions.onnxruntime.get()))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.ktor.client.mock)
        }
    }
}


compose.resources {
    packageOfResClass = "com.hereliesaz.geministrator.resources"
}

// The compose-resource pipeline tasks must never be restored from build cache: stale entries
// cause "Unresolved reference" errors for drawables that exist on disk but were absent when
// the cached outputs were produced. All three pipeline stages are covered.
// whenTaskAdded fires synchronously for every added task (eager and lazy), ensuring the
// cacheIf { false } configuration is applied before any build-cache lookup occurs.
tasks.whenTaskAdded { task ->
    if (task.name.startsWith("copyNonXmlValueResources") ||
        task.name.startsWith("prepareComposeResourcesTask") ||
        task.name.startsWith("generateResourceAccessors")
    ) {
        task.outputs.cacheIf { false }
    }
}
