plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())

    // Mobile and tablets are the reason this framework exists at all -- a desktop-only demo never
    // put a gesture, an Escort, or a felt refusal under an actual thumb. This target is what
    // conveyance-demo-android links against; nothing here is desktop-specific, so it costs nothing
    // to share.
    androidLibrary {
        namespace = "com.hereliesaz.conveyance.demo"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(project(":conveyance-compose"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
        }
        // The five composable-set libraries, for the style showcase alongside the photograph
        // gallery. Each is published via AGP's split-module KMP layout: a root artifact plus
        // separate "-android"/"-desktop" coordinates joined by Gradle Module Metadata
        // "available-at" redirects. Gradle resolves BOTH of a dependency's "available-at" targets
        // as real graph nodes any time this project configures more than one Kotlin target
        // (confirmed with `./gradlew :conveyance-demo:dependencyInsight --configuration
        // desktopCompileClasspath --dependency conveyance-h2g2`: the "-desktop" split module
        // resolves correctly with a fully attribute-matched variant, but the sibling "-android"
        // split module is *also* required directly by desktopCompileClasspath and fails, since it
        // correctly has no jvm-platform variant to offer) -- regardless of which source set
        // declares the dependency. Excluding each split coordinate that a given target will never
        // need stops Gradle from attempting to resolve it at all. See HereLiesAz/Conveyance#36.
        val fiveComposableSets = listOf(
            "conveyance-h2g2",
            "conveyance-expressive",
            "conveyance-liquid",
            "conveyance-bacterium",
            "conveyance-space",
        )
        fun org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler.addFiveComposableSets(unneededSplitSuffix: String) {
            fiveComposableSets.forEach { name ->
                implementation("com.github.HereLiesAz:$name:main-SNAPSHOT") {
                    // Transitively depends on this same repo's own conveyance-core/-compose
                    // artifacts via JitPack; excluded since `project(":conveyance-compose")` in
                    // commonMain (which itself depends on `project(":conveyance-core")`) already
                    // supplies those exact classes from this build -- without the exclude, both
                    // the JitPack jar and the local project would define the identical classes on
                    // the same classpath.
                    exclude(group = "com.github.HereLiesAz.Conveyance")
                    exclude(group = "com.github.HereLiesAz.$name", module = "$name-$unneededSplitSuffix")
                }
            }
            // Convey is a second, standalone design system built on this repo's own Manifesto --
            // unlike the five composable-set libraries above, it has no dependency on this repo's
            // conveyance-core/-compose, so it needs no exclude for that. It also isn't published
            // via the split-module layout the five composable-set libraries use (confirmed: its
            // dotted-group coordinate resolves through real Gradle Module Metadata straight to one
            // platform variant, with no separate "-android"/"-desktop" artifact ever appearing in
            // the graph), so it needs no unneededSplitSuffix exclude either.
            implementation("com.github.HereLiesAz.convey:convey:main-SNAPSHOT")
        }
        val androidMain by getting {
            dependencies { addFiveComposableSets(unneededSplitSuffix = "desktop") }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                addFiveComposableSets(unneededSplitSuffix = "android")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":conveyance-auditor"))
                // The auditor keeps Jackson internal, correctly; the harness serialises the bundle
                // itself and so needs its own.
                implementation(libs.jackson.kotlin)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.hereliesaz.conveyance.demo.MainKt"
    }
}

tasks.withType<Test>().configureEach {
    // Rendering happens off-screen; there is no display in CI and none is needed.
    systemProperty("java.awt.headless", "true")
    testLogging { events("passed", "failed", "skipped") }
    // RenderMotion holds every captured frame of a film in memory before writing it out --
    // several dozen full-resolution ARGB frames per test, several films per run. The default
    // heap is sized for ordinary unit tests and starts failing with OutOfMemoryError once enough
    // of them run in the same worker, which is a resource limit, not a defect in what the films
    // are testing.
    maxHeapSize = "2g"
}
