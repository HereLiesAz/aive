package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.workflow.KeylessPacing
import com.hereliesaz.geministrator.workflow.QUEEN_PACE_CALL_MILLIS
import com.hereliesaz.geministrator.workflow.QUEEN_PACE_TOTAL_MILLIS
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * What a dispute's model calls carry with them, in the coroutine that raises it, so the model (which only sees a prompt) can
 * keep a Source's request limit and tell the keeper in the Story when she moves to another Source. With none installed, a call
 * is not paced and a move is not told.
 */
class QueenCallContext(
    private val pacing: KeylessPacing?,
    private val totalWaitMillis: Long = QUEEN_PACE_TOTAL_MILLIS,
    private val callWaitMillis: Long = QUEEN_PACE_CALL_MILLIS,
) : AbstractCoroutineContextElement(Key) {
    private var waitedMillis = 0L

    /** True when a call through this context may have to wait for a Source's turn. */
    val paces: Boolean get() = pacing != null

    /** The most one call may wait for a turn: her call limit, and what is left of the dispute's total. */
    fun callBudgetMillis(): Long = pacingBudget(totalWaitMillis, waitedMillis, callWaitMillis)

    /**
     * Waits for [providerId]'s turn; true when it may be asked now (the wait counts as hers), false when its turn is further
     * off than her bounded wait, so the caller moves on to the next Source (Automatic) or gives up (Pinned).
     */
    suspend fun awaitTurn(providerId: String): Boolean {
        val gate = pacing ?: return true
        val waited = gate.await(providerId, callBudgetMillis()) ?: return false
        waitedMillis += waited
        return true
    }

    companion object Key : CoroutineContext.Key<QueenCallContext>
}

/** What one call may wait: [callMillis], but never more than what is left of [totalMillis] after [waitedMillis]. Pure. */
fun pacingBudget(totalMillis: Long, waitedMillis: Long, callMillis: Long): Long =
    minOf(callMillis, maxOf(0L, totalMillis - waitedMillis))

/** The enclosing dispute's call context, if any. */
suspend fun currentQueenCallContext(): QueenCallContext? = coroutineContext[QueenCallContext]
