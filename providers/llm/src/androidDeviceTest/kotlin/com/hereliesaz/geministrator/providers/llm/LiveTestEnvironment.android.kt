package com.hereliesaz.geministrator.providers.llm

import androidx.test.platform.app.InstrumentationRegistry

/** A device has no host environment; settings arrive as instrumentation runner arguments. */
internal actual fun liveEnv(name: String): String? =
    InstrumentationRegistry.getArguments().getString(name) ?: System.getenv(name)
