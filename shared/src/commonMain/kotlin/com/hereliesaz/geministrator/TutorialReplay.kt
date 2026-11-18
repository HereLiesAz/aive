package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.intro.TutorialRouter

/**
 * "Teach me" asked from the Keeper's room (About), to be served by the launch form or the hive on screen: the same seeded goal
 * the first hive offers, fed the same way, so the lesson can be replayed without retyping it. Held as state and taken once.
 */
internal object TutorialRequests {
    var pending: Boolean by mutableStateOf(false)

    /** True once, when a request was pending. */
    fun take(): Boolean = pending.also { pending = false }
}

/** Where a "Teach me" request is served. */
internal enum class TeachTarget {
    /** No hive is live: the launch form takes the seeded goal and its own gate decides what Feed it does. */
    FillLaunchForm,

    /** A hive is live: the goal is queued on it and fed when its swarm is done, as any queued objective is. */
    QueueOnHive,
}

internal fun teachTarget(hasLiveRun: Boolean): TeachTarget = if (hasLiveRun) TeachTarget.QueueOnHive else TeachTarget.FillLaunchForm

/**
 * Whether the launch form says, in a plain line, that the lessons need a Source: the goal asks to be taught the app, and no
 * Source is connected (the gate wants a provider) and none is being connected for the keeper. A fixed string, never model text.
 */
internal fun tutorialNeedsSourceLine(objective: String, gate: FeedGate, sourcePickedForKeeper: Boolean): Boolean =
    !sourcePickedForKeeper && gate == FeedGate.Provider && TutorialRouter.isTutorialGoal(objective)

/**
 * The queen is away (the tutorial needed her model and no Source could give one) and there was no run to say so on: the
 * shell shows one line with a link to Connections until the keeper follows it. Set by the app, taken by the shell.
 */
internal object QueenAwayNotice {
    var shown: Boolean by mutableStateOf(false)
}

/** Whether a tutorial run is in progress, so a second "teach me" goal does not stop the first one's layer. Lives outside composition. */
internal object TutorialRunGuard {
    var running: Boolean = false
}
