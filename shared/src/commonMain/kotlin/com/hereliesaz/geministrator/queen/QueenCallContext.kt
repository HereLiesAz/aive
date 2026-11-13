package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.workflow.KeylessPacing
import com.hereliesaz.geministrator.workflow.QUEEN_PACE_CALL_MILLIS
import com.hereliesaz.geministrator.workflow.QUEEN_PACE_TOTAL_MILLIS
import kotlinx.coroutines.withContext
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
    private val onMoved: suspend (from: String, to: String, why: String) -> Unit = { _, _, _ -> },
) : AbstractCoroutineContextElement(Key) {
    private var waitedMillis = 0L

    /** What the current call (one stage's answer, across every Source it tries) has waited; null until [beginCall]. */
    private var callWaitedMillis: Long? = null

    /** A new call (one dispute stage's answer) starts: the per-call cap counts afresh, however many Sources it tries. */
    fun beginCall() {
        callWaitedMillis = 0L
    }

    /** True when a call through this context may have to wait for a Source's turn. */
    val paces: Boolean get() = pacing != null

    /** The most one call may wait for a turn: her call limit, and what is left of the dispute's total. */
    fun callBudgetMillis(): Long {
        val dispute = pacingBudget(totalWaitMillis, waitedMillis, callWaitMillis)
        val spent = callWaitedMillis ?: return dispute
        return minOf(dispute, maxOf(0L, callWaitMillis - spent))
    }

    /**
     * Waits for [providerId]'s turn; true when it may be asked now (the wait counts as hers), false when its turn is further
     * off than her bounded wait, so the caller moves on to the next Source (Automatic) or gives up (Pinned).
     */
    suspend fun awaitTurn(providerId: String): Boolean {
        val gate = pacing ?: return true
        val waited = gate.await(providerId, callBudgetMillis()) ?: return false
        waitedMillis += waited
        callWaitedMillis = callWaitedMillis?.plus(waited)
        return true
    }

    suspend fun moved(from: String, to: String, why: String) = onMoved(from, to, why)

    companion object Key : CoroutineContext.Key<QueenCallContext>
}

/** What one call may wait: [callMillis], but never more than what is left of [totalMillis] after [waitedMillis]. Pure. */
fun pacingBudget(totalMillis: Long, waitedMillis: Long, callMillis: Long): Long =
    minOf(callMillis, maxOf(0L, totalMillis - waitedMillis))

/** The enclosing dispute's call context, if any. */
suspend fun currentQueenCallContext(): QueenCallContext? = coroutineContext[QueenCallContext]

/**
 * [base] whose every answer runs in its own fresh [QueenCallContext] on [pacing], so a long run of calls (a tutorial's plan and
 * lessons) keeps each Source's request limit with a full wait budget per call instead of one shared dispute total.
 */
fun pacedPerCall(base: QueenModel, pacing: KeylessPacing?): QueenModel = object : QueenModel {
    override val uses: List<ModelUse> get() = base.uses

    override suspend fun answer(prompt: String): String =
        withContext(QueenCallContext(pacing)) { base.answer(prompt) }
}
