package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.vector.BuiltInTextEmbedder
import com.hereliesaz.geministrator.vector.space
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class ModelTextEmbedderTest {
    /** Database texts share one direction, everything else another; [working] toggles failure. */
    private class FakeModel {
        var working = true
        var calls = 0
        suspend fun embed(texts: List<String>): List<List<Float>> {
            calls++
            check(working) { "model not installed" }
            return texts.map { if ("Postgres" in it || "datastore" in it) listOf(3f, 0f) else listOf(0f, 2f) }
        }
    }

    private val fallback = BuiltInTextEmbedder(dimension = 64)

    @Test
    fun usesTheModelSpaceAndNormalizesItsVectors() = runBlocking {
        val model = FakeModel()
        val embedder = ModelTextEmbedder("minilm", model::embed, fallback)

        val vectors = embedder.embed(listOf("Chose Postgres", "", "splash"))

        assertTrue(embedder.usingModel)
        assertEquals("model:minilm", embedder.id)
        assertEquals(2, embedder.dimension)
        assertEquals(listOf(1f, 0f), vectors[0].toList())
        assertEquals(listOf(0f, 0f), vectors[1].toList(), "blank text is the zero vector, never sent to the model")
        assertEquals(listOf(0f, 1f), vectors[2].toList())
    }

    @Test
    fun fallsBackWhenTheModelFailsAndRetriesLater() = runBlocking {
        val model = FakeModel().apply { working = false }
        val clock = TestTimeSource()
        val embedder = ModelTextEmbedder("minilm", model::embed, fallback, retryAfter = 5.minutes, timeSource = clock)

        val fellBack = embedder.embed(listOf("Chose Postgres"))
        assertFalse(embedder.usingModel)
        assertEquals(fallback.space, embedder.space)
        assertEquals(fallback.dimension, fellBack.single().size)

        model.working = true
        embedder.embed(listOf("Chose Postgres"))
        assertEquals(1, model.calls, "no retry before the interval")

        clock += 5.minutes
        embedder.embed(listOf("Chose Postgres"))
        assertTrue(embedder.usingModel, "an installed model is picked up on retry")
        assertEquals(2, model.calls)
    }

    @Test
    fun recallSurvivesTheSpaceChanging() = runBlocking {
        val model = FakeModel()
        val clock = TestTimeSource()
        val embedder = ModelTextEmbedder("minilm", model::embed, fallback, retryAfter = 5.minutes, timeSource = clock)
        val store = InMemoryMemoryStore().apply {
            commit(
                0,
                MemoryStoreMutation(
                    nodesToAdd = listOf(
                        MemoryNode(MemoryNodeId("db"), MemoryNodeKind.Context, "Picked a relational datastore.", createdAtEpochMillis = 1L),
                        MemoryNode(MemoryNodeId("ui"), MemoryNodeKind.Context, "Splash animation stutters.", createdAtEpochMillis = 1L),
                    ),
                ),
            )
        }
        val tool = GraphMemoryTool(store, embedder = embedder)
        val query = MemoryQuery("Chose Postgres", resolution = MemoryResolution.Context)

        val withModel = tool.grip(query).hits.map { it.node.id.value }
        assertTrue("db" in withModel, "meaning-only match with the model: $withModel")
        val modelSpace = embedder.space

        // The model disappears: recall keeps working, in the fallback space, without mixing widths.
        model.working = false
        clock += 5.minutes
        tool.grip(query)
        tool.grip(query)
        assertNotEquals(modelSpace, embedder.space)

        // And comes back.
        model.working = true
        clock += 5.minutes
        tool.grip(query)
        val again = tool.grip(query).hits.map { it.node.id.value }
        assertEquals(modelSpace, embedder.space)
        assertTrue("db" in again, "meaning-only match after the model returns: $again")
    }
}
