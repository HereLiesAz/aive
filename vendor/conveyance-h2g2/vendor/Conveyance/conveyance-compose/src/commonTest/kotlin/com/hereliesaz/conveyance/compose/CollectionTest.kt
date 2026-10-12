package com.hereliesaz.conveyance.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.Act
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Practice
import com.hereliesaz.conveyance.SubjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CollectionTest {

    private val list = ElementId("documents")
    private fun doc(n: Int) = SubjectId("doc.$n")
    // The framework owns the residue's address now; the test asks it, rather than inventing one.
    private fun ghostId(s: SubjectId) = ghostElement(s)

    /**
     * The Migration. An empty collection shows its creation control full size in the middle of the
     * space; the first subject sends that control to the corner it will occupy from then on.
     *
     * This is the framework's canonical demonstration that resourceful minimalism buys something
     * real: no empty-state illustration, no paragraph, and the person still learns what the space is
     * for and where the button lives.
     */
    @Test
    fun `the creation control migrates from the centre to its home`() = runComposeUiTest {
        val registry = ElementRegistry()
        val items = mutableStateOf(emptyList<SubjectId>())
        val create = Act.create("doc.new", doc(1), into = list)

        setContent {
            CompositionLocalProvider(
                LocalElements provides registry,
                LocalPractice provides Practice(),
                LocalGhosts provides Ghosts(),
            ) {
                Collection(
                    items = items.value,
                    creator = create,
                    key = { it },
                    modifier = Modifier.size(400.dp),
                    creatorContent = { Box(Modifier.size(64.dp)) },
                    item = { Box(Modifier.width(200.dp).height(40.dp)) },
                )
            }
        }
        waitForIdle()
        val empty = assertNotNull(registry.bounds(create.elementId))

        items.value = listOf(doc(1))
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        val filled = assertNotNull(registry.bounds(create.elementId))

        assertTrue(filled.left > empty.left, "The control should travel toward its corner.")
        assertTrue(filled.top > empty.top, "The control should travel downward to its home.")
        assertTrue(
            filled.width < empty.width,
            "It should shrink into its home rather than stay at invitation size.",
        )
    }

    /**
     * The Ghost's whole claim is that undo lives where the thing was. That is only true if the
     * collection holds the slot open, so this asserts position rather than mere existence.
     */
    @Test
    fun `a destroyed subject leaves its residue in the slot it held`() = runComposeUiTest {
        val registry = ElementRegistry()
        val ghosts = Ghosts()
        val items = mutableStateOf(listOf(doc(1), doc(2), doc(3)))
        val create = Act.create("doc.new", doc(9), into = list)
        val restore = Act.create("doc.restore", doc(2), into = list)
        val delete = Act.destroy("doc.delete", doc(2), target = list, inverse = restore)

        setContent {
            CompositionLocalProvider(
                LocalElements provides registry,
                LocalPractice provides Practice(),
                LocalGhosts provides ghosts,
            ) {
                Collection(
                    items = items.value,
                    creator = create,
                    key = { it },
                    modifier = Modifier.size(400.dp),
                    creatorContent = { Box(Modifier.size(48.dp)) },
                    item = { subject ->
                        Box(Modifier.width(200.dp).height(40.dp).element(ElementId(subject.value)))
                    },
                )
            }
        }
        waitForIdle()
        val firstTop = assertNotNull(registry.bounds(ElementId("doc.1"))).top
        val thirdTop = assertNotNull(registry.bounds(ElementId("doc.3"))).top

        // The subject is destroyed: it leaves the list, and leaves a residue behind.
        runOnUiThread { ghosts.leave(delete, at = list) }
        items.value = listOf(doc(1), doc(3))
        waitForIdle()

        val ghostBounds = assertNotNull(
            registry.bounds(ghostId(doc(2))),
            "The residue must be rendered, not merely recorded.",
        )
        assertTrue(ghostBounds.top > firstTop, "The residue drifted above the slot it held.")
        assertTrue(
            ghostBounds.top < assertNotNull(registry.bounds(ElementId("doc.3"))).top,
            "The residue must sit where its subject was, not be appended at the end.",
        )
        assertTrue(thirdTop > firstTop)
    }

    @Test
    fun `recovering a residue hands back the act that restores the subject`() = runComposeUiTest {
        val ghosts = Ghosts()
        val restore = Act.create("doc.restore", doc(2), into = list)
        val delete = Act.destroy("doc.delete", doc(2), target = list, inverse = restore)

        ghosts.leave(delete, at = list)
        assertTrue(ghosts.holds(doc(2)))

        assertEquals(restore, ghosts.recover(doc(2)), "Undo is an act like any other.")
        assertTrue(!ghosts.holds(doc(2)), "A recovered residue is spent.")
    }

    @Test
    fun `the slot closes once a residue is released`() = runComposeUiTest {
        val registry = ElementRegistry()
        val ghosts = Ghosts()
        val items = mutableStateOf(listOf(doc(1), doc(2)))
        val create = Act.create("doc.new", doc(9), into = list)
        val restore = Act.create("doc.restore", doc(2), into = list)
        val delete = Act.destroy("doc.delete", doc(2), target = list, inverse = restore)

        setContent {
            CompositionLocalProvider(
                LocalElements provides registry,
                LocalPractice provides Practice(),
                LocalGhosts provides ghosts,
            ) {
                Collection(
                    items = items.value,
                    creator = create,
                    key = { it },
                    modifier = Modifier.size(400.dp),
                    creatorContent = { Box(Modifier.size(48.dp)) },
                    item = { Box(Modifier.size(40.dp).element(ElementId(it.value))) },
                )
            }
        }
        waitForIdle()

        runOnUiThread { ghosts.leave(delete, at = list) }
        items.value = listOf(doc(1))
        waitForIdle()
        assertNotNull(registry.bounds(ghostId(doc(2))))

        runOnUiThread { ghosts.release(doc(2)) }
        waitForIdle()
        assertTrue(
            !registry.resolves(ghostId(doc(2))),
            "Once the window closes the slot must close with it, quietly.",
        )
    }
}
