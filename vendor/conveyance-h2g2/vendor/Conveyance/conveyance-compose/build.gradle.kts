import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    `maven-publish`
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())

    androidLibrary {
        namespace = "com.hereliesaz.conveyance.compose"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }
    jvm("desktop")
    js {
        browser()
        binaries.executable()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":conveyance-core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        // material3 is present for its Expressive geometry -- MaterialShapes and Morph -- and for
        // nothing else. Conveyance assigns its own channel meanings and cannot import M3's.
        androidMain.dependencies {
            implementation(libs.androidx.material3)
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed", "skipped") }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Conveyance Compose")
            description.set(
                "The multiplatform Compose binding: the Escort, the Migration, the Ghost, Enter " +
                    "and Return, rendered as motion the framework draws rather than the application.",
            )
        }
    }
}
