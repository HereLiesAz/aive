import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    `maven-publish`
}

group = "com.hereliesaz.conveyance"
version = "0.1.0"

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())

    androidLibrary {
        namespace = "com.hereliesaz.conveyance.h2g2"
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
            // Pin the exact Conveyance revision this binding was built against. Floating
            // main-SNAPSHOT dependencies make KMP metadata non-reproducible and can resolve stale
            // target publications through JitPack.
            //
            // The previous pin (b3e13674df9dfbcc0b35f800b57d78a305d07b03) had no JitPack build
            // artifacts at all: every JitPack build of Conveyance failed while the aggregate build
            // also configured :conveyance-demo, which depends back on this repo's own JitPack
            // artifacts -- an unresolvable cycle. Conveyance's jitpack.yml now scopes the install
            // command to the publishable modules only, and 653122cd8e (main) is the first commit
            // JitPack has successfully built and published: verified by fetching
            // conveyance-core/conveyance-compose's POMs for this exact SHA (HTTP 200) and by
            // https://jitpack.io/api/builds/com.github.HereLiesAz/Conveyance/latest reporting
            // status "ok" for main-653122cd8e-1 with all core/compose target publications present.
            val conveyanceRevision = "653122cd8ec79a4b3ceadd44d99fe80f66c9905d"
            api("com.github.HereLiesAz.Conveyance:conveyance-core:$conveyanceRevision")
            api("com.github.HereLiesAz.Conveyance:conveyance-compose:$conveyanceRevision")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.ui)
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.12.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Templates.kt is composables, and the defect an audit found there (every Offer
            // missing `.tell(owesTell, weight).clickable { engage() }`, so every element rendered
            // but was inert) is only observable by actually composing one and clicking it. This is
            // the same harness conveyance-compose's own commonTest uses for its Offer tests.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// Kotlin Multiplatform registers one publication per target on its own; there is nothing to
// create here, only a shared description for whichever one a consumer ends up resolving.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Conveyance h2g2")
            description.set(
                "The h2g2 style system -- hues, ground-rotation surfaces, workflow routes, and the 8-step Jost type scale -- ported from HG2Gui.",
            )
        }
    }
}
