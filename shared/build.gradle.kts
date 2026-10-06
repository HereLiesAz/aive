@file:Suppress("UnusedImport")
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

val isMacHost = System.getProperty("os.name").lowercase().contains("mac")

/** Browser memory database worker (SQLite in OPFS); see memory-worker/memory.worker.js. */
val memoryWorkerPackage: File = layout.projectDirectory.dir("memory-worker").asFile

kotlin {
    androidLibrary {
        namespace = "com.hereliesaz.geministrator.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        androidResources {
            enable = true
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
        // The Compose Resources plugin wires commonResClass/commonMainResourceCollectors into
        // commonMain automatically but not commonMainResourceAccessors, leaving every
        // Res.drawable.* reference unresolved. Wire it explicitly.
        getByName("commonMain").kotlin.srcDir(tasks.named("generateResourceAccessorsForCommonMain"))
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation("com.github.HereLiesAz:conveyance-h2g2:060231873e51d64082c43208a585a6ebb4a71ed5")
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatform.settings.no.arg)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kmp.zip)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.async.extensions)
        }

        getByName("androidMain").dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.onnxruntime.android)
            implementation(libs.cryptography.provider.jdk.bc)
            // Overrides the provider's vulnerable transitive bcprov 1.83.
            implementation(libs.bouncycastle.bcprov)
            implementation(libs.sqldelight.android.driver)
        }

        getByName("desktopMain").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            if (isMacHost) {
                implementation(libs.onnxruntime)
            } else {
                implementation(libs.onnxruntime.gpu)
            }
        }

        getByName("jsMain").dependencies {
            implementation(npm("onnxruntime-web", libs.versions.onnxruntime.get()))
            implementation(libs.sqldelight.web.worker.driver)
            implementation(npm("aive-memory-worker", memoryWorkerPackage))
        }

        getByName("wasmJsMain").dependencies {
            implementation(libs.sqldelight.web.worker.driver)
            implementation(npm("aive-memory-worker", memoryWorkerPackage))
        }

        getByName("desktopTest").dependencies {
            // Native Skia for headless ImageComposeScene renders (CreatureRigRenderTest).
            implementation(compose.desktop.currentOs)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.ktor.client.mock)
        }
    }
}


sqldelight {
    databases {
        create("MemoryDatabase") {
            packageName.set("com.hereliesaz.geministrator.memory.db")
            // The browser driver is asynchronous; generating suspend queries keeps one store for all targets.
            generateAsync.set(true)
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
tasks.whenTaskAdded(object : Action<Task> {
    override fun execute(task: Task) {
        if (task.name.startsWith("copyNonXmlValueResources") ||
            task.name.startsWith("prepareComposeResourcesTask") ||
            task.name.startsWith("generateResourceAccessors")
        ) {
            task.outputs.cacheIf { false }
        }
    }
})
