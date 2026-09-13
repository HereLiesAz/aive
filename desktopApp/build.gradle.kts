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
}

kotlin {
    jvmToolchain(17)
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
        mainClass = "com.hereliesaz.geministrator.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "TheHaive"
            packageVersion = nativePackageVersion
            description = "The Haive — agentic workflow orchestration"
            copyright = "© 2026 HereLiesAz"

            linux {
                iconFile.set(rootProject.file("branding/haive-icon-color.png"))
            }
            macOS {
                val icns = rootProject.file("branding/haive-icon.icns")
                if (icns.exists()) iconFile.set(icns)
            }
            windows {
                val ico = rootProject.file("branding/haive-icon.ico")
                if (ico.exists()) iconFile.set(ico)
                menuGroup = "The Haive"
                upgradeUuid = "e1d4b3c2-5f6a-4b8e-9d7c-0a1b2c3d4e5f"
            }
        }
    }
}
