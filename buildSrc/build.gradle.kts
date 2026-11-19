plugins {
    `kotlin-dsl`
}

repositories {
    // Kotlin runtime and Gradle plugin artifacts live on Maven Central; the Plugin Portal
    // can supply their metadata without serving the corresponding jars.
    mavenCentral()
    gradlePluginPortal()
}
