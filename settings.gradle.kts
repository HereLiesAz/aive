pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Override or align ANDROID_SDK_ROOT system property with ANDROID_HOME to prevent
// SDK location conflict errors between ANDROID_HOME and ANDROID_SDK_ROOT environment variables.
val androidHome = System.getenv("ANDROID_HOME") ?: "C:\\Users\\azrie\\AppData\\Local\\Android\\Sdk"
System.setProperty("ANDROID_SDK_ROOT", androidHome)

val useLocalH2g2 = providers.gradleProperty("haive.useLocalH2g2")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: true
val useMavenLocalH2g2 = providers.gradleProperty("haive.useMavenLocalH2g2")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: false
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

rootProject.name = "haive"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":shared")
include(":providers:jules")
include(":providers:llm")
include(":androidApp")
include(":desktopApp")
include(":webApp")
