package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.domain.RepositoryRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitEnvironmentTest {
    private val op = "github-action:ci.yml"

    private fun use(run: String, at: Long, environment: String?) = ProcedureTrace(
        workflowId = run, projectId = "p", taskRunId = "tr-$run", taskDefinitionId = "build", attempt = 1,
        phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
        outcome = ProcedureTraceOutcome.Completed, recordedAtEpochMillis = at, operation = op,
        environment = environment,
    )

    @Test
    fun anEnvironmentIsARepositoryNameAndNothingElse() {
        assertEquals("a/b", use("r", 1, "a/b").environment)
        listOf("not a name", "a/b/c", "/b", "a/", "a b/c", "x".repeat(101) + "/y").forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad) { use("r", 1, bad) }
        }
        assertEquals("a/b", ProcedureTraceRedactor.environmentOf(RepositoryRef("a", "b")))
        assertNull(ProcedureTraceRedactor.environmentOf(RepositoryRef("has space", "b")))
        assertNull(ProcedureTraceRedactor.environmentOf(null))
    }

    @Test
    fun aHabitCountsWhereItsUsesWereDoneMostUsedFirst() {
        val habit = HabitFormation.form(
            listOf(use("r1", 1, "acme/web"), use("r2", 2, "acme/api"), use("r3", 3, "acme/web"), use("r4", 4, null)),
        ).single()
        assertEquals(listOf(HabitEnvironment("acme/web", 2), HabitEnvironment("acme/api", 1)), habit.environments)
        // A receipt with no environment reads as it always did.
        assertTrue(HabitFormation.form(listOf(use("r1", 1, null))).single().environments.isEmpty())
    }

    @Test
    fun theOfferStatesWhereItWasDoneAndSaysNothingWhenNoReceiptNamedOne() {
        val habit = HabitFormation.form(
            listOf(use("r1", 1, "acme/web"), use("r2", 2, "acme/api"), use("r3", 3, "acme/web")),
        ).single()
        val text = assertNotNull(HabitOffers.render(listOf(HabitOffer(habit, listOf("ci"))), 10L))
        assertTrue("Done in acme/web (2), acme/api (1)." in text)

        val bare = HabitFormation.form(listOf(use("r1", 1, null), use("r2", 2, null), use("r3", 3, null))).single()
        assertFalse("Done in" in assertNotNull(HabitOffers.render(listOf(HabitOffer(bare, listOf("ci"))), 10L)))
    }

    @Test
    fun theBankedEpisodeNamesTheEnvironmentSoMemoryCanSeeTheSameFrameInADifferentPlace() {
        val with = use("r", 1, "acme/web").contextualSummary()
        val without = use("r", 1, null).contextualSummary()
        assertTrue("Environment: acme/web." in with)
        assertFalse("Environment" in without)
    }

    @Test
    fun theStatsAreCountsOfWhatTheHabitsAddUpTo() {
        fun ev(run: String, at: Long, operation: String, outcome: ProcedureTraceOutcome = ProcedureTraceOutcome.Completed) = ProcedureTrace(
            workflowId = run, projectId = "p", taskRunId = "tr-$run-$operation", taskDefinitionId = "t", attempt = 1,
            phase = ProcedureTracePhase.Reconcile, executorKind = "GitHubAction",
            outcome = outcome, recordedAtEpochMillis = at, operation = operation,
        )
        val a = "github-action:ci.yml"
        val b = "deployment:production"
        val habits = HabitFormation.form(
            // The same configured values (none) completed and failed: an open contrast, no decision.
            listOf(ev("1", 1, a), ev("2", 2, a), ev("3", 3, a, ProcedureTraceOutcome.Failed), ev("1", 4, b)),
        )
        val stats = HabitOffers.stats(habits)
        assertEquals(2, stats.habits)
        assertEquals(1, stats.offered)
        assertEquals(4, stats.uses)
        assertEquals(1, stats.failedUses)
        assertEquals(1, stats.openContrastsUnanswered)
        assertEquals(0, stats.activeOverrides)
        assertEquals(HabitOffers.Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), HabitOffers.stats(emptyList()))
    }
}

