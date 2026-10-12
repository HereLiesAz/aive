import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    `maven-publish`
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())

    androidLibrary {
        namespace = "com.hereliesaz.conveyance.core"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }
    jvm("desktop")
    js { browser() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    compilerOptions {
        // The core carries no UI toolkit, no platform, and no runtime dependency of any kind.
        // If a production dependency ever appears below, the model has stopped being portable.
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        commonMain {
            // Preserve the existing source path while the formerly-JVM module becomes KMP.
            kotlin.srcDir("src/main/kotlin")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopTest by getting {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(libs.junit.platform.launcher)
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Conveyance Core")
            description.set(
                "The multiplatform type system: acts, consequences, gates, places and the rules " +
                    "that make an illegal interface state unrepresentable, with no UI toolkit dependency.",
            )
        }
    }
}
