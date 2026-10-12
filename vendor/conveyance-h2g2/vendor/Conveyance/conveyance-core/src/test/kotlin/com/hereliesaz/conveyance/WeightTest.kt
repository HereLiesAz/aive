package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightTest {

    private val e = ElementId("e")
    private val s = SubjectId("s")

    @Test
    fun `the base weight of each consequence class`() {
        fun w(c: Consequence) = Weight.of(c, Scope.Item, reversible = false)
        assertEquals(Weight.Light, w(Consequence.Reveal(e)))
        assertEquals(Weight.Light, w(Consequence.Alter(s, "name", e)))
        assertEquals(Weight.Medium, w(Consequence.Create(s, e)))
        assertEquals(Weight.Medium, w(Consequence.Send(s, e)))
        assertEquals(Weight.Heavy, w(Consequence.Enter(Place.from("p", origin = e))))
        assertEquals(Weight.Heavy, w(Consequence.Destroy(s, e)))
    }

    /** Touching more of a person's world costs more in the hand. */
    @Test
    fun `scope adds inertia`() {
        val alter = Consequence.Alter(s, "name", e)
        assertEquals(Weight.Light, Weight.of(alter, Scope.Detail, reversible = false))
        assertEquals(Weight.Medium, Weight.of(alter, Scope.Collection, reversible = false))
        assertEquals(Weight.Heavy, Weight.of(alter, Scope.Everything, reversible = false))
    }

    /**
     * Reversibility lightens, because a thing you can take back costs less to try — but never below
     * the base for its class. An undoable deletion is still a deletion and must not feel like
     * flipping a switch.
     */
    @Test
    fun `reversibility lightens by one step and never below the class base`() {
        val destroy = Consequence.Destroy(s, e)
        assertEquals(Weight.Heavy, Weight.of(destroy, Scope.Item, reversible = true))
        assertEquals(Weight.Heavy, Weight.of(destroy, Scope.Collection, reversible = true))

        val create = Consequence.Create(s, e)
        assertEquals(Weight.Heavy, Weight.of(create, Scope.Collection, reversible = false))
        assertEquals(Weight.Medium, Weight.of(create, Scope.Collection, reversible = true))
    }

    @Test
    fun `wiping everything is always heavy, reversible or not`() {
        Scope.entries.filter { it == Scope.Everything }.forEach { scope ->
            listOf(true, false).forEach { reversible ->
                assertEquals(Weight.Heavy, Weight.of(Consequence.Destroy(s, e), scope, reversible))
            }
        }
    }

    @Test
    fun `weight on an act is derived, and tracks its own reversibility`() {
        val restore = Act.create("restore", s, into = e)
        val wipe = Act.destroy("wipe", s, target = e, inverse = restore, scope = Scope.Everything)
        assertEquals(Weight.Heavy, wipe.weight)

        val rename = Act.alter("rename", s, "name", e)
        assertEquals(Weight.Light, rename.weight)
        assertTrue(rename.weight.ordinal < wipe.weight.ordinal, "Renaming must not feel like wiping.")
    }
}
