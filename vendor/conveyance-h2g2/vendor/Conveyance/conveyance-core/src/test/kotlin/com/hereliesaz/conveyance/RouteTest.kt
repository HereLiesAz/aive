package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The prerequisite graph nobody wrote down.
 *
 * These are the tests that decide whether the Escort is a courtesy or a capability. Carrying a
 * person to the first thing that is missing is easy and is often useless — the missing thing is
 * frequently missing *because* something else is. What matters is arriving at the first thing they
 * can actually do, and that is a search, not a lookup.
 */
class RouteTest {

    private val here = ElementId("here")
    private val there = ElementId("there")
    private val further = ElementId("further")

    private fun gate(id: String, at: ElementId, open: Boolean) = Gate(id, at) { open }

    private fun act(id: String, vararg requires: Gate) =
        Act.alter(id = id, subject = SubjectId(id), property = "value", target = here, requires = requires.toList())

    /** Nothing in the way is not a route; it is permission. */
    @Test
    fun `an unblocked act needs no route`() {
        assertEquals(Step.Ready, Route.from(act("go")) { null })
    }

    /** The ordinary case, and the one the Escort already handled: one gate, one thing to do. */
    @Test
    fun `one gate routes to the act that opens it`() {
        val opener = act("choose")
        val goal = act("send", gate("chosen", there, open = false))

        val step = assertIs<Step.Do>(Route.from(goal) { if (it == there) opener else null })
        assertSame(opener, step.act)
        assertEquals("chosen", step.opens.id)
    }

    /**
     * The case that makes this worth having.
     *
     * The thing you are missing is itself unavailable. Escorting someone to it delivers them to a
     * second refusal, which is worse than the first because they have now been moved for nothing.
     */
    @Test
    fun `a blocked opener is skipped for the one that is actually possible`() {
        val root = act("sign in")
        val middle = act("choose", gate("signed in", further, open = false))
        val goal = act("send", gate("chosen", there, open = false))

        val step = assertIs<Step.Do>(
            Route.from(goal) {
                when (it) {
                    there -> middle
                    further -> root
                    else -> null
                }
            },
        )
        assertSame(root, step.act, "The escort should arrive at the only thing that can be done.")
        assertEquals("signed in", step.opens.id)
    }

    /** Breadth first, so what a person is carried to is the nearest way through and not merely a way. */
    @Test
    fun `the nearest way through wins`() {
        val near = act("near")
        val far = act("far")
        val detour = act("detour", gate("deep", further, open = false))
        val goal = act(
            "send",
            gate("slow", ElementId("detour"), open = false),
            gate("quick", there, open = false),
        )

        val step = assertIs<Step.Do>(
            Route.from(goal) {
                when (it) {
                    ElementId("detour") -> detour
                    further -> far
                    there -> near
                    else -> null
                }
            },
        )
        assertSame(near, step.act)
    }

    /**
     * A gate nothing can open is a hole in the product, and the framework says so rather than
     * pretending the escort worked.
     */
    @Test
    fun `a gate with nothing behind it is reported as a dead end`() {
        val goal = act("send", gate("chosen", there, open = false))
        val step = assertIs<Step.Stranded>(Route.from(goal) { null })
        assertEquals("chosen", step.gate.id)
    }

    /** A dead end is the last resort, not the first: a way through elsewhere still wins. */
    @Test
    fun `a dead end does not stop the search`() {
        val opener = act("choose")
        val goal = act(
            "send",
            gate("nowhere", ElementId("void"), open = false),
            gate("chosen", there, open = false),
        )

        val step = assertIs<Step.Do>(Route.from(goal) { if (it == there) opener else null })
        assertSame(opener, step.act)
    }

    /** Two gates that need each other terminate, because a product can be wrong. */
    @Test
    fun `a circular prerequisite does not hang`() {
        lateinit var second: Act
        val first = act("first", gate("b", ElementId("b"), open = false))
        second = act("second", gate("a", ElementId("a"), open = false))
        val goal = act("goal", gate("a", ElementId("a"), open = false))

        val step = Route.from(goal) {
            when (it) {
                ElementId("a") -> first
                ElementId("b") -> second
                else -> null
            }
        }
        assertIs<Step.Stranded>(step)
    }
}
