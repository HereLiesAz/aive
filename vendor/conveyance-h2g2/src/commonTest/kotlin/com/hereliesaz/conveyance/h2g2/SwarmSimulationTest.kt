package com.hereliesaz.conveyance.h2g2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SwarmSimulationTest {
    private val specs = listOf(
        H2g2SwarmAgentSpec(
            id = "orchestrator",
            identityKind = H2g2SwarmIdentityKind.Orchestrator,
            habitatX = .5f,
            habitatY = .5f,
        ),
        H2g2SwarmAgentSpec("research", habitatX = .2f, habitatY = .3f),
        H2g2SwarmAgentSpec("builder", habitatX = .8f, habitatY = .7f),
    )

    @Test
    fun `same identities and time steps reproduce the same colony`() {
        val first = H2g2SwarmWorld(specs)
        val second = H2g2SwarmWorld(specs)

        repeat(120) {
            first.step(16f)
            second.step(16f)
        }

        assertEquals(first.snapshots(), second.snapshots())
    }

    @Test
    fun `agents own distinct personalities and decision cadences`() {
        val world = H2g2SwarmWorld(specs)
        val research = world.snapshot("research")!!
        val builder = world.snapshot("builder")!!

        assertNotEquals(research.personality, builder.personality)
        assertTrue(research.personality.decisionPeriodMillis in 110f..260f)
        assertTrue(builder.personality.decisionPeriodMillis in 110f..260f)
    }

    @Test
    fun `orchestrator remains a canonical non procedural identity`() {
        val orchestrator = H2g2SwarmWorld(specs).snapshot("orchestrator")!!

        assertEquals(H2g2SwarmIdentityKind.Orchestrator, orchestrator.identityKind)
        assertEquals(.96f, orchestrator.personality.sociability)
    }

    @Test
    fun `transfer creates contact and paired interaction states`() {
        val world = H2g2SwarmWorld(specs)
        world.beginTransfer("research", "builder")

        assertEquals(H2g2SwarmBehavior.Transfer, world.snapshot("research")!!.behavior)
        assertEquals(H2g2SwarmBehavior.Approach, world.snapshot("builder")!!.behavior)
        val contact = world.activeContacts().single()
        assertEquals(H2g2SwarmContactKind.Transfer, contact.kind)
        assertEquals("research", contact.sourceId)
        assertEquals("builder", contact.targetId)
    }

    @Test
    fun `spawned child is born at parent and retains lineage`() {
        val world = H2g2SwarmWorld(specs)
        val parentBefore = world.snapshot("orchestrator")!!
        val child = world.spawnChild("orchestrator", "verifier")

        assertEquals("orchestrator", child.parentId)
        assertEquals(H2g2SwarmBehavior.Birth, child.behavior)
        assertEquals(parentBefore.x, child.x)
        assertEquals(parentBefore.y, child.y)
        assertEquals(H2g2SwarmContactKind.Birth, world.activeContacts().single().kind)
    }

    @Test
    fun `completed interaction releases agents back to autonomous behavior`() {
        val world = H2g2SwarmWorld(specs)
        world.beginConference("research", "builder", durationMillis = 120f)

        repeat(3) { world.step(60f) }

        assertTrue(world.activeContacts().isEmpty())
        assertEquals(H2g2SwarmBehavior.Recover, world.snapshot("research")!!.behavior)
        assertEquals(H2g2SwarmBehavior.Recover, world.snapshot("builder")!!.behavior)
    }

    @Test
    fun `habitat edits can move presentation without inventing workflow semantics`() {
        val world = H2g2SwarmWorld(specs)
        world.moveHabitat("research", .72f, .18f, moveCreature = true)

        val moved = world.snapshot("research")!!
        assertEquals(.72f, moved.habitatX)
        assertEquals(.18f, moved.habitatY)
        assertEquals(.72f, moved.x)
        assertEquals(.18f, moved.y)
        assertTrue(world.activeContacts().isEmpty())
    }
}
