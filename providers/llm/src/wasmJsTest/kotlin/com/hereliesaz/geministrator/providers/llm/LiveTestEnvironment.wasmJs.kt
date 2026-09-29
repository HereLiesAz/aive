package com.hereliesaz.geministrator.providers.llm

/** Browsers have no process environment; Karma passes the settings in its client configuration. */
internal actual fun liveEnv(name: String): String? = karmaLiveEnv(name)

private fun karmaLiveEnv(name: String): String? = js(
    "(typeof __karma__ !== 'undefined' && __karma__.config && __karma__.config.aiveLiveEnv && typeof __karma__.config.aiveLiveEnv[name] === 'string') ? __karma__.config.aiveLiveEnv[name] : null",
)
