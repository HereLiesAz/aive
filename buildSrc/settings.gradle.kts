// buildSrc is a separate Gradle build and does not inherit the root pluginManagement.
// Kotlin plugin classpath artifacts must resolve from Maven Central, not only the Plugin Portal.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
