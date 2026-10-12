package com.hereliesaz.conveyance.compose

import androidx.compose.runtime.mutableStateListOf
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.SubjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `resolveSlots` and `pruneLikeness`, tested as what they actually are: plain functions over
 * data, not composables. There is nothing about merging a live list with a set of ghosts, or
 * about deciding what a cache should still hold, that needs a composition to check.
 *
 * This replaces an earlier `desktopTest` that tried to prove the likeness cache doesn't leak with
 * `System.gc()` and a `WeakReference` -- a real object was, in that harness, observed to survive
 * garbage collection for reasons that traced back to something in the full Compose animation
 * pipeline rather than to `likeness` itself (confirmed separately: `likeness.keys` was already
 * correctly pruned by direct inspection). A test that can fail for reasons unrelated to the code
 * it is meant to verify is not a regression test, it is noise -- so the fix is asserted on
 * directly instead, against the exact map it acts on.
 */
class CollectionSlotsTest {

    private class Doc(val id: String)
    private val key: (Doc) -> SubjectId = { SubjectId(it.id) }

    @Test
    fun `pruneLikeness drops exactly what the current slots no longer account for`() {
        val likeness = mutableMapOf(
            SubjectId("keep") to Doc("keep"),
            SubjectId("gone") to Doc("gone"),
        )
        val slots = listOf<Slot<Doc>>(Slot.Present(Doc("keep")))

        pruneLikeness(likeness, slots, key)

        assertEquals(setOf(SubjectId("keep")), likeness.keys)
    }

    @Test
    fun `pruneLikeness keeps a subject still held as a recoverable ghost`() {
        val likeness = mutableMapOf(SubjectId("ghosted") to Doc("ghosted"))
        val slots = listOf<Slot<Doc>>(Slot.Gone(SubjectId("ghosted")))

        pruneLikeness(likeness, slots, key)

        assertEquals(setOf(SubjectId("ghosted")), likeness.keys)
    }

    /**
     * The scenario the original leak was about: a subject dropped outright, with no destroy act
     * and no ghost, across many cycles. If pruning were broken this would grow without bound;
     * checked here as a map size, not a reference that may or may not have been collected yet.
     */
    @Test
    fun `pruneLikeness does not accumulate across repeated add-then-drop cycles`() {
        val likeness = mutableMapOf<SubjectId, Doc>()
        repeat(500) { n ->
            val doc = Doc("doc.$n")
            likeness[key(doc)] = doc
            val slots = listOf<Slot<Doc>>(Slot.Present(doc))
            pruneLikeness(likeness, slots, key)
            assertTrue(likeness.size <= 1, "likeness grew to ${likeness.size} on cycle $n.")

            val emptied = emptyList<Slot<Doc>>()
            pruneLikeness(likeness, emptied, key)
            assertTrue(likeness.isEmpty(), "likeness should be empty once nothing is Present or ghosted.")
        }
    }

    /** The other half: `resolveSlots` merging a live list with a ghost, as a value, not a UI effect. */
    @Test
    fun `resolveSlots holds a ghost's slot open where it was, then drops it once released`() {
        val ghosts = Ghosts()
        val order = mutableStateListOf<SubjectId>()
        val doc1 = Doc("1")
        val doc2 = Doc("2")
        val doc3 = Doc("3")

        val initial = resolveSlots(listOf(doc1, doc2, doc3), key, ghosts, order)
        assertEquals(listOf(SubjectId("1"), SubjectId("2"), SubjectId("3")), initial.map { it.subject(key) })

        ghosts.leave(
            com.hereliesaz.conveyance.Act.destroy(
                "doc.2.delete",
                SubjectId("2"),
                target = ElementId("list"),
                inverse = com.hereliesaz.conveyance.Act.create("doc.2.restore", SubjectId("2"), into = ElementId("list")),
            ),
            at = ElementId("list"),
        )
        val withGhost = resolveSlots(listOf(doc1, doc3), key, ghosts, order)
        assertEquals(
            listOf(SubjectId("1"), SubjectId("2"), SubjectId("3")),
            withGhost.map { it.subject(key) },
            "The ghost holds position 2 open rather than the list collapsing around it.",
        )
        assertTrue(withGhost[1] is Slot.Gone)

        ghosts.release(SubjectId("2"))
        val released = resolveSlots(listOf(doc1, doc3), key, ghosts, order)
        assertEquals(
            listOf(SubjectId("1"), SubjectId("3")),
            released.map { it.subject(key) },
            "Once released, the slot closes and the gap is gone.",
        )
    }
}
