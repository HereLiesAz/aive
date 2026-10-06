package com.hereliesaz.geministrator.providers.llm

/**
 * Reads a live-verification setting such as `AIVE_LIVE_RUNTIME_VERIFICATION`, or null when unset.
 *
 * Desktop and the Android host tests read the process environment. An Android device reads instrumentation arguments
 * (`-Pandroid.testInstrumentationRunnerArguments.<NAME>=<value>`). JS and Wasm browsers read the
 * values that `karma.config.d/live-runtime-verification.js` copies from the Gradle environment.
 */
internal expect fun liveEnv(name: String): String?
