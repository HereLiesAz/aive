import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

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
            implementation("com.github.HereLiesAz:conveyance-h2g2:8debce4d58d9389a909cffe7d1339206830a2c4e")
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatform.settings.no.arg)
            implementation(libs.ktor.client.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.multiplatform.settings.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
