package com.hereliesaz.geministrator.providers.llm

internal actual fun liveEnv(name: String): String? = System.getenv(name)
