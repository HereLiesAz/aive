package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommendationTest {

    private fun element(
        id: String,
        jobs: Set<Job>,
        left: Float,
        act: ActId? = null,
        lifecycleAct: ActId? = null,
        verb: Verb? = null,
        target: ElementId? = null,
    ) = AuditElement(
        id = ElementId(id),
        left = left,
        top = 0f,
        width = 20f,
        height = 20f,
        visible = true,
        act = act,
        lifecycleAct = lifecycleAct,
        verb = verb,
        target = target,
        jobs = jobs,
    )

    @Test
    fun `observed facts derive behavioral roles without component names`() {
        val source = element(
            id = "anything",
            jobs = setOf(Job.Invite, Job.Interrupt),
            left = 0f,
            act = ActId("save"),
        )

        val roles = source.behavioralRoles()

        assertTrue(BehavioralRole.ActionSource in roles)
        assertTrue(BehavioralRole.Interruptible in roles)
    }

    @Test
    fun `gate address derives gate resolver role`() {
        val id = ElementId("recipient")
        val resolver = element(
            id = id.value,
            jobs = setOf(Job.Locate),
            left = 0f,
        )

        val roles = resolver.behavioralRoles(setOf(id))

        assertTrue(BehavioralRole.GateResolver in roles)
        assertTrue(BehavioralRole.Locator in roles)
    }

    @Test
    fun `consequence target derives destination role from the graph`() {
        val destinationId = ElementId("wherever")
        val source = element(
            id = "source.with.no.naming.clue",
            jobs = setOf(Job.Invite),
            left = 0f,
            act = ActId("move"),
            target = destinationId,
        )
        val destination = element(
            id = destinationId.value,
            jobs = setOf(Job.Identify),
            left = 24f,
        )
        val targets = listOf(source, destination).mapNotNullTo(mutableSetOf()) { it.target }

        val roles = destination.behavioralRoles(targets = targets)

        assertTrue(BehavioralRole.Destination in roles)
    }

    @Test
    fun `proven fragmented action lifecycle maps through roles to Offer`() {
        val save = ActId("save")
        val source = element(
            id = "save.source",
            jobs = setOf(Job.Invite, Job.Interrupt),
            left = 0f,
            act = save,
        )
        val progress = element(
            id = "save.progress",
            jobs = setOf(Job.Progress),
            left = 24f,
            lifecycleAct = save,
        )
        val completion = element(
            id = "save.complete",
            jobs = setOf(Job.Confirm),
            left = 48f,
            lifecycleAct = save,
        )
        val frame = AuditFrame(
            surface = "editor",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(source, progress, completion),
        )

        val suggestion = ConsolidationAdvisor.suggest(frame).single()

        assertEquals(ConveyanceRecipes.Offer, suggestion.replacement)
        assertTrue(BehavioralRole.ActionSource in suggestion.combinedRoles)
        assertTrue(BehavioralRole.ProgressReporter in suggestion.combinedRoles)
        assertTrue(BehavioralRole.CompletionReporter in suggestion.combinedRoles)
        assertTrue(suggestion.message().contains("Conveyance Offer"))
    }

    @Test
    fun `nearby lifecycle shaped fragments are not guessed to belong to an Offer`() {
        val source = element(
            id = "save.source",
            jobs = setOf(Job.Invite, Job.Interrupt),
            left = 0f,
            act = ActId("save"),
        )
        val progress = element(
            id = "mystery.progress",
            jobs = setOf(Job.Progress),
            left = 24f,
        )
        val completion = element(
            id = "mystery.complete",
            jobs = setOf(Job.Confirm),
            left = 48f,
        )
        val frame = AuditFrame(
            surface = "editor",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(source, progress, completion),
        )

        val suggestion = ConsolidationAdvisor.suggest(frame).single()

        assertNull(suggestion.replacement)
        assertTrue(suggestion.message().startsWith("Combine"))
    }

    @Test
    fun `Offer never absorbs two distinct offered Acts into one lifecycle`() {
        val firstAct = element(
            id = "first.source",
            jobs = setOf(Job.Invite, Job.Progress),
            left = 0f,
            act = ActId("first"),
        )
        val secondAct = element(
            id = "second.source",
            jobs = setOf(Job.Interrupt),
            left = 24f,
            act = ActId("second"),
        )
        val completion = element(
            id = "completion",
            jobs = setOf(Job.Confirm),
            left = 48f,
        )
        val frame = AuditFrame(
            surface = "editor",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(firstAct, secondAct, completion),
        )

        val suggestion = ConsolidationAdvisor.suggest(frame).single()

        assertNull(suggestion.replacement)
        assertTrue(suggestion.message().startsWith("Combine"))
    }

    @Test
    fun `custom legacy recipe can still match raw jobs`() {
        val legacy = ComposableRecipe(
            name = "LegacyThing",
            absorbs = setOf(Job.Invite, Job.Report, Job.Progress, Job.Interrupt),
            docsAnchor = "legacy",
        )
        val first = element(
            id = "a",
            jobs = setOf(Job.Invite, Job.Report),
            left = 0f,
        )
        val second = element(
            id = "b",
            jobs = setOf(Job.Progress, Job.Interrupt),
            left = 24f,
        )
        val frame = AuditFrame(
            surface = "legacy",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(first, second),
        )

        assertEquals(legacy, ConsolidationAdvisor.suggest(frame, recipes = listOf(legacy)).single().replacement)
    }
}
