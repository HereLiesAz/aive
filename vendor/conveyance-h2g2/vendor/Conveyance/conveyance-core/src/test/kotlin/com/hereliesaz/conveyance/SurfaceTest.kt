package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertEquals

class SurfaceTest {

    private fun working() = Employment.Working(
        Job.Invite,
        Job.Report,
        Job.Progress,
        Job.Interrupt,
    )

    private fun element(id: String, rank: Rank) =
        DeclaredElement(ElementId(id), working(), rank = rank)

    @Test
    fun `a surface may have several prominent elements`() {
        val surface = Surface(
            "s",
            elements = listOf(element("a", Rank.Primary), element("b", Rank.Primary)),
        )
        assertEquals(2, surface.elements.count { it.rank == Rank.Primary })
    }

    @Test
    fun `a surface may have no primary`() {
        val surface = Surface(
            "s",
            elements = listOf(element("a", Rank.Secondary), element("b", Rank.Tertiary)),
        )
        assertEquals(0, surface.elements.count { it.rank == Rank.Primary })
    }

    @Test
    fun `act emphasis is semantic and defaults to supporting`() {
        val supporting = Act.reveal("details", ElementId("details"))
        val heroic = Act.reveal(
            "reveal.world",
            ElementId("world"),
            emphasis = ActEmphasis.Heroic,
        )

        assertEquals(ActEmphasis.Supporting, supporting.emphasis)
        assertEquals(ActEmphasis.Heroic, heroic.emphasis)
    }

    @Test
    fun `reference channels remain readable from declarations`() {
        val element = DeclaredElement(
            ElementId("a"),
            working(),
            channels = setOf(Channel.Hue, Channel.Elevation),
        )
        assertEquals(
            setOf(Meaning.VisualIdentity, Meaning.Reversibility),
            element.channels.map { it.carries }.toSet(),
        )
    }
}
