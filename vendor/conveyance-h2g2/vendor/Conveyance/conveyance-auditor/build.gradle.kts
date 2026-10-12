plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "conveyance-auditor"
            pom {
                name.set("Conveyance Auditor")
                description.set(
                    "Shows a rendered surface to a viewer who has never seen it and grades what " +
                        "they predicted against the truth -- the two rules no structural check can " +
                        "settle. Reaches any OpenAI-compatible provider, Anthropic natively, or a " +
                        "local model with no API key at all.",
                )
            }
        }
    }
}

dependencies {
    api(project(":conveyance-core"))
    implementation(libs.anthropic.java)
    implementation(libs.jackson.kotlin)

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
