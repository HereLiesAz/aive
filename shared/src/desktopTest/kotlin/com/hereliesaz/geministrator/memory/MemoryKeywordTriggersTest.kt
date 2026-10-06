package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.providers.AgentOrchestrationContext
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.workflow.ManagedSessionHandle
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryKeywordTriggersTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val wordNet = WordNetLexicon.parse(
        MemoryLanguageResources.gunzip(File(root, "shared/src/commonMain/composeResources/files/aive-wordnet-v1.txt.gz").readBytes()),
    )

    private fun tag(id: String, text: String) = MemoryTagKeywords.withKeywords(
        listOf(MemoryNode(MemoryNodeId(id), MemoryNodeKind.NounTag, text, createdAtEpochMillis = 0)),
        wordNet,
    ).single()

    private fun snapshot(vararg tags: Pair<String, String>): MemorySnapshot {
        val nodes = tags.flatMap { (id, text) ->
            listOf(tag(id, text), MemoryNode(MemoryNodeId("m-$id"), MemoryNodeKind.Context, "Something about $text", createdAtEpochMillis = 0))
        }
        val edges = tags.map { (id, _) -> MemoryEdge(MemoryEdgeId("$id-m"), MemoryNodeId(id), MemoryNodeId("m-$id"), MemoryRelationKind.Indexes, 1f, 0) }
        return MemorySnapshot(nodes = nodes, edges = edges)
    }

    @Test
    fun theTableIsBuiltFromStoredKeywordsAndUpdatedOnCommit() = runBlocking {
        val triggers = MemoryKeywordTriggers()
        val store = KeywordTriggeringStore(InMemoryMemoryStore(snapshot("tag-automobile" to "automobile")), triggers)
        triggers.ensureCurrent(store.read())
        assertEquals(1.0f, triggers.match(listOf("car")).getValue(MemoryNodeId("tag-automobile")).weight)
        assertTrue("automobile" in triggers.keywords(), "the tag's own text is a keyword")
        assertTrue(triggers.keywords().any { ' ' in it }, "multi-word lemmas are phrase keys")
        assertTrue(triggers.match(listOf("motorcar")).isNotEmpty())

        // A committed tag is in the table at once, without a rebuild.
        val before = store.read().revision
        assertTrue(store.commit(before, MemoryStoreMutation(nodesToAdd = listOf(tag("tag-ice-cream", "ice cream")))))
        assertTrue(triggers.match(listOf("ice cream")).containsKey(MemoryNodeId("tag-ice-cream")), "phrase keys match")
        triggers.ensureCurrent(store.read())
        assertTrue(triggers.match(listOf("ice cream")).containsKey(MemoryNodeId("tag-ice-cream")))

        val keys = MemoryKeywordTriggers.keys("I bought an ice cream cone") { it == "an" }
        assertTrue("ice cream" in keys && "ice cream cone" in keys && "an ice cream" in keys && "an" !in keys, "$keys")
    }

    private suspend fun cue(message: String, level: Float, vararg tags: Pair<String, String>): String {
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf("wf-kw" to InMemoryMemoryStore(snapshot(*tags)))),
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 1_000L },
        )
        controller.setDefaultAttentionLevel(level)
        val handle = ManagedSessionHandle(TaskRunId("agent-kw"), AgentProviderId("p"), ProviderRunId("r"))
        controller.observer.onSessionStarted(
            handle,
            AgentTaskRequest(TaskRunId("agent-kw"), "Work", "", emptyList(), orchestrationContext = AgentOrchestrationContext(workflowRunId = WorkflowRunId("wf-kw"))),
        )
        return controller.observer.annotateUserMessage(handle, message)
    }

    @Test
    fun aKeywordFiresItsTagWithoutTheTagSearch() = runBlocking {
        // "car" is too short to be a searched cue word, so only the trigger table can fire the tag.
        val annotated = cue("Where is the car?", 0.5f, "tag-automobile" to "automobile")
        assertTrue(annotated.endsWith("$MEMORY_MESSAGE_MARKER #automobile"), annotated)
        assertFalse("#car" in annotated, "the tag is delivered, never the keyword: $annotated")
    }

    @Test
    fun aSiblingKeywordIsGatedByTheDial() = runBlocking {
        assertTrue("#red" in cue("Paint it blue", 1f, "tag-red" to "red"), "a sibling passes an intrusive dial")
        assertFalse("#red" in cue("Paint it blue", 0.5f, "tag-red" to "red"), "a sibling does not pass the middle dial")
    }

    @Test
    fun explicitTagQueriesDoNotReadKeywords() = runBlocking {
        val tool = GraphMemoryTool(InMemoryMemoryStore(snapshot("tag-automobile" to "automobile")))
        assertTrue(tool.grip(MemoryTagQuery(tags = listOf("car"))).hits.none { it.node.id.value == "tag-automobile" })
        assertTrue(tool.grip(MemoryTagQuery(tags = listOf("automobile"))).hits.any { it.node.id.value == "tag-automobile" })
    }
}
