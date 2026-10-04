import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidLibrary {
        namespace = "com.hereliesaz.geministrator.providers.llm"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        // On-device (instrumented) tests share the common test tree, so the opt-in live
        // acceptance test can run on an Android device or emulator.
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
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
            implementation(projects.shared)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.multiplatform.settings.no.arg)
            implementation(libs.multiplatform.settings.test)
        }

        named("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.test.runner)
            }
        }

        named("androidMain") {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }

        named("desktopMain") {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }

        named("jsMain") {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }

        named("wasmJsMain") {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
    }
}

// :shared brings in Compose, whose Skiko JS module imports its runtime (skiko.mjs, skiko.wasm) when
// the test bundle loads. The Compose plugin ships that runtime only for modules that apply it, so the
// JS and Wasm browser tests here copy it next to their bundles, at the Skiko version :shared resolves.
val skikoWasmRuntime = configurations.create("skikoWasmRuntime") {
    isCanBeConsumed = false
    isTransitive = false
}
skikoWasmRuntime.dependencies.addLater(
    configurations.named("jsTestRuntimeClasspath").map { classpath ->
        val skiko = classpath.incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .first { it.group == "org.jetbrains.skiko" && it.name.startsWith("skiko") }
        dependencies.create("org.jetbrains.skiko:skiko-js-wasm-runtime:${skiko.version}")
    },
)
val unpackSkikoRuntimeForBrowserTests = tasks.register<Sync>("unpackSkikoRuntimeForBrowserTests") {
    from(skikoWasmRuntime.elements.map { jars -> jars.map { zipTree(it.asFile) } })
    exclude("META-INF/**")
    into(layout.buildDirectory.dir("skiko-runtime-for-browser-tests"))
}
listOf("jsTestProcessResources", "wasmJsTestProcessResources").forEach { name ->
    tasks.named<ProcessResources>(name) { from(unpackSkikoRuntimeForBrowserTests) }
}

// The opt-in live acceptance test reads its settings from the environment, which Gradle does not
// track. A live run must really run: never reuse an up-to-date or cached result for it.
val liveRuntimeVerification = providers.environmentVariable("AIVE_LIVE_RUNTIME_VERIFICATION").orElse("")
tasks.withType<AbstractTestTask>().configureEach {
    inputs.property("aiveLiveRuntimeVerification", liveRuntimeVerification)
    outputs.upToDateWhen { liveRuntimeVerification.get() != "1" }
    outputs.cacheIf { liveRuntimeVerification.get() != "1" }
}
