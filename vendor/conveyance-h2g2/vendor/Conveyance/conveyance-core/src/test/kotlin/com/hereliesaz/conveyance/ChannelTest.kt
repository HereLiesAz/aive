package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChannelTest {

    @Test
    fun `reference channels remain internally coherent`() {
        val assigned = Channel.entries.map { it.carries }
        assertEquals(assigned.size, assigned.toSet().size)
        Meaning.entries.forEach { assertEquals(it, Channel.carrying(it).carries) }
    }

    @Test
    fun `hue is available for stable visual identity`() {
        assertEquals(Channel.Hue, Channel.carrying(Meaning.VisualIdentity))
    }

    @Test
    fun `reference haptic intensity follows weight`() {
        assertEquals(Weight.Heavy, HapticVoice.Commit.intensity(Weight.Heavy))
    }

    @Test
    fun `working elements need four distinct jobs`() {
        assertFailsWith<IllegalArgumentException> { Employment.Working(Job.Report) }
        assertFailsWith<IllegalArgumentException> {
            Employment.Working(Job.Invite, Job.Progress, Job.Interrupt)
        }

        val employed = Employment.Working(Job.Invite, Job.Progress, Job.Interrupt, Job.Report)
        assertEquals(
            setOf(Job.Invite, Job.Progress, Job.Interrupt, Job.Report),
            employed.jobs,
        )
    }

    @Test
    fun `duplicate declarations do not fake the four-job constraint`() {
        assertFailsWith<IllegalArgumentException> {
            Employment.Working(Job.Invite, Job.Invite, Job.Invite, Job.Invite)
        }
    }
}
