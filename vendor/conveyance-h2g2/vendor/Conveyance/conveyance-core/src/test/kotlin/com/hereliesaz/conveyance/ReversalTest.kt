package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReversalTest {

    private val list = ElementId("documents")
    private val doc = SubjectId("doc.7")

    @Test
    fun `the recovery window grows with what the act cost`() {
        assertTrue(Reversal.windowMillis(Weight.Heavy) > Reversal.windowMillis(Weight.Medium))
        assertTrue(Reversal.windowMillis(Weight.Medium) > Reversal.windowMillis(Weight.Light))
    }

    /**
     * The window is doing the work a confirmation dialog would otherwise do, so a destruction that
     * matters has to get meaningfully longer than one that does not.
     */
    @Test
    fun `destroying a whole collection is recoverable for far longer than a detail`() {
        val restore = Act.create("restore", doc, into = list)
        val deleteOne = Act.destroy("delete.one", doc, target = list, inverse = restore)
        assertEquals(Reversal.windowMillis(Weight.Heavy), Reversal.windowMillis(deleteOne))
        assertTrue(Reversal.windowMillis(deleteOne) >= 30_000)
    }

    @Test
    fun `nothing but a destruction can leave a residue`() {
        val send = Act.send("send", doc, to = ElementId("outbox"))
        assertFailsWith<IllegalArgumentException> { Reversal.windowMillis(send) }
    }
}
