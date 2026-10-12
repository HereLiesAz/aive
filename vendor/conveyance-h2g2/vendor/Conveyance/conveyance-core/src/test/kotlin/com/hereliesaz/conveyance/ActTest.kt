package com.hereliesaz.conveyance

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActTest {

    private val list = ElementId("invoices")
    private val recipientField = ElementId("recipient.field")
    private val recipientAvatar = ElementId("recipient.avatar")
    private val invoice = SubjectId("invoice.41")

    @Test
    fun `an act with no unmet gate is ready`() {
        val act = Act.send("invoice.send", invoice, recipientAvatar)
        assertEquals(ActState.Ready, act.state())
        assertEquals(null, act.blockingGate())
    }

    @Test
    fun `a gate names the element where it is resolved`() {
        var recipient: String? = null
        val gate = Gate("recipient.chosen", livesAt = recipientField) { recipient != null }
        val act = Act.send("invoice.send", invoice, recipientAvatar, requires = listOf(gate))

        val blocked = assertIs<ActState.Blocked>(act.state())
        assertEquals(recipientField, blocked.gate.livesAt)

        recipient = "someone"
        assertEquals(ActState.Ready, act.state())
    }

    @Test
    fun `a blocked act escorts instead of performing`() = runTest {
        var performed = false
        val gate = Gate("never", livesAt = recipientField) { false }
        val act = Act.send("invoice.send", invoice, recipientAvatar, requires = listOf(gate)) {
            performed = true
            Outcome.Done
        }

        val seen = mutableListOf<ActState>()
        val terminal = act.engage(seen::add)

        assertIs<ActState.Blocked>(terminal)
        assertFalse(performed, "A blocked act must not run its work.")
        assertEquals(1, seen.size, "A blocked act does not pass through Yielding.")
    }

    @Test
    fun `an engaged act yields then settles`() = runTest {
        val act = Act.create("invoice.new", invoice, into = list)
        val seen = mutableListOf<ActState>()

        assertEquals(ActState.Settled, act.engage(seen::add))
        assertContentEquals(listOf(ActState.Yielding(), ActState.Settled), seen)
    }

    @Test
    fun `failure becomes a state of the element carrying a derived retry`() = runTest {
        val act = Act.send("invoice.send", invoice, recipientAvatar) { Outcome.Failed(Refusal.Unreachable) }
        val refused = assertIs<ActState.Refused>(act.engage())
        assertEquals(Refusal.Unreachable, refused.refusal)
        assertTrue(refused.retryable)

        val denied = Act.send("x", invoice, recipientAvatar) { Outcome.Failed(Refusal.Denied) }
        assertFalse(assertIs<ActState.Refused>(denied.engage()).retryable)
    }

    @Test
    fun `work that dies part-way is interrupted`() = runTest {
        val act = Act.create("boom", invoice, into = list) { error("connection dropped") }
        assertEquals(ActState.Refused(Refusal.Interrupted), act.engage())
    }

    @Test
    fun `ordinary destruction stays reversible by construction`() {
        val restore = Act.create("invoice.restore", invoice, into = list)
        val delete = Act.destroy("invoice.delete", invoice, target = list, inverse = restore)

        assertTrue(delete.reversible)
        assertEquals(restore, delete.inverse)
        assertTrue(delete.signature.leavesResidue)
    }

    @Test
    fun `irreversible destruction is a named opt-out not a nullable inverse`() {
        val destroy = Act.destroyIrreversibly(
            id = "invoice.submit.final",
            subject = invoice,
            target = list,
            scope = Scope.Everything,
        )

        assertFalse(destroy.reversible)
        assertEquals(null, destroy.inverse)
        assertEquals(Verb.Destroy, destroy.verb)
    }

    @Test
    fun `an act cannot be declared without a consequence that names its target`() {
        val send = Act.send("invoice.send", invoice, recipientAvatar)
        assertEquals(recipientAvatar, send.consequence.target)

        val create = Act.create("invoice.new", invoice, into = list)
        assertEquals(list, create.consequence.target)

        val enter = Act.enter("invoice.open", Place.from("invoice.detail", origin = list))
        assertEquals(list, enter.consequence.target)
    }

    @Test
    fun `entering a root place is refused where the act is made`() {
        assertFailsWith<IllegalArgumentException> { Act.enter("go.home", Place.root("home")) }
        assertFailsWith<IllegalArgumentException> { Consequence.Enter(Place.root("home")) }
    }
}
