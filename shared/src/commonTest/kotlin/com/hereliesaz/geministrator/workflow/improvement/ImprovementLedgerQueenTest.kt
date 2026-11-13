package com.hereliesaz.geministrator.workflow.improvement

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImprovementLedgerQueenTest {
    private fun row(id: String, source: ImprovementSource, at: Long, status: ProposalStatus = ProposalStatus.Proposed) = ImprovementProposal(
        id = id, cycleRunId = "r", kind = ImprovementKind.Other, target = "t", summary = "s", newText = "", expectedEffect = "",
        undo = "", meta = false, status = status, createdAtEpochMillis = at, source = source,
    )

    @Test
    fun preQueenJsonStillDecodes() {
        val old = """{"proposals":[{"id":"p","cycleRunId":"r","kind":"Other","target":"t","summary":"s","newText":"","expectedEffect":"","undo":"","meta":false,"createdAtEpochMillis":1,"source":"Habit"}]}"""
        val state = Json { ignoreUnknownKeys = true }.decodeFromString(ImprovementState.serializer(), old)
        val p = state.proposals.single()
        assertEquals(ImprovementSource.Habit, p.source)
        assertEquals(null, p.action)
        assertEquals(emptyList(), p.args)
        assertFalse(p.outside)
    }

    @Test
    fun queenFieldsRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val p = row("q", ImprovementSource.Queen, 1).copy(action = "Rest", voice = "SuggestRestNanny", args = listOf("1"), outside = true)
        assertEquals(p, json.decodeFromString(ImprovementProposal.serializer(), json.encodeToString(ImprovementProposal.serializer(), p)))
    }

    @Test
    fun queenRowsCappedAt100() {
        val rows = (1..130).map { row("q$it", ImprovementSource.Queen, it.toLong()) }
        val kept = ProposalRetention.bound(rows)
        assertEquals(ProposalRetention.MAX_QUEEN, kept.size)
        assertEquals("q31", kept.first().id, "the oldest are dropped first")
    }

    @Test
    fun queenRowsNeverEvictLoopProposals() {
        val loop = (1..20).map { row("l$it", ImprovementSource.Loop, it.toLong()) }
        val queen = (1..150).map { row("q$it", ImprovementSource.Queen, 100L + it) }
        val kept = ProposalRetention.bound(loop + queen)
        assertEquals(loop.map { it.id }, kept.filter { it.source == ImprovementSource.Loop }.map { it.id })
        assertTrue(kept.count { it.source == ImprovementSource.Queen } <= ProposalRetention.MAX_QUEEN)
    }

    @Test
    fun decideIsCompareAndSet() = runBlocking<Unit> {
        val store = InMemoryImprovementStore()
        store.merge { it.copy(proposals = listOf(row("q", ImprovementSource.Queen, 1))) }
        assertTrue(store.decide("q") { it.copy(status = ProposalStatus.Adopted) })
        assertFalse(store.decide("q") { it.copy(status = ProposalStatus.Dismissed) }, "already decided")
        assertEquals(ProposalStatus.Adopted, store.state().proposals.single().status)
    }
}
