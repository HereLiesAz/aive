package com.hereliesaz.geministrator.providers.llm

/** Browsers have no process environment; Karma passes the settings in its client configuration. */
internal actual fun liveEnv(name: String): String? {
    val value: dynamic = js(
        "(typeof __karma__ !== 'undefined' && __karma__.config && __karma__.config.aiveLiveEnv) ? __karma__.config.aiveLiveEnv[name] : undefined",
    )
    return value as? String
}
