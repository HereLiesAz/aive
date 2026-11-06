plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

// A standalone process: it speaks MCP over stdio and talks to the running desktop app's control API
// (docs/MCP.md). It shares no code with the app, only JSON over loopback HTTP, so its runtime
// classpath is the Kotlin stdlib and kotlinx.serialization. The app's control API is used only by tests.
dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(projects.shared) {
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.animation")
        exclude(group = "org.jetbrains.compose.material3")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.components")
        exclude(group = "com.github.HereLiesAz", module = "conveyance-h2g2")
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.hereliesaz.geministrator.mcpbridge.MainKt")
    applicationName = "aive-mcp"
}
