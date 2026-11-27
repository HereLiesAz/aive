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

val appVersionNameProvider = providers.gradleProperty("app.versionName").orElse("dev")

/** Writes AppVersion.kt (APP_VERSION_NAME) from app.versionName; the Keeper's room shows it. */
val generateAppVersion = tasks.register("generateAppVersion") {
    val outputDir = layout.buildDirectory.dir("generated/appVersion/kotlin")
    val versionName = appVersionNameProvider
    inputs.property("versionName", versionName)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("com/hereliesaz/geministrator")
        dir.mkdirs()
        dir.resolve("AppVersion.kt").writeText(
            "package com.hereliesaz.geministrator\n\ninternal const val APP_VERSION_NAME: String = \"" + versionName.get() + "\"\n",
        )
    }
}

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

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        // The Compose Resources plugin wires commonResClass/commonMainResourceCollectors into
        // commonMain automatically but not commonMainResourceAccessors, leaving every
        // Res.* reference unresolved. Wire it explicitly.
        getByName("commonMain").kotlin.srcDir(tasks.named("generateResourceAccessorsForCommonMain"))
        // The version the build was given (CI substitutes the BUILD part), so the Keeper's room can show it.
        getByName("commonMain").kotlin.srcDir(generateAppVersion)
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            // CI checks out the same release tag from the version catalog for composite builds.
            implementation(libs.conveyance.h2g2)
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

        // Both JVM targets use the same bounded archive reader; no platform-specific duplicate.
        getByName("androidMain").kotlin.srcDir("src/jvmArchiveMain/kotlin")
        getByName("desktopMain").kotlin.srcDir("src/jvmArchiveMain/kotlin")
        getByName("androidMain").dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.onnxruntime.android)
            implementation(libs.cryptography.provider.jdk.bc)
            // Overrides the provider's vulnerable transitive bcprov 1.83.
            implementation(libs.bouncycastle.bcprov)
            implementation(libs.sqldelight.android.driver)
            // Local Git on Android: imported repositories are read and changed through JGit.
            implementation(libs.jgit)
        }

        getByName("desktopMain").dependencies {
            // Native GL3 inside the Compose desktop field, with platform-native jars.
            implementation(libs.jogamp.jogl)
            implementation(libs.jogamp.gluegen)
            implementation(libs.sqldelight.sqlite.driver)
            // The MCP script sandbox (QuickJsScriptSandbox): QuickJS in WebAssembly on a pure-JVM runtime.
            implementation(libs.quickjs4j)
            if (isMacHost) {
                implementation(libs.onnxruntime)
            } else {
                implementation(libs.onnxruntime.gpu)
            }
        }

        getByName("wasmJsMain").dependencies {
            // ONNX Runtime Web; webApp copies its dist files next to the bundle (generateWebOrtRuntime).
            implementation(npm("onnxruntime-web", libs.versions.onnxruntime.web.get()))
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
        // Workflow state (projects, runs, events, artifacts, gates) on Android and desktop; see
        // docs/architecture/STORAGE_SCALE.md. Its own source directory keeps the two schemas apart.
        create("ProcedureDatabase") {
            packageName.set("com.hereliesaz.geministrator.memory.procedural.db")
            srcDirs.setFrom("src/commonMain/sqldelight-procedural")
            generateAsync.set(true)
        }
        create("WorkflowDatabase") {
            packageName.set("com.hereliesaz.geministrator.persistence.db")
            srcDirs.setFrom("src/commonMain/sqldelight-workflow")
            generateAsync.set(true)
        }
    }
}

// The app's own written docs (README.md and the top-level docs folder's .md files), copied at build time into a generated
// compose-resources root under files/aive-docs/ with an index.txt of their names (resources cannot be listed at run time), so
// the queen can read how the app works (retrieval/AiveDocsChunkSource.kt). There is no checked-in copy. The directory is wired
// through compose.resources.customDirectory with the task as its provider, so every resource task depends on it by itself.
val aiveDocsResourcesRoot = layout.buildDirectory.dir("generated/aiveDocs/composeResources")
val bundleAiveDocs = tasks.register("bundleAiveDocs") {
    val readme = rootProject.layout.projectDirectory.file("README.md").asFile
    val docsDir = rootProject.layout.projectDirectory.dir("docs").asFile
    val rootDir = aiveDocsResourcesRoot
    // customDirectory for commonMain replaces the default composeResources folder instead of adding to it, so this root
    // carries a copy of the checked-in resources too: without it no string or drawable is built (Unresolved reference 'Res.string...').
    val ownResources = layout.projectDirectory.dir("src/commonMain/composeResources").asFile
    inputs.dir(ownResources).withPropertyName("ownComposeResources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.fileTree("docs") { include("*.md") }).withPropertyName("aiveDocs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    if (readme.isFile) inputs.file(readme).withPropertyName("aiveReadme")
    outputs.dir(rootDir)
    doLast {
        val root = rootDir.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        if (ownResources.isDirectory) ownResources.copyRecursively(root, overwrite = true)
        val target = root.resolve("files/aive-docs")
        target.mkdirs()
        val names = mutableListOf<String>()
        if (readme.isFile) {
            readme.copyTo(target.resolve("README.md"), overwrite = true)
            names += "README.md"
        }
        val docs = docsDir.listFiles { f -> f.isFile && f.extension == "md" }.orEmpty().sortedBy { it.name }
        if (docs.isNotEmpty()) target.resolve("docs").mkdirs()
        docs.forEach { f ->
            val safe = f.name.replace(' ', '-')
            f.copyTo(target.resolve("docs/$safe"), overwrite = true)
            names += "docs/$safe"
        }
        target.resolve("index.txt").writeText(names.joinToString("\n") + "\n")
    }
}

compose.resources {
    packageOfResClass = "com.hereliesaz.geministrator.resources"
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = bundleAiveDocs.map { aiveDocsResourcesRoot.get() },
    )
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

// SuiteCountsInDocsTest reads the docs, so a docs edit must re-run the desktop tests instead of
// being served from the up-to-date check or the build cache.
tasks.matching { it.name == "desktopTest" }.configureEach {
    inputs.files(rootProject.fileTree("docs") { include("*.md") })
        .withPropertyName("suiteCountDocs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// The app-integration adapter corpus (tools/app_integration_training) is written by
// AppIntegrationDatasetWriterTest only when -Daive.appIntegration.outDir=<dir> is given to Gradle; the
// property is passed to the test JVM and the task then always runs. Normal runs never set it.
tasks.matching { it.name == "desktopTest" }.configureEach {
    val test = this as? org.gradle.api.tasks.testing.Test ?: return@configureEach
    val keys = listOf("aive.appIntegration.outDir", "aive.appIntegration.commit")
    var requested = false
    keys.forEach { key ->
        System.getProperty(key)?.let {
            test.systemProperty(key, it)
            if (key.endsWith("outDir")) requested = true
        }
    }
    if (requested) {
        test.outputs.upToDateWhen { false }
        test.outputs.cacheIf { false }
    }
}
