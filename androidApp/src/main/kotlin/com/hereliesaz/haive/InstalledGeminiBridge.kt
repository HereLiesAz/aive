package com.hereliesaz.haive

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/** Process-local mailbox between the installed-Gemini transport and accessibility service. */
internal object InstalledGeminiBridge {
    enum class Phase { Idle, Input, AwaitResponse }

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

    fun reset() {
        pendingPrompt = null
        targetPackage = null
        waiting = false
        phase = Phase.Idle
        promptSubmitted = false
        baselineCopyActions = 0
        baselineSnapshot = ""
        observedGenerating = false
        while (responses.tryReceive().getOrNull() != null) Unit
    }
}
