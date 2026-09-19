plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

dependencies {
    // The relay consumes protocol/domain classes from shared, not the app/UI runtime graph.
    // Keep UI/toolkit dependencies out of this headless distribution.
    implementation(projects.shared) {
        isTransitive = false
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
