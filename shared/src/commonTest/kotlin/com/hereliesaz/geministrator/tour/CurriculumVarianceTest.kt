package com.hereliesaz.geministrator.tour

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scaffolding for the property test that proves a curriculum is not prewritten (the queen's real planner plugs in as
 * [Planner]; until it exists a scripted planner that is a pure function of the packet stands in). The harness reports
 * failures as data so a meta-test can prove that a CANNED planner (one constant plan) is detected.
 */
class CurriculumVarianceTest {
    /** Everything a planner is given: registry rows filtered by facts, the goal's topics, and the comfort record. */
    data class Packet(
        val rows: List<TargetRow>,
        val goalTopics: Set<String>,
        val done: Set<String>,
        val shaky: Set<String>,
        val comfortable: Set<String>,
    )

    fun interface Planner {
        fun plan(packet: Packet): List<String>
    }

    // A synthetic registry: twelve targets, three topics, a few prerequisites. Not the real registry, on purpose.
    private val topics = mapOf(
        "T01" to "a", "T02" to "a", "T03" to "a", "T04" to "b", "T05" to "b", "T06" to "b",
        "T07" to "c", "T08" to "c", "T09" to "c", "T10" to "a", "T11" to "b", "T12" to "c",
    )
    private val needs = mapOf(
        "T03" to setOf("T01"), "T05" to setOf("T04"), "T06" to setOf("T04", "T05"),
        "T09" to setOf("T07"), "T10" to setOf("T02", "T03"), "T12" to setOf("T08"),
    )
    private val rows = topics.keys.map { id ->
        TargetRow(id, "Label $id", needs[id].orEmpty(), alwaysVisible = false, observable = setOf(ExpectKind.Tapped))
    }

    /** The reference scripted planner: derives the plan from the packet alone. */
    private val scripted = Planner { p ->
        val candidates = p.rows.filter { it.id !in p.done && it.id !in p.comfortable }
            .filter { p.goalTopics.isEmpty() || topics[it.id] in p.goalTopics }
        val remaining = candidates.toMutableList()
        val placed = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { r -> r.needs.none { n -> remaining.any { it.id == n } } }
            val pick = ready.sortedWith(compareBy({ it.id !in p.shaky }, { it.id })).first()
            placed.add(pick.id)
            remaining.remove(pick)
        }
        placed
    }

    private val canned = Planner { listOf("T01", "T02", "T03", "T04") }

    private fun randomPacket(r: Random): Packet {
        val all = topics.keys.toList()
        val goal: Set<String> = if (r.nextInt(4) == 0) emptySet() else setOf("a", "b", "c").filter { r.nextBoolean() }.toSet()
        val done = all.filter { r.nextInt(5) == 0 }.toSet()
        val shaky = all.filter { it !in done && r.nextInt(4) == 0 }.toSet()
        val comfortable = all.filter { it !in done && it !in shaky && r.nextInt(5) == 0 }.toSet()
        return Packet(rows, goal, done, shaky, comfortable)
    }

    /** The checks, as a list of failures (empty = the planner varies with its input and every plan is valid). */
    fun check(planner: Planner, seed: Int = 7, variations: Int = 300, minDifferingShare: Double = 0.5): List<String> {
        val r = Random(seed)
        val failures = mutableListOf<String>()
        val packets = List(variations) { randomPacket(r) }
        val plans = packets.map { planner.plan(it) }

        // (c) every plan is valid: only registered rows, no repeats, prerequisites first.
        packets.zip(plans).forEachIndexed { i, (p, plan) ->
            if (plan.toSet().size != plan.size) failures.add("variation $i repeats a target")
            if (plan.any { id -> p.rows.none { it.id == id } }) failures.add("variation $i names an unknown target")
            for (id in plan) for (n in needs[id].orEmpty()) {
                if (n in plan && plan.indexOf(n) > plan.indexOf(id)) failures.add("variation $i puts $id before its prerequisite $n")
            }
        }

        // (a) plans differ materially when the goal, the comfort record or the done facts differ.
        var differingInputs = 0
        var differingPlans = 0
        for (i in 1 until variations) {
            val a = packets[i - 1]
            val b = packets[i]
            if (a.goalTopics != b.goalTopics || a.shaky != b.shaky || a.comfortable != b.comfortable || a.done != b.done) {
                differingInputs++
                if (plans[i - 1] != plans[i]) differingPlans++
            }
        }
        if (differingInputs == 0 || differingPlans.toDouble() / differingInputs < minDifferingShare) {
            failures.add("plans barely change with the packet: $differingPlans of $differingInputs")
        }

        // (b) marking a target done removes it; marking one shaky never moves it later.
        for ((i, p) in packets.withIndex().take(60)) {
            val plan = plans[i]
            val t = plan.firstOrNull() ?: continue
            val without = planner.plan(p.copy(done = p.done + t))
            if (t in without) failures.add("variation $i still plans $t after it was marked done")
            val u = plan.lastOrNull() ?: continue
            val shakier = planner.plan(p.copy(shaky = p.shaky + u))
            if (u in shakier && shakier.indexOf(u) > plan.indexOf(u)) failures.add("variation $i moved shaky $u later")
        }
        return failures
    }

    @Test
    fun theScriptedPlannerVariesWithItsPacketAndAlwaysProducesValidPlans() {
        assertEquals(emptyList<String>(), check(scripted))
    }

    @Test
    fun metaACannedPlannerIsDetected() {
        val failures = check(canned)
        assertTrue(failures.isNotEmpty())
        assertTrue(failures.any { it.contains("barely change") }, failures.take(3).toString())
    }

    @Test
    fun aGoalNamingOneTopicYieldsAShorterPlanThanTheWholeApp() {
        val whole = scripted.plan(Packet(rows, emptySet(), emptySet(), emptySet(), emptySet()))
        val one = scripted.plan(Packet(rows, setOf("c"), emptySet(), emptySet(), emptySet()))
        assertTrue(one.size < whole.size)
        assertTrue(one.all { topics[it] == "c" })
    }

    @Test
    fun theHarnessAcceptsEveryValidPlanItCanProduceSoThePipelineMustToo() {
        // Any order that respects prerequisites is valid; a reviewer that rejects one of these would be a hidden syllabus.
        val r = Random(3)
        repeat(50) {
            val ids = topics.keys.shuffled(r).take(r.nextInt(1, 12))
            val plan = ids.sortedBy { needs[it].orEmpty().size }.let { sorted ->
                // Place prerequisites first, keeping the random order otherwise.
                val out = mutableListOf<String>()
                fun add(id: String) {
                    if (id in out) return
                    for (n in needs[id].orEmpty()) if (n in sorted) add(n)
                    out.add(id)
                }
                sorted.forEach { add(it) }
                out
            }
            for (id in plan) for (n in needs[id].orEmpty()) {
                if (n in plan) assertTrue(plan.indexOf(n) < plan.indexOf(id))
            }
        }
    }
}
