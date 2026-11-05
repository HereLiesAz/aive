package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrariumFocusTest {
    // o -> a -> b -> c -> end ;  b -> side ; lone (disconnected)
    private val ids = setOf("o", "a", "b", "c", "end", "side", "lone")
    private val graph = FocusGraph(
        ids,
        listOf("o" to "a", "a" to "b", "b" to "c", "c" to "end", "b" to "side"),
    )

    private fun levels(sel: String, active: Set<String> = emptySet(), blocked: Set<String> = emptySet(), orch: Collection<String> = listOf("o")) =
        focusLevels(graph, setOf(sel), orch, active, blocked)

    @Test
    fun graphHelpers() {
        assertEquals(listOf("c", "b", "a", "o"), graph.directedPath("c", setOf("o"), forward = false))
    }

    @Test
    fun nearestEndGoalWinsAndTheOrchestratorFallsBackToTheEntry() {
        assertEquals(listOf("b", "side"), graph.directedPath("b", setOf("end", "side"), forward = true))
        assertEquals("o", graph.orchestrator(emptyList())) // entries are o and lone; the one with strands wins
        assertEquals("a", FocusGraph(setOf("a", "b"), listOf("a" to "b")).orchestrator(emptyList()))
        assertEquals("b", graph.orchestrator(listOf("b")))
        assertEquals(setOf("o", "a"), graph.ancestors("b"))
        assertEquals(setOf("c", "end", "side"), graph.descendants("b"))
        assertNull(graph.undirectedPath("a", setOf("lone")))
    }

    @Test
    fun levelsRunInOrderAndTheShorterPathComesFirst() {
        // b: up to o is 4 nodes; down to the nearest end (side) is 2 nodes, so the end goal is first.
        val l = levels("b")
        assertEquals(
            listOf(FocusLevel.Creature, FocusLevel.Neighbours, FocusLevel.PathToEnd, FocusLevel.PathToOrchestrator, FocusLevel.WholeChain, FocusLevel.Overview),
            l.map { it.level },
        )
        assertEquals(setOf("b"), l[0].nodes)
        assertEquals(setOf("a", "b", "c", "side"), l[1].nodes)
        assertEquals(setOf("b", "side"), l[2].nodes)
        assertEquals(setOf("b", "a", "o"), l[3].nodes)
    }

    @Test
    fun theOrchestratorPathWinsTiesAndEmptyLevelsAreSkipped() {
        // a: up = o,a (2), down = a,b,side (3): orchestrator first. No active, no blocks.
        val l = levels("a")
        assertEquals(FocusLevel.PathToOrchestrator, l[2].level)
        assertTrue(l.none { it.level == FocusLevel.PathToActive || it.level == FocusLevel.PathToBlocks })
        // The orchestrator itself has no path up; a terminal has none down.
        assertTrue(levels("o").none { it.level == FocusLevel.PathToOrchestrator })
        assertTrue(levels("end").none { it.level == FocusLevel.PathToEnd })
    }

    @Test
    fun activeAndBlockedPathsAppearWhenThereAreSome() {
        val l = levels("a", active = setOf("c"), blocked = setOf("side"))
        assertEquals(setOf("a", "b", "c"), l.first { it.level == FocusLevel.PathToActive }.nodes)
        assertEquals(setOf("a", "b", "side"), l.first { it.level == FocusLevel.PathToBlocks }.nodes)
        // The selected creature being the active one gives no path to itself.
        assertTrue(levels("c", active = setOf("c")).none { it.level == FocusLevel.PathToActive })
    }

    @Test
    fun duplicatesAreDroppedAndTheOverviewStays() {
        val chain = FocusGraph(setOf("o", "a"), listOf("o" to "a"))
        val l = focusLevels(chain, setOf("a"), listOf("o"), emptySet(), emptySet())
        // Neighbours, path and chain all equal {o,a}, which is the whole hive: only the Overview remains of them.
        assertEquals(listOf(FocusLevel.Creature, FocusLevel.Overview), l.map { it.level })
    }

    @Test
    fun noOrchestratorFallsBackToTheEntryAndCyclesDoNotHang() {
        val l = focusLevels(graph, setOf("c"), emptyList(), emptySet(), emptySet())
        assertEquals(setOf("c", "b", "a", "o"), l.first { it.level == FocusLevel.PathToOrchestrator }.nodes)
        val loop = FocusGraph(setOf("x", "y", "z"), listOf("x" to "y", "y" to "x", "y" to "z"))
        assertTrue(focusLevels(loop, setOf("x"), emptyList(), setOf("z"), setOf("z")).isNotEmpty())
        assertEquals(setOf("y", "z"), loop.descendants("x") - "x")
    }

    @Test
    fun nothingSelectedIsJustTheOverview() {
        assertEquals(listOf(FocusLevel.Overview), focusLevels(graph, emptySet(), listOf("o"), emptySet(), emptySet()).map { it.level })
    }

    @Test
    fun aClusterIsOneFocus() {
        val l = focusLevels(graph, setOf("a", "side"), listOf("o"), emptySet(), emptySet())
        assertEquals(setOf("a", "side"), l[0].nodes)
        assertTrue(l.any { it.level == FocusLevel.WholeChain })
    }

    @Test
    fun theStackStepsOutAndBackInAndResets() {
        var s = FocusStack().selected(levels("b"))
        assertEquals(FocusLevel.Creature, s.current.level)
        s = s.stepIn()
        assertEquals(0, s.index)
        repeat(20) { s = s.stepOut() }
        assertEquals(FocusLevel.Overview, s.current.level)
        s = s.stepIn()
        assertEquals(FocusLevel.WholeChain, s.current.level)
        assertEquals(0, s.selected(levels("a")).index)
        // A run moving on keeps the same kind of view.
        assertEquals(FocusLevel.WholeChain, s.refreshed(levels("b", active = setOf("c"))).current.level)
    }

    @Test
    fun fitCentresPadsAndClamps() {
        val one = fitCamera(listOf(H2g2TerrariumPosition(0.3f, 0.4f)))
        assertEquals(0.3f, one.focus.x); assertEquals(0.4f, one.focus.y)
        assertTrue(one.zoom in 1.2f..TerrariumCamera.MAX_ZOOM)
        val all = overviewFit(listOf(H2g2TerrariumPosition(0.1f, 0.1f), H2g2TerrariumPosition(0.9f, 0.9f)))
        assertTrue(all.zoom <= 1.3f && all.zoom >= TerrariumCamera.MIN_ZOOM)
        // A subset is never wider than the whole hive's view.
        val sub = fitCamera(listOf(H2g2TerrariumPosition(0.1f, 0.1f), H2g2TerrariumPosition(0.9f, 0.9f)), floorZoom = all.zoom)
        assertTrue(sub.zoom >= all.zoom)
        assertEquals(1f, fitCamera(emptyList()).zoom)
    }
}
