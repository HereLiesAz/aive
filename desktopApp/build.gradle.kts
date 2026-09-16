import haive.build.BrandAssets
import haive.build.BrandLoaderVerifier
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(projects.providers.jules)
    implementation(projects.providers.llm)
    implementation(compose.desktop.currentOs)
    implementation(libs.ktor.client.cio)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

val brandSourceLogo = rootProject.layout.projectDirectory.file("branding/haive_logo.png")
val brandSourceAnimation = rootProject.layout.projectDirectory.file("branding/haive_splash.gif")
val generatedDesktopBrandDir = layout.buildDirectory.dir("generated/brand/desktop")
val generateDesktopBrandAssets = tasks.register("generateDesktopBrandAssets") {
    inputs.files(brandSourceLogo, brandSourceAnimation)
    outputs.dir(generatedDesktopBrandDir)
    doLast {
        val outputDir = generatedDesktopBrandDir.get().asFile
        BrandAssets.generateDesktop(
            source = brandSourceLogo.asFile,
            outputDir = outputDir,
        )
        BrandAssets.generateLoader(
            logoSource = brandSourceLogo.asFile,
            animationSource = brandSourceAnimation.asFile,
            outputDir = outputDir,
        )
        BrandLoaderVerifier.verify(outputDir)
    }
}

sourceSets {
    main {
        resources.srcDir(generatedDesktopBrandDir)
    }
}

tasks.processResources {
    dependsOn(generateDesktopBrandAssets)
}

tasks.matching {
    it.name.startsWith("package") ||
        it.name == "createDistributable" ||
        it.name == "runDistributable"
}.configureEach {
    dependsOn(generateDesktopBrandAssets)
}

val appVersionName = providers.gradleProperty("app.versionName").get()
// DMG and MSI only accept MAJOR.MINOR.PATCH; strip any prerelease suffix.
val appPackageVersion = appVersionName.substringBefore("-")
val nativePackageVersion = if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
    // macOS jpackage requires the first app-version component to be greater than zero.
    // Offset only the native macOS package major so Haive's public SemVer can remain pre-1.0.
    val components = appPackageVersion.split('.').map { it.toInt() }
    buildList {
        add((components.first() + 1).toString())
        addAll(components.drop(1).map(Int::toString))
    }.joinToString(".")
} else {
    appPackageVersion
}

compose.desktop {
    application {
        mainClass = "com.hereliesaz.geministrator.DesktopLauncherKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "TheHaive"
            packageVersion = nativePackageVersion
            description = "The Haive — agentic workflow orchestration"
            copyright = "© 2026 HereLiesAz"

            linux {
                iconFile.set(generatedDesktopBrandDir.get().file("haive-icon-color.png").asFile)
            }
            macOS {
                iconFile.set(generatedDesktopBrandDir.get().file("haive-icon.icns").asFile)
            }
            windows {
                iconFile.set(generatedDesktopBrandDir.get().file("haive-icon.ico").asFile)
                menuGroup = "The Haive"
                upgradeUuid = "e1d4b3c2-5f6a-4b8e-9d7c-0a1b2c3d4e5f"
            }
        }
    }
}
