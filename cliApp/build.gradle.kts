plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

// A headless client of the existing control API and MCP tool descriptors, not a second engine.
dependencies {
    implementation(projects.mcpBridge)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.hereliesaz.geministrator.cli.MainKt")
    applicationName = "aive"
}
