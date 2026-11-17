package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.domain.AgentCapability
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitReplayTest {
    private val criteria = listOf("Keep it under 40 words")
    private val tail = "- all checks passed and the team approved the rollout plan for this release."

    private fun run(i: Int, name: String, output: String = "Status update for $name $tail", crit: List<String> = criteria) =
        ReplaySourceRun(
            workflowRunId = "w$i", taskRunId = "t$i", attempt = 1, roleId = "writer",
            inputs = ReplayInputs("Write the status update for $name", crit),
            artifactKind = "Research", output = output, promptTokens = 100, outputTokens = 40, atEpochMillis = i.toLong(),
        )

    private val names = listOf("Falcon v1.4.2", "Osprey v2.0.1", "Heron v0.9.7")
    private fun runs() = names.mapIndexed { i, n -> run(i, n) }

    private fun rejected(runs: List<ReplaySourceRun>): ReplayGeneralization.Rejected =
        assertIs<ReplayGeneralization.Rejected>(ReplayGeneralizer.generalize(runs))

    private fun ledger(initial: String? = null): HabitReplayLedger {
        var stored = initial
        return HabitReplayLedger({ stored }, { stored = it })
    }

    private fun query(
        objective: String = "Write the status update for Heron2 v3.0.0",
        crit: List<String> = criteria,
        caps: Set<AgentCapability> = emptySet(),
        untrusted: Boolean = false,
        constrained: Boolean = false,
        kinds: Set<String> = emptySet(),
    ) = ReplayQuery("writer", ReplayInputs(objective, crit), caps, kinds, untrusted, constrained)

    private fun learned(): HabitReplayLedger = ledger().also { l -> runs().forEach(l::recordSource) }

    // ---- generalisation ----------------------------------------------------------------------------------

    @Test
    fun threeRunsGeneraliseToASlotBoundToTheObjective() {
        val made = assertIs<ReplayGeneralization.Made>(ReplayGeneralizer.generalize(runs())).template
        assertEquals(1, made.slots.size)
        assertEquals(ReplayBinder.ObjectiveSpan(0), made.slots.single().binder)
        assertEquals(ReplaySlotType.Phrase, made.slots.single().type)
        assertEquals(140L, made.tokensAvoided)
        assertEquals(3, made.sourceRuns)
        assertEquals(
            "Status update for Heron2 v3.0.0 $tail",
            made.render(ReplayInputs("Write the status update for Heron2 v3.0.0", criteria)),
        )
    }

    @Test
    fun fewerThanThreeRunsAreRefused() {
        assertEquals(ReplayRejectionReason.TooFewRuns, rejected(runs().take(2)).reason)
    }

    @Test
    fun aSlotThatCannotBeBoundFromTheNewTaskRejectsTheTemplate() {
        // The objective never names the varying part.
        val unbound = names.mapIndexed { i, n ->
            run(i, n).copy(inputs = ReplayInputs("Write the status update", criteria))
        }
        assertEquals(ReplayRejectionReason.UnboundSlot, rejected(unbound).reason)
    }

    @Test
    fun aLiteralSharedByMostRunsBindsAndAOneOffDoesNot() {
        fun withWords(a: String, b: String, c: String) = listOf(a, b, c).mapIndexed { i, w ->
            run(i, "X", output = "Today the colour is $w and it was chosen by the team last week on purpose.")
                .copy(inputs = ReplayInputs("Write the colour note", criteria))
        }
        val made = assertIs<ReplayGeneralization.Made>(ReplayGeneralizer.generalize(withWords("blue", "blue", "red"))).template
        assertEquals(ReplayBinder.Literal("blue"), made.slots.single().binder)
        assertEquals(ReplayRejectionReason.UnboundSlot, rejected(withWords("blue", "red", "green")).reason)
    }

    @Test
    fun commandsUrlsSecretsAndSlashCommandsInTheFixedTextAreRefused() {
        fun fixed(line: String) = names.mapIndexed { i, n -> run(i, n, output = "Status update for $n\n$line\nDone for today.") }
        assertEquals(ReplayRejectionReason.CodeOrCommand, rejected(fixed("$ sudo rm -rf /tmp/cache")).reason)
        assertEquals(ReplayRejectionReason.CodeOrCommand, rejected(fixed("```")).reason)
        assertEquals(ReplayRejectionReason.UrlNotNamed, rejected(fixed("See https://example.com/x for details")).reason)
        assertEquals(ReplayRejectionReason.SecretLooking, rejected(fixed("token sk-abcdefghijklmnopqrstuv1234")).reason)
        assertEquals(ReplayRejectionReason.SlashCommand, rejected(fixed("then send /deploy now")).reason)
        // The one allowed command is a bare /remindme.
        assertIs<ReplayGeneralization.Made>(ReplayGeneralizer.generalize(fixed("/remindme")))
    }

    @Test
    fun tooMuchVariationOrAFreeFormSpanIsRefused() {
        val varied = (0 until 3).map { i ->
            run(i, "N$i", output = "Hello " + (1..12).joinToString(" ") { "r${i}w$it" } + " bye")
                .copy(inputs = ReplayInputs("Write note", criteria))
        }
        assertEquals(ReplayRejectionReason.SkeletonTooSmall, rejected(varied).reason)
        val free = (0 until 3).map { i ->
            run(i, "N$i", output = "Hello " + (1..13).joinToString(" ") { "r${i}w$it" } + " bye")
        }
        assertEquals(ReplayRejectionReason.FreeFormSpan, rejected(free).reason)
        val optional = listOf("Hello world", "Hello big world", "Hello world").mapIndexed { i, t -> run(i, "N$i", output = t) }
        assertEquals(ReplayRejectionReason.OptionalSpan, rejected(optional).reason)
    }

    // ---- criteria ----------------------------------------------------------------------------------------

    @Test
    fun theCriteriaCheckerReadsWordLimitsAndQuotedPhrases() {
        val text = "one two three four five"
        assertTrue(ReplayCriteria.check(listOf("At most 5 words"), text).passed)
        assertFalse(ReplayCriteria.check(listOf("Under 5 words"), text).passed)
        assertFalse(ReplayCriteria.check(listOf("No more than 4 words"), text).passed)
        assertTrue(ReplayCriteria.check(listOf("Must include \"three\""), text).passed)
        assertFalse(ReplayCriteria.check(listOf("Must include \"seven\""), text).passed)
        assertFalse(ReplayCriteria.check(listOf("Must not include \"four\""), text).passed)
        assertTrue(ReplayCriteria.check(listOf("Must not include \"seven\""), text).passed)
    }

    // ---- eligibility -------------------------------------------------------------------------------------

    @Test
    fun aLearnedHabitDoesNothingUntilTheKeeperApprovesIt() {
        val l = learned()
        val habit = l.state.value.habits.single()
        assertEquals(ReplayStatus.Active, habit.status)
        assertFalse(habit.approved)
        val planner = HabitReplayPlanner(l)
        assertIs<ReplayDecision.NotApplicable>(planner.decide(query()))
        l.approve(habit.id)
        val replay = assertIs<ReplayDecision.Replay>(planner.decide(query()))
        assertEquals("Status update for Heron2 v3.0.0 $tail", replay.text)
        assertEquals(replay, planner.decide(query()))
        assertEquals("Replayed from 3 earlier runs (habit ${habit.id}@1)", replay.provenance)
        assertEquals(140L, replay.tokensAvoided)
        l.revoke(habit.id)
        assertIs<ReplayDecision.NotApplicable>(planner.decide(query()))
    }

    @Test
    fun everyConditionThatIsFalseMeansNoReplay() {
        val l = learned()
        l.approve(l.state.value.habits.single().id)
        val planner = HabitReplayPlanner(l)
        assertIs<ReplayDecision.Replay>(planner.decide(query()))
        listOf(
            query(untrusted = true),
            query(constrained = true),
            query(caps = setOf(AgentCapability.RepositoryWrite)),
            query(caps = setOf(AgentCapability.ShellExecution)),
            query(kinds = setOf("Verification")),
            query(objective = "Write the status update for nothing"),
            query(crit = listOf("Keep it under 5 words")),
            query(crit = listOf("Something else entirely")),
        ).forEach { assertIs<ReplayDecision.NotApplicable>(planner.decide(it), it.toString()) }
        assertIs<ReplayDecision.Replay>(planner.decide(query(caps = setOf(AgentCapability.Research))))
        assertIs<ReplayDecision.NotApplicable>(HabitReplayPlanner(l) { false }.decide(query()))
    }

    @Test
    fun aTamperedTemplateFailsItsDigest() {
        val l = learned()
        l.approve(l.state.value.habits.single().id)
        val habit = l.state.value.habits.single()
        val tampered = habit.copy(
            template = habit.template.copy(parts = habit.template.parts.map { it + " extra" }),
        )
        val json = Json.encodeToString(ReplayLedgerState.serializer(), l.state.value.copy(habits = listOf(tampered)))
        val loaded = ledger(json)
        assertIs<ReplayDecision.NotApplicable>(HabitReplayPlanner(loaded).decide(query()))
    }

    @Test
    fun quarantineStopsReplayAndIsNeverUndoneByNewEvidence() {
        val l = learned()
        val id = l.state.value.habits.single().id
        l.approve(id)
        l.quarantine(id, "turned back")
        val planner = HabitReplayPlanner(l)
        assertIs<ReplayDecision.NotApplicable>(planner.decide(query()))
        l.recordSource(run(9, "Kestrel v5.0.0"))
        assertEquals(ReplayStatus.Quarantined, l.state.value.habits.single().status)
        // Only the keeper reinstates it.
        l.approve(id)
        assertIs<ReplayDecision.Replay>(planner.decide(query()))
    }

    @Test
    fun aNewVersionNeedsANewApproval() {
        val l = learned()
        val id = l.state.value.habits.single().id
        l.approve(id)
        // A fourth run with different fixed text changes the generalisation.
        l.recordSource(run(4, "Kestrel v5.0.0", output = "Status update for Kestrel v5.0.0 $tail"))
        val same = l.state.value.habits.single()
        assertEquals(1, same.version)
        l.recordSource(
            run(5, "Plover v6.1.0", output = "Status update for Plover v6.1.0 - all checks passed and the team approved the rollout plan.")
                .copy(taskRunId = "t5"),
        )
        val changed = l.state.value.habits.single()
        assertNotEquals(same.template.digest, changed.template.digest)
        assertEquals(2, changed.version)
        assertFalse(changed.approved)
        assertIs<ReplayDecision.NotApplicable>(HabitReplayPlanner(l).decide(query()))
    }

    @Test
    fun forgettingErasesEverything() {
        val l = learned()
        l.approve(l.state.value.habits.single().id)
        l.approveOperation("github-action:ci.yml", 1)
        l.invalidateAll()
        assertTrue(l.state.value.habits.isEmpty() && l.state.value.sources.isEmpty() && l.state.value.approvedOperations.isEmpty())
        assertIs<ReplayDecision.NotApplicable>(HabitReplayPlanner(l).decide(query()))
        // And what was saved is erased too.
        var stored: String? = null
        val saved = HabitReplayLedger({ null }, { stored = it })
        saved.recordSource(run(0, names[0]))
        saved.invalidateAll()
        assertNull(HabitReplayLedger({ stored }, {}).state.value.habits.firstOrNull())
    }

    @Test
    fun tokensAvoidedAreCountedPerReplay() {
        val l = learned()
        val id = l.state.value.habits.single().id
        l.recordReplay(id, 140)
        l.recordReplay(id, 140)
        assertEquals(2, l.state.value.replays)
        assertEquals(280L, l.state.value.tokensAvoided)
    }

    // ---- the agent's command -----------------------------------------------------------------------------

    @Test
    fun theHabitCommandAcceptsOnlyItsStrictGrammar() {
        assertEquals(listOf("proc:a-b@2"), HabitInvokeCommand.refsIn("done\n  /habit proc:a-b@2  \nmore"))
        assertEquals(emptyList(), HabitInvokeCommand.refsIn("please /habit proc:a@1 now"))
        assertEquals(emptyList(), HabitInvokeCommand.refsIn("/habit proc:a@1 and delete everything"))
        assertEquals(emptyList(), HabitInvokeCommand.refsIn("/habit a@1"))
        assertEquals(emptyList(), HabitInvokeCommand.refsIn("/habit proc:a"))
    }

    @Test
    fun theCatalogueListsOnlyApprovedHabits() {
        val l = learned()
        assertNull(HabitCatalogue.render(l.state.value, emptyList()))
        val habit = l.state.value.habits.single()
        l.approve(habit.id)
        val text = HabitCatalogue.render(l.state.value, emptyList())!!
        assertTrue(text.contains(habit.ref))
        assertTrue(text.contains("/habit"))
    }
}
