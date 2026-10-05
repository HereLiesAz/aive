package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryTagCloudTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val wordNet = WordNetLexicon.parse(
        MemoryLanguageResources.gunzip(File(root, "shared/src/commonMain/composeResources/files/aive-wordnet-v1.txt.gz").readBytes()),
    )

    private fun cloud(text: String, kind: MemoryNodeKind = MemoryNodeKind.NounTag) = MemoryTagCloud.cloudFor(text, kind, wordNet).toMap()

    @Test
    fun synonymsDerivationsAndHyponymsAreWeighted() {
        assertEquals(1.0f, cloud("start", MemoryNodeKind.VerbTag)["begin"])
        val database = cloud("database")
        assertTrue(database.any { (_, weight) -> weight == MemoryTagCloud.WEIGHTS.hyponym }, "database has a narrower term: $database")
        assertEquals(MemoryTagCloud.WEIGHTS.hypernym, database["information"] ?: database["info"])
        assertEquals(MemoryTagCloud.WEIGHTS.sibling, cloud("red")["blue"])
        assertEquals(1.0f, cloud("automobile")["car"])
    }

    @Test
    fun cloudsAreDeterministicAndCapped() {
        val first = MemoryTagCloud.cloudFor("change", MemoryNodeKind.VerbTag, wordNet)
        assertEquals(first, MemoryTagCloud.cloudFor("change", MemoryNodeKind.VerbTag, wordNet))
        assertEquals(MemoryTagCloud.MAX_TERMS, first.size)
        assertEquals(first.sortedByDescending { it.second }.map { it.second }, first.map { it.second })
        val encoded = MemoryTagCloud.encode(first)
        assertEquals(first.map { it.first }, MemoryTagCloud.decode(encoded).map { it.first })
    }

    @Test
    fun unknownWordsGetThemselvesAndTheirAliases() {
        assertEquals(mapOf("zorbulator" to 1.0f), cloud("zorbulator"))
        assertEquals(setOf("postgres", "postgresql", "psql", "pgsql"), cloud("postgres").keys)
        assertEquals(setOf("postgres", "postgresql", "psql", "pgsql"), MemoryTagCloud.cloudFor("postgres", MemoryNodeKind.NounTag, null).map { it.first }.toSet())
    }

    private fun tag(id: String, text: String, memory: String) = MemoryTagCloud.withClouds(
        listOf(MemoryNode(MemoryNodeId(id), MemoryNodeKind.NounTag, text, createdAtEpochMillis = 0)),
        wordNet,
    ).single() to MemoryEdge(MemoryEdgeId("$id-$memory"), MemoryNodeId(id), MemoryNodeId(memory), MemoryRelationKind.Indexes, 1f, 0)

    private fun tool(): GraphMemoryTool {
        val (car, carEdge) = tag("tag-automobile", "automobile", "m-car")
        val (red, redEdge) = tag("tag-red", "red", "m-red")
        val memories = listOf(
            MemoryNode(MemoryNodeId("m-car"), MemoryNodeKind.Context, "Parked the automobile in the garage", createdAtEpochMillis = 0),
            MemoryNode(MemoryNodeId("m-red"), MemoryNodeKind.Context, "Painted the fence red", createdAtEpochMillis = 0),
        )
        return GraphMemoryTool(InMemoryMemoryStore(MemorySnapshot(nodes = memories + car + red, edges = listOf(carEdge, redEdge))))
    }

    @Test
    fun aSynonymFindsTheTaggedMemoryAndASiblingOnlyAtAnIntrusiveDial() = runBlocking {
        val tool = tool()
        val car = tool.grip(MemoryQuery("car", resolution = MemoryResolution.Context)).hits.singleOrNull { it.node.id.value == "m-car" }
        assertNotNull(car, "a memory tagged automobile is found from car")
        val blue = tool.grip(MemoryQuery("blue", resolution = MemoryResolution.Context)).hits.singleOrNull { it.node.id.value == "m-red" }
        assertNotNull(blue, "a sibling relation still reaches the memory, weakly")
        assertTrue(blue.score < car.score, "sibling ${blue.score} ranks below synonym ${car.score}")

        val middle = AttentionGatedRecall(initialState = MemoryAttentionState(baselineLevel = 0.5f))
        assertEquals(1, middle.selectAlongside(listOf(car)).size, "a synonym clears the middle dial")
        assertTrue(middle.selectAlongside(listOf(blue)).isEmpty(), "a sibling does not clear the middle dial")
        val intrusive = AttentionGatedRecall(initialState = MemoryAttentionState(baselineLevel = 1f))
        assertEquals(1, intrusive.selectAlongside(listOf(blue)).size, "a sibling clears an intrusive dial")
    }

    @Test
    fun explicitTagsAndTheFrequencyFilterSeeClouds() = runBlocking {
        val tool = tool()
        assertTrue(tool.grip(MemoryTagQuery(tags = listOf("car"))).hits.none { it.node.id.value == "tag-automobile" }, "explicit #tags do not read clouds")
        assertEquals(2, tool.termFrequency("car").memories, "a cloud term counts the tag and its memory")
    }
}
