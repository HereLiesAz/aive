package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PracticeTest {

    private val list = ElementId("tracks")
    private val track = SubjectId("track.9")

    @Test
    fun `a tell is owed once, before the first use, and never again`() {
        val practice = Practice()
        val act = Act.create("track.add", track, into = list)

        assertTrue(practice.owesTell(act.id))
        practice.record(act.id)
        assertFalse(practice.owesTell(act.id), "An element that keeps hinting after the fact is nagging.")

        repeat(50) { practice.record(act.id) }
        assertFalse(practice.owesTell(act.id))
    }

    @Test
    fun `ceremony attenuates with familiarity`() {
        val practice = Practice()
        val act = Act.create("track.add", track, into = list)

        assertEquals(Ceremony.Full, practice.ceremonyFor(act.id))
        repeat(3) { practice.record(act.id) }
        assertEquals(Ceremony.Practised, practice.ceremonyFor(act.id))
        repeat(17) { practice.record(act.id) }
        assertEquals(Ceremony.Fluent, practice.ceremonyFor(act.id))
    }

    @Test
    fun `fluency lightens an inflated weight but never below the class base`() {
        val practice = Practice()
        val bulkAdd = Act.create("track.addAll", track, into = list, scope = Scope.Collection)
        assertEquals(Weight.Heavy, bulkAdd.weight)

        repeat(20) { practice.record(bulkAdd.id) }
        assertEquals(Weight.Medium, practice.weightFor(bulkAdd), "Familiarity should buy speed.")
        assertEquals(Weight.Medium, Weight.baseOf(bulkAdd.consequence))
    }

    /**
     * The floor is the important half. Practice earns speed, never a reduction in stakes — the cost
     * to the person did not change just because their hand got quicker.
     */
    @Test
    fun `destruction stays heavy no matter how practised the hand`() {
        val practice = Practice()
        val restore = Act.create("track.restore", track, into = list)
        val delete = Act.destroy("track.delete", track, target = list, inverse = restore)

        repeat(10_000) { practice.record(delete.id) }
        assertEquals(Ceremony.Fluent, practice.ceremonyFor(delete.id))
        assertEquals(Weight.Heavy, practice.weightFor(delete))
    }

    @Test
    fun `practice is tracked per act, not globally`() {
        val practice = Practice()
        val add = Act.create("track.add", track, into = list)
        val play = Act.enter("track.play", Place.from("player", origin = list))

        repeat(25) { practice.record(add.id) }
        assertEquals(Ceremony.Fluent, practice.ceremonyFor(add.id))
        assertEquals(Ceremony.Full, practice.ceremonyFor(play.id))
        assertTrue(practice.owesTell(play.id))
    }
}
