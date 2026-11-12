pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// The settings classpath (plugin resolution) takes the same Guava floor as the build: see build.gradle.kts.
buildscript {
    configurations.configureEach {
        // CVE-2026-102554 (GHSA-xxph-c9ww-hj94): Guava before 33.7.2 over-allocates when deserializing.
        // Guava only arrives transitively; lift every older request to the fixed release, keeping its flavour.
        resolutionStrategy.eachDependency {
            // Jackson (GHSA alerts #21-#30: ReDoS, unbounded parse and token growth, SSRF, type-ID retention)
            // arrives only transitively (quickjs4j, Android tooling); lift every older core/databind to 2.22.4.
            if (requested.group == "com.fasterxml.jackson.core" &&
                (requested.name == "jackson-core" || requested.name == "jackson-databind")
            ) {
                val parts = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?""").find(requested.version.orEmpty())?.destructured
                if (parts != null) {
                    val (major, minor, patch) = parts
                    val old = major.toInt() == 2 &&
                        (minor.toInt() < 22 || (minor.toInt() == 22 && patch.ifEmpty { "0" }.toInt() < 4))
                    if (old) {
                        useVersion("2.22.4")
                        because("Jackson DoS and SSRF advisories fixed in 2.22.4")
                    }
                }
            }
            // Dependabot alerts #1-#4, #13, #16, #17, #19 (BC name-constraints and ASN.1 depth, bcpkix, JDOM XXE, jose4j JWE DoS,
            // Kotlin Gradle plugin build-cache deserialization, commons-lang3 recursion, HttpClient XSS). All arrive only
            // transitively; lift every older request to the fixed release. Pre-release suffixes compare by their numeric core.
            val securityFloors = mapOf(
                "org.bouncycastle:bcprov-jdk18on" to "1.86",
                "org.bouncycastle:bcpkix-jdk18on" to "1.86",
                "org.jdom:jdom2" to "2.0.6.1",
                "org.bitbucket.b_c:jose4j" to "0.9.7",
                "org.jetbrains.kotlin:kotlin-gradle-plugin" to "2.4.21",
                "org.apache.commons:commons-lang3" to "3.21.0",
                "org.apache.httpcomponents:httpclient" to "4.5.14",
            )
            securityFloors["${requested.group}:${requested.name}"]?.let { floor ->
                fun core(v: String) = Regex("""\d+""").findAll(v.substringBefore('-')).map { it.value.toInt() }.toList()
                val have = core(requested.version.orEmpty())
                val want = core(floor)
                val older = (0 until maxOf(have.size, want.size)).firstNotNullOfOrNull { n ->
                    (have.getOrElse(n) { 0 }).compareTo(want.getOrElse(n) { 0 }).takeIf { it != 0 }
                }?.let { it < 0 } ?: false
                if (have.isNotEmpty() && older) {
                    useVersion(floor)
                    because("Dependabot security advisories fixed in $floor")
                }
            }
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                val version = requested.version.orEmpty()
                val (major, minor, patch) = (Regex("""^(\d+)\.(\d+)(?:\.(\d+))?""").find(version)?.destructured
                    ?: return@eachDependency)
                val old = major.toInt() < 33 || (major.toInt() == 33 && (minor.toInt() < 7 || (minor.toInt() == 7 && patch.ifEmpty { "0" }.toInt() < 2)))
                if (old) {
                    useVersion(if (version.endsWith("-android")) "33.7.2-android" else "33.7.2-jre")
                    because("CVE-2026-102554")
                }
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Kotlin/JS/Wasm package tasks cannot safely traverse the nested composite
// Aive -> H2G2 -> Conveyance. Production web tasks therefore resolve the exact Maven-local
// publications prepared by CI. Android/Desktop and ordinary development keep compiling H2G2 source.
val isolatedWebBuild = gradle.startParameter.taskNames.any { task ->
    task.startsWith(":webApp:") && (
        task.contains("jsBrowser", ignoreCase = true) ||
            task.contains("wasmJsBrowser", ignoreCase = true)
        )
}
val useLocalH2g2 = providers.gradleProperty("haive.useLocalH2g2")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: !isolatedWebBuild
val useMavenLocalH2g2 = providers.gradleProperty("haive.useMavenLocalH2g2")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: isolatedWebBuild
val localH2g2 = file("vendor/conveyance-h2g2")
if (useLocalH2g2 && localH2g2.exists()) {
    includeBuild(localH2g2) {
        dependencySubstitution {
            substitute(module("com.github.HereLiesAz:conveyance-h2g2"))
                .using(project(":"))
        }
    }
}

dependencyResolutionManagement {
    repositories {
        if (useMavenLocalH2g2) mavenLocal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "aive"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":shared")
include(":providers:llm")
include(":androidApp")
include(":desktopApp")
include(":webApp")

include(":computeRelay")
include(":mcpBridge")
include(":cliApp")
