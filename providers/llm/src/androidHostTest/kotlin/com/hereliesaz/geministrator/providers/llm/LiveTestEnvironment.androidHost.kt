package com.hereliesaz.geministrator.providers.llm

/** Host (JVM) unit tests on the Android target read the process environment, as on desktop. */
internal actual fun liveEnv(name: String): String? = System.getenv(name)
