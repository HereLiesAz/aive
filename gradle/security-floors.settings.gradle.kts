// The one place that lifts vulnerable transitive dependencies to their fixed releases.
// Applied from settings.gradle.kts; it covers every project's build-script classpath and every
// project configuration. gradle.beforeProject runs before a project's script is evaluated, so the
// buildscript classpath is still unresolved and the strategy can be set. (This replaces three
// identical copies that used to sit in settings.gradle.kts and, twice, in build.gradle.kts. The
// settings classpath holds only the foojay resolver plugin, none of the floored libraries.)
// To add a floor: add a line to securityFloors, or a rule below, in this file only.

val securityFloorRule = Action<DependencyResolveDetails> {
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
            ?: return@Action)
        val old = major.toInt() < 33 || (major.toInt() == 33 && (minor.toInt() < 7 || (minor.toInt() == 7 && patch.ifEmpty { "0" }.toInt() < 2)))
        if (old) {
            useVersion(if (version.endsWith("-android")) "33.7.2-android" else "33.7.2-jre")
            because("CVE-2026-102554")
        }
    }
}

gradle.beforeProject {
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency(securityFloorRule) }
    configurations.configureEach { resolutionStrategy.eachDependency(securityFloorRule) }
}
