package com.hereliesaz.aive

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-local mailbox between the installed-Gemini transport and accessibility service. */
internal object InstalledGeminiBridge {
    enum class Phase { Idle, Input, AwaitResponse }

    private val bridgeMutex = Mutex()

    @Volatile var pendingPrompt: String? = null
    @Volatile var targetPackage: String? = null
    @Volatile var waiting: Boolean = false
    @Volatile var phase: Phase = Phase.Idle
    @Volatile var promptSubmitted: Boolean = false
    @Volatile var baselineCopyActions: Int = 0
    @Volatile var baselineSnapshot: String = ""
    @Volatile var observedGenerating: Boolean = false

    val responses: Channel<String> = Channel(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    suspend fun reset() {
        bridgeMutex.withLock {
            pendingPrompt = null
            targetPackage = null
            phase = Phase.Idle
            promptSubmitted = false
            baselineCopyActions = 0
            baselineSnapshot = ""
            observedGenerating = false
            waiting = false
            while (responses.tryReceive().getOrNull() != null) Unit
        }
    }
}
