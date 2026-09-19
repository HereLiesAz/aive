plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

dependencies {
    // The relay consumes protocol/domain classes from shared, not the app/UI runtime graph.
    // Exclude only UI/toolkit families so non-UI transitive runtime dependencies remain intact.
    implementation(projects.shared) {
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.animation")
        exclude(group = "org.jetbrains.compose.material3")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.components")
        exclude(group = "com.github.HereLiesAz", module = "conveyance-h2g2")
    }
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.hereliesaz.geministrator.relay.ComputeRelayServerKt")
}
