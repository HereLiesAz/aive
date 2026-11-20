package com.hereliesaz.geministrator.retrieval

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskResource
import com.hereliesaz.geministrator.domain.TaskResources
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.orchestration.resourcesFor
import com.hereliesaz.geministrator.orchestration.stepsMissingAiveDocs
import com.hereliesaz.geministrator.orchestration.worksFromLine
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.workflow.OrchestratedWorkflowFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskResourcesTest {
    private fun obj(kind: String, key: String, vararg items: String): JsonObject = buildJsonObject {
        put("kind", kind)
        put(key, JsonArray(items.map { JsonPrimitive(it) }))
    }

    private fun request(
        objective: String,
        resources: List<TaskResource> = emptyList(),
        artifacts: List<ArtifactRef> = emptyList(),
        runs: Set<TaskRunId>? = null,
    ) = AgentTaskRequest(
        taskRunId = TaskRunId("t"),
        objective = objective,
        roleInstructions = "",
        acceptanceCriteria = emptyList(),
        contextArtifacts = artifacts,
        resources = resources,
        resourceTaskRuns = runs,
    )

    // Parsing and bounds ---------------------------------------------------------------------------------------------

    @Test
    fun theThreeKindsAreParsedAndUnknownOnesAreDropped() {
        val parsed = TaskResources.fromJson(
            listOf(
                obj("aive_docs", "topics", "hives", "queen"),
                obj("repository_files", "paths", "docs/*.md"),
                obj("upstream_artifacts", "fromTasks", "a", "ghost"),
                obj("web_search", "queries", "x"),
                JsonPrimitive("not an object"),
            ),
        ) { id -> if (id == "a") TaskDefinitionId("a") else null }
        assertEquals(
            listOf(
                TaskResource.AiveDocs(listOf("hives", "queen")),
                TaskResource.RepositoryFiles(listOf("docs/*.md")),
                TaskResource.UpstreamArtifacts(listOf(TaskDefinitionId("a"))),
            ),
            parsed,
        )
    }

    @Test
    fun itemsAreCappedInCountAndLengthAndDuplicateKindsAreOne() {
        val many = (1..20).map { "topic$it" }.toTypedArray()
        val long = "x".repeat(500)
        val parsed = TaskResources.fromJson(
            listOf(obj("aive_docs", "topics", *many), obj("aive_docs", "topics", "again"), obj("repository_files", "paths", long, "  ")),
        ) { null }
        val docs = parsed.filterIsInstance<TaskResource.AiveDocs>().single()
        assertEquals(TaskResourceLimitsForTest.items, docs.topics.size)
        val files = parsed.filterIsInstance<TaskResource.RepositoryFiles>().single()
        assertEquals(listOf("x".repeat(TaskResourceLimitsForTest.chars)), files.paths)
        assertTrue(parsed.size <= TaskResourceLimitsForTest.resources)
    }

    @Test
    fun aFileListWithNoPathsIsDroppedAndNothingThrows() {
        assertEquals(emptyList(), TaskResources.fromJson(listOf(obj("repository_files", "paths")), { null }))
        assertEquals(emptyList(), TaskResources.fromJson(listOf(buildJsonObject { put("kind", 7) }), { null }))
        assertEquals(emptyList(), TaskResources.fromJson(emptyList(), { null }))
    }

    @Test
    fun aTaskDefinitionWrittenBeforeResourcesStillReads() {
        val old = """{"id":{"value":"a"},"name":"A","objective":"do it","roleId":null}"""
        val task = Json.decodeFromString(TaskDefinition.serializer(), old)
        assertEquals(emptyList(), task.resources)
        val withResources = task.copy(resources = listOf(TaskResource.AiveDocs(listOf("queen")), TaskResource.RepositoryFiles(listOf("README.md"))))
        val again = Json.decodeFromString(TaskDefinition.serializer(), Json.encodeToString(TaskDefinition.serializer(), withResources))
        assertEquals(withResources.resources, again.resources)
    }

    // The plan line and the reviewer ---------------------------------------------------------------------------------

    private fun step(id: String, objective: String, resources: List<kotlinx.serialization.json.JsonElement> = emptyList()) =
        OrchestrationPlanStep(id, id, objective, "researcher", resources = resources)

    @Test
    fun theKeeperAndReviewerReadOneWorksFromLinePerCreature() {
        val a = step("a", "Survey the notes")
        val b = step("b", "Explain how hives work", listOf(obj("aive_docs", "topics", "hives", "queen"), obj("upstream_artifacts", "fromTasks", "a")))
        val plan = OrchestrationPlan(listOf(a, b))
        assertNull(plan.worksFromLine(a))
        assertEquals("Works from: the Aive's docs (hives, queen); step a's output", plan.worksFromLine(b))
        assertEquals(listOf(TaskResource.AiveDocs(listOf("hives", "queen")), TaskResource.UpstreamArtifacts(listOf(TaskDefinitionId("a")))), plan.resourcesFor(b))
    }

    @Test
    fun aStepThatNeedsAppFactsButListsNoDocsIsFlaggedForTheReviewer() {
        val bare = step("x", "Explain how the queen drafts a hive")
        val listed = step("y", "Explain how the queen drafts a hive", listOf(obj("aive_docs", "topics", "queen")))
        val coding = step("z", "Fix the login bug in the parser")
        assertEquals(listOf("x"), OrchestrationPlan(listOf(bare, listed, coding)).stepsMissingAiveDocs().map { it.id })
    }

    @Test
    fun theReviewPromptShowsWhatEachCreatureWorksFromAndAsksForDocsWhereAppFactsAreNeeded() {
        val plan = OrchestrationPlan(
            listOf(
                step("x", "Explain how the queen drafts a hive"),
                step("y", "Explain hives", listOf(obj("aive_docs", "topics", "hives"))),
            ),
        )
        val packet = com.hereliesaz.geministrator.orchestration.OrchestrationPacket(objective = "o", availableAgents = emptyList(), instruction = "i")
        val prompt = com.hereliesaz.geministrator.orchestration.planReviewPrompt(
            com.hereliesaz.geministrator.orchestration.PlanReviewRequest(packet, plan, "Reviewer", "Check it.", null),
        )
        assertTrue("Works from: the Aive's docs (hives)" in prompt, prompt)
        assertTrue("must list the Aive's docs" in prompt, prompt)
        assertTrue("list no docs: x." in prompt, prompt)
    }

    @Test
    fun everyPlanInstructionAsksForResources() {
        assertTrue("aive_docs" in TaskResources.PLAN_INSTRUCTION && "repository_files" in TaskResources.PLAN_INSTRUCTION)
        assertTrue("aive_docs" in com.hereliesaz.geministrator.swarmDraftPacket("audit the repo", emptyList()).instruction)
        assertTrue("aive_docs" in com.hereliesaz.geministrator.newWorkflowInstruction("Fix the login bug", null))
    }

    // The tutorial safety net ----------------------------------------------------------------------------------------

    private fun task(id: String, role: String, resources: List<TaskResource> = emptyList()) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "o",
        roleId = com.hereliesaz.geministrator.domain.RoleDefinitionId(role),
        resources = resources,
    )

    @Test
    fun inASwarmThatTeachesTheAppOnlyAReviewingCreatureIsOptional() {
        assertTrue(OrchestratedWorkflowFactory.teachingReview("Teach me how to use the Aive", "reviewer"))
        assertTrue(OrchestratedWorkflowFactory.teachingReview("Teach me how to use the Aive", "adversarial-reviewer"))
        assertFalse(OrchestratedWorkflowFactory.teachingReview("Teach me how to use the Aive", "researcher"))
        assertFalse(OrchestratedWorkflowFactory.teachingReview("Fix the login bug", "reviewer"))
    }

    @Test
    fun aTutorialPlanWithNoDocsGetsThemOnItsFirstResearchLikeCreature() {
        val tasks = listOf(task("plan", "planner"), task("look", "researcher"), task("write", "writer"))
        val out = OrchestratedWorkflowFactory.withTutorialDocs("Teach me how to use the Aive", tasks)
        assertEquals(listOf(emptyList(), listOf(TaskResource.AiveDocs()), emptyList()), out.map { it.resources })
    }

    @Test
    fun anOrdinaryPlanOrOneThatAlreadyHasDocsIsLeftAlone() {
        val tasks = listOf(task("look", "researcher"))
        assertEquals(tasks, OrchestratedWorkflowFactory.withTutorialDocs("Fix the login bug", tasks))
        val assigned = listOf(task("look", "researcher", listOf(TaskResource.AiveDocs(listOf("queen")))))
        assertEquals(assigned, OrchestratedWorkflowFactory.withTutorialDocs("Teach me how to use the Aive", assigned))
    }

    // Retrieval: per task, never global ------------------------------------------------------------------------------

    private val docs = mapOf(
        "docs/Queen.md" to "# The queen\n\nThe queen plans a hive and picks creatures for it.\n\n## Sources\n\nA source is a model the queen can ask.",
        "docs/Weather.md" to "# Weather\n\nRain falls on the garden in spring.\n",
        "README.md" to "Intro text before any heading.\n\n# Aive\n\nAn app for hives.\n\n```\n# not a heading\n```\n",
    )

    @Test
    fun docsSplitOnHeadingsWithinTheBoundAndLabelledByFileAndHeading() {
        val big = "# Big\n\n" + (1..200).joinToString("\n\n") { "Paragraph number $it says something about hives and creatures." }
        val chunks = AiveDocsChunkSource.splitDocs(docs + ("docs/Big.md" to big))
        assertTrue(chunks.all { it.text.length <= AiveDocsChunkSource.DEFAULT_MAX_CHARS }, "a chunk is too long")
        assertTrue(chunks.count { it.label == "docs/Big.md > Big" } > 1)
        assertTrue("docs/Queen.md > The queen" in chunks.map { it.label })
        assertTrue("docs/Queen.md > Sources" in chunks.map { it.label })
        assertTrue("README.md" in chunks.map { it.label }, "text before the first heading is labelled by file")
        assertFalse(chunks.any { it.label.endsWith("not a heading") })
        assertEquals(chunks.size, chunks.map { it.id }.toSet().size)
    }

    @Test
    fun aTaskWithNoResourcesGetsNoDocsAndSoDoesACodingTask() = runTest {
        val source = AiveDocsChunkSource({ docs })
        assertEquals(emptyList(), source.chunks(request("Fix the login bug")))
        assertEquals(emptyList(), source.chunks(request("Explain how the queen picks creatures")))
    }

    @Test
    fun anAssignedTaskGetsTheDocsNarrowedByItsTopics() = runTest {
        val source = AiveDocsChunkSource({ docs })
        val all = source.chunks(request("anything", listOf(TaskResource.AiveDocs())))
        assertTrue(all.map { it.label }.any { it.startsWith("docs/Weather.md") })
        val narrowed = source.chunks(request("anything", listOf(TaskResource.AiveDocs(listOf("queen")))))
        assertTrue(narrowed.isNotEmpty() && narrowed.all { "queen" in (it.label + it.text).lowercase() })
    }

    @Test
    fun rankingPutsTheRelevantSectionFirst() = runTest {
        val source = AiveDocsChunkSource({ docs })
        val retriever = SimilarityContextRetriever(listOf(source), maxChunks = 1)
        val hit = retriever.retrieve(request("how does the queen plan a hive and pick creatures", listOf(TaskResource.AiveDocs()))).single()
        assertTrue(hit.chunk.label.startsWith("docs/Queen.md"), hit.chunk.label)
    }

    @Test
    fun aSourceThatFailsOrIsGivenNothingContributesNothing() = runTest {
        val failing = AiveDocsChunkSource({ error("no bundle") })
        assertEquals(emptyList(), failing.chunks(request("x", listOf(TaskResource.AiveDocs()))))
        assertEquals(emptyList(), AiveDocsChunkSource({ emptyMap() }).chunks(request("x", listOf(TaskResource.AiveDocs()))))
        assertEquals(emptyList(), AiveDocsChunkSource.splitDocs(mapOf("a.md" to "")))
    }

    @Test
    fun repositoryFilesNarrowToTheNamedPathsAndOnlyForATaskThatHasThem() = runTest {
        val inner = ContextChunkSource {
            listOf("src/a.kt", "docs/x.md", "docs/y.md", "README.md").map { TextChunk("id-$it", "Repository file $it", "text of $it") }
        }
        val narrowed = RepositoryFilesNarrowedSource(inner)
        assertEquals(4, narrowed.chunks(request("go")).size)
        val got = narrowed.chunks(request("go", listOf(TaskResource.RepositoryFiles(listOf("docs/*.md", "README.md")))))
        assertEquals(setOf("docs/x.md", "docs/y.md", "README.md"), got.map { RepositoryFilesNarrowedSource.pathOf(it.label) }.toSet())
    }

    @Test
    fun upstreamArtifactsNarrowToTheNamedRunsAndOtherwiseNothingIsDropped() = runTest {
        fun artifact(id: String, run: String) = ArtifactRef(
            id = ArtifactId(id), kind = ArtifactKind.Research, taskRunId = TaskRunId(run), label = id, textContent = "text of $id", createdAtEpochMillis = 0,
        )
        val arts = listOf(artifact("one", "r1"), artifact("two", "r2"))
        assertEquals(2, ArtifactChunkSource.chunks(request("go", artifacts = arts)).size)
        val only = ArtifactChunkSource.chunks(request("go", artifacts = arts, runs = setOf(TaskRunId("r2"))))
        assertEquals(listOf("Artifact: two"), only.map { it.label })
    }
}

/** The limits as the tests read them, so a change to a bound is a visible change here. */
private object TaskResourceLimitsForTest {
    val resources = com.hereliesaz.geministrator.domain.TaskResourceLimits.MAX_RESOURCES
    val items = com.hereliesaz.geministrator.domain.TaskResourceLimits.MAX_ITEMS
    val chars = com.hereliesaz.geministrator.domain.TaskResourceLimits.MAX_CHARS
}
