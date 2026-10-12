package com.hereliesaz.geministrator.vector

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VectorIndexTest {
    private val dimension = 48

    /** Clustered data, like real embeddings: points scattered around a few centres. */
    private fun dataset(count: Int, seed: Int = 7): List<Pair<String, FloatArray>> {
        val random = Random(seed)
        val centres = List(12) { FloatArray(dimension) { random.nextFloat() * 2 - 1 } }
        return List(count) { i ->
            val centre = centres[i % centres.size]
            "v$i" to FloatArray(dimension) { centre[it] + (random.nextFloat() - 0.5f) * 0.6f }
        }
    }

    private fun queries(count: Int) = dataset(count, seed = 99).map { it.second }

    private fun recallAt10(index: VectorIndex, data: List<Pair<String, FloatArray>>): Double {
        val exact = FlatVectorIndex(dimension).apply { data.forEach { (id, v) -> upsert(id, v) } }
        val qs = queries(40)
        val found = qs.sumOf { q ->
            val truth = exact.search(q, 10).map { it.id }.toSet()
            index.search(q, 10).count { it.id in truth }
        }
        return found.toDouble() / (qs.size * 10)
    }

    @Test
    fun flatIndexReturnsTheExactNearestFirst() {
        val index = FlatVectorIndex(3)
        index.upsert("x", floatArrayOf(1f, 0f, 0f))
        index.upsert("y", floatArrayOf(0f, 1f, 0f))
        index.upsert("xy", floatArrayOf(1f, 1f, 0f))

        val hits = index.search(floatArrayOf(2f, 0.1f, 0f), 2)
        assertEquals(listOf("x", "xy"), hits.map { it.id })
        assertTrue(hits[0].score > 0.99f)
    }

    @Test
    fun filterReplaceAndRemoveBehaveTheSameOnEveryIndex() {
        val indexes = listOf(
            FlatVectorIndex(3),
            QuantizedVectorIndex(3),
            BinaryVectorIndex(3),
            HnswVectorIndex(3, m = 4, efConstruction = 8),
        )
        for (index in indexes) {
            index.upsert("a", floatArrayOf(1f, 0f, 0f))
            index.upsert("b", floatArrayOf(0.9f, 0.1f, 0f))
            index.upsert("c", floatArrayOf(0f, 0f, 1f))
            val name = index::class.simpleName

            assertEquals("b", index.search(floatArrayOf(1f, 0f, 0f), 1) { it != "a" }.single().id, name)

            index.upsert("a", floatArrayOf(0f, 0f, -1f))
            assertEquals(3, index.size, name)
            assertEquals("b", index.search(floatArrayOf(1f, 0f, 0f), 1).single().id, name)

            assertTrue(index.remove("b"), name)
            assertFalse("b" in index, name)
            assertFalse(index.search(floatArrayOf(1f, 0f, 0f), 3).any { it.id == "b" }, name)
            assertTrue(index.search(floatArrayOf(1f, 0f, 0f), 0).isEmpty(), name)
        }
    }

    @Test
    fun approximateIndexesKeepHighRecall() {
        val data = dataset(3_000)
        val hnsw = HnswVectorIndex(dimension).apply { data.forEach { (id, v) -> upsert(id, v) } }
        val quantized = QuantizedVectorIndex(dimension).apply { data.forEach { (id, v) -> upsert(id, v) } }
        val binary = BinaryVectorIndex(dimension).apply { data.forEach { (id, v) -> upsert(id, v) } }

        assertTrue(recallAt10(hnsw, data) >= 0.95, "HNSW recall@10 ${recallAt10(hnsw, data)}")
        assertTrue(recallAt10(quantized, data) >= 0.98, "int8 recall@10 ${recallAt10(quantized, data)}")
        assertTrue(recallAt10(binary, data) >= 0.80, "binary recall@10 ${recallAt10(binary, data)}")
    }

    @Test
    fun hnswIsReproducibleAndCompactsDeletedNodes() {
        val data = dataset(500)
        fun build() = HnswVectorIndex(dimension, seed = 3).apply { data.forEach { (id, v) -> upsert(id, v) } }
        val query = queries(1).single()
        assertEquals(build().search(query, 10), build().search(query, 10))

        val index = build()
        data.take(100).forEach { (id, _) -> index.remove(id) }
        assertEquals(100, index.deletedCount)
        val compacted = index.compact()
        assertEquals(400, compacted.size)
        assertEquals(0, compacted.deletedCount)
        assertTrue(compacted.search(query, 10).none { it.id in data.take(100).map { (id, _) -> id } })
    }

    @Test
    fun codecRoundTripsVectors() {
        val vector = floatArrayOf(0.25f, -1.5f, 3f, 0f)
        assertContentEquals(vector, VectorCodec.decode(VectorCodec.encode(vector)))

        val quantized = VectorCodec.decode(VectorCodec.encode(vector, VectorCodec.Format.Int8))
        val unit = normalized(vector)
        unit.indices.forEach { assertTrue(kotlin.math.abs(unit[it] - quantized[it]) < 0.01f) }
    }
}

class BuiltInTextEmbedderTest {
    private val embedder = BuiltInTextEmbedder(dimension = 256)

    private fun similarity(a: String, b: String): Float = dot(embedder.embedOne(a), embedder.embedOne(b))

    @Test
    fun relatedTextScoresAboveUnrelatedText() {
        val related = similarity("Chose Postgres for the workflow database", "workflow database uses Postgres")
        val unrelated = similarity("Chose Postgres for the workflow database", "the splash screen animation stutters")
        assertTrue(related > unrelated + 0.3f, "related=$related unrelated=$unrelated")
    }

    @Test
    fun identifiersSplitIntoWordsAndInflectionsOverlap() {
        assertEquals(listOf("sql", "workflow", "persistence", "store", "path"), BuiltInTextEmbedder.tokens("SQLWorkflowPersistence store_path"))
        assertTrue(similarity("retrying failed builds", "retry the failed build") > 0.35f)
    }

    @Test
    fun conceptsLinkWordsThatShareNoSpelling() {
        val concepts = mapOf("fix" to intArrayOf(7), "repair" to intArrayOf(7))
        val withConcepts = BuiltInTextEmbedder(dimension = 256) { concepts[it] ?: IntArray(0) }
        val plain = dot(embedder.embedOne("fix crash"), embedder.embedOne("repair crash"))
        val conceptual = dot(withConcepts.embedOne("fix crash"), withConcepts.embedOne("repair crash"))
        assertTrue(conceptual > plain, "conceptual=$conceptual plain=$plain")
        assertEquals("builtin-hash-wordnet", withConcepts.id)
    }

    @Test
    fun embeddingIsDeterministicAndEmptyTextIsZero() {
        assertContentEquals(embedder.embedOne("same text"), embedder.embedOne("same text"))
        assertTrue(embedder.embedOne("the and of").all { it == 0f })
    }
}
