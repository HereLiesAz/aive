package com.hereliesaz.geministrator.events

import com.hereliesaz.geministrator.Sha256

/**
 * A run's event journal, hash-chained: each event's link is SHA-256 of the link before it and the event's stored text,
 * so the final link (the tip) commits to every event, their order and their count. The store records the tip as events
 * are written; an event edited, deleted or moved afterwards no longer folds to the recorded tip.
 *
 * What this proves: the journal changed since it was written (a bug, a bad import, a hand edit of the file). What it does
 * not: someone who can write the whole store can recompute the tip too, and a run whose events were written before this
 * existed has no chain ([ChainStatus.NoChain]), which is never reported as broken.
 */
object EventChain {
    /** The link before the first event. */
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    /** The link after [payload], given the link [previous] it. */
    fun next(previous: String, payload: String): String = Sha256.hex((previous + "\n" + payload).encodeToByteArray())

    /** The count and tip of [payloads] folded in order from [GENESIS]. */
    fun fold(payloads: List<String>): ChainTip {
        var tip = GENESIS
        payloads.forEach { tip = next(tip, it) }
        return ChainTip(payloads.size, tip)
    }
}

/** How many events a chain covers and its last link. Stored beside the run as `count:tip`. */
data class ChainTip(val count: Int, val tip: String) {
    fun encode(): String = "$count:$tip"

    companion object {
        fun parse(stored: String): ChainTip? {
            val colon = stored.indexOf(':')
            if (colon <= 0) return null
            val count = stored.substring(0, colon).toIntOrNull() ?: return null
            return ChainTip(count, stored.substring(colon + 1))
        }
    }
}

/** What checking a run's event journal against its recorded chain found. */
sealed interface ChainStatus {
    /** Every one of [count] events folds to the recorded tip. */
    data class Intact(val count: Int) : ChainStatus

    /** The events no longer fold to what was recorded: one was edited, removed, added or moved. */
    data class Altered(val recorded: ChainTip, val actual: ChainTip) : ChainStatus

    /** No chain was recorded for this run (it predates the chain, or this store keeps none): nothing to check. */
    data object NoChain : ChainStatus
}
