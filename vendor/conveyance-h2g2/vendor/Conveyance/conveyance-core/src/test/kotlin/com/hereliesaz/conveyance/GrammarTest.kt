package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrammarTest {

    @Test
    fun `every verb has exactly one signature`() {
        assertEquals(Verb.entries.size, Grammar.signatures.size)
        Verb.entries.forEach { assertEquals(it, Grammar[it].verb) }
    }

    /**
     * Exclusivity is the load-bearing rule of the whole framework. If two verbs move the same way,
     * the language has a homonym and a person cannot learn it by watching. This test is the rule.
     */
    @Test
    fun `no two verbs share a signature`() {
        val shapes = Grammar.signatures.map { it.copy(verb = Verb.Reveal) }
        assertEquals(shapes.size, shapes.toSet().size, "Two verbs move identically; the grammar has a homonym.")
    }

    @Test
    fun `every consequence class resolves to a verb and a signature`() {
        val e = ElementId("e")
        val s = SubjectId("s")
        val cases = listOf(
            Consequence.Reveal(e) to Verb.Reveal,
            Consequence.Enter(Place.from("p", origin = e)) to Verb.Enter,
            Consequence.Create(s, e) to Verb.Create,
            Consequence.Destroy(s, e) to Verb.Destroy,
            Consequence.Alter(s, "name", e) to Verb.Alter,
            Consequence.Send(s, e) to Verb.Send,
        )
        cases.forEach { (consequence, verb) ->
            assertEquals(verb, Verb.of(consequence))
            assertEquals(verb, Grammar.of(consequence).verb)
        }
    }

    @Test
    fun `enter and return are mirror images, not the same motion`() {
        val enter = Grammar[Verb.Enter]
        val ret = Grammar[Verb.Return]
        assertEquals(enter.from, ret.to)
        assertEquals(enter.to, ret.from)
        assertTrue(enter.morphs && ret.morphs)
    }

    /**
     * Reduced motion removes duration, never identity. If any of these assertions can be deleted,
     * the grammar was being carried by ornament.
     */
    @Test
    fun `the reduced register keeps morph, residue and deformation`() {
        Grammar.signatures.forEach { full ->
            val reduced = full.reduced()
            assertFalse(reduced.translates, "${full.verb} still traverses in the reduced register.")
            assertEquals(full.morphs, reduced.morphs, "${full.verb} lost its morph.")
            assertEquals(full.leavesResidue, reduced.leavesResidue, "${full.verb} lost its residue.")
            assertEquals(full.deforms, reduced.deforms, "${full.verb} lost its deformation.")
            assertEquals(full.from, reduced.from)
            assertEquals(full.to, reduced.to)
        }
    }

    @Test
    fun `only destroy leaves a residue and only refuse and yield deform`() {
        assertEquals(setOf(Verb.Destroy), Grammar.signatures.filter { it.leavesResidue }.map { it.verb }.toSet())
        assertEquals(setOf(Verb.Refuse, Verb.Yield), Grammar.signatures.filter { it.deforms }.map { it.verb }.toSet())
    }
}
