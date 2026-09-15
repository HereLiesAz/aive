package com.hereliesaz.geministrator.memory

/**
 * Process-local bridge used by platform bootstraps to attach the live Memory layer to provider
 * sessions before ApplicationRuntime is created. Tests and platforms that do not install a memory
 * runtime retain the fail-safe no-op observer.
 */
object MemoryRuntimeBridge {
    var observer: MemorySessionObserver = NoOpMemorySessionObserver
}
