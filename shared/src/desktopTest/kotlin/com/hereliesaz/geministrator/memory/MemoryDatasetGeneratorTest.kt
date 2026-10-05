package com.hereliesaz.geministrator.memory

import java.io.File
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryDatasetGeneratorTest {
    private val rows by lazy { MemoryDatasetGenerator.generate(sessions = 150) }

    @Test
    fun everyGenerativeRoleHasUniqueRowsInEverySplit() {
        MemoryDatasetGenerator.ROLES.forEach { role ->
            val roleRows = rows.getValue(role)
            assertTrue(roleRows.isNotEmpty(), "no rows for $role")
            assertEquals(roleRows.size, roleRows.map { it.id }.distinct().size, "duplicate ids for $role")
            assertEquals(roleRows.size, roleRows.map { it.input }.distinct().size, "duplicate inputs for $role")
            // An empty split cannot gate a clerk: the notebook would have nothing to judge it on.
            val splits = roleRows.groupingBy { it.split }.eachCount()
            listOf("train", "validation", "test", "adversarial").forEach { split ->
                assertTrue((splits[split] ?: 0) > 0, "no $split rows for $role: $splits")
            }
        }
    }

    @Test
    fun theCategoryClerkSeesTheTaxonomyItIsLabelledWith() {
        val guide = memoryCategoryGuide()
        assertTrue("data: database, sql*, postgres*, schema*, migrat*, persist*, serializ*, json" in guide, guide)
        rows.getValue(MemoryMicroAgentRole.CategoryClassifier).forEach { row -> assertTrue(guide in row.input, row.id) }
    }

    @Test
    fun noLabelWritesARelationOnlyTheEngineMayWrite() {
        val engineOnly = MemoryRelationKind.entries.filter { it !in MODEL_WRITABLE_RELATIONS }
        rows.values.flatten().forEach { row ->
            engineOnly.forEach { relation -> assertTrue("\"relation\":\"${relation.name}\"" !in row.expected, "${row.id} writes $relation") }
        }
    }

    @Test
    fun everyRowHasAClassAndClassesAreBalancedWhereShortcutsLurk() {
        rows.values.flatten().forEach { row -> assertTrue(row.tags.any { it.startsWith("class:") }, row.id) }
        // Condensation: clusters that must condense beside clusters that must be kept apart, in
        // training and in the gated splits, so neither "always condense" nor "never" passes.
        val condensation = rows.getValue(MemoryMicroAgentRole.CondensationRewriter)
        listOf("train", "test", "adversarial").forEach { split ->
            val classes = condensation.filter { it.split == split }.map { it.tags.last() }
            assertTrue(classes.any { it == "class:condense" }, "no condense rows in $split")
            assertTrue(classes.any { it.startsWith("class:keep-apart") }, "no keep-apart rows in $split")
        }
        condensation.filter { it.tags.last().startsWith("class:keep-apart") }.forEach { row ->
            assertEquals("{\"sections\":[],\"nodes\":[],\"links\":[]}", row.expected, row.id)
            assertTrue(row.copy != null && row.copy != row.expected, row.id)
        }
    }

    @Test
    fun theSummaryClerkLearnsTheChainsRequestsWithinTheirLimit() {
        val chain = rows.getValue(MemoryMicroAgentRole.SummarySynthesizer).filter { it.tags.first() == "SummaryChain" }
        assertTrue(chain.any { it.split == "train" } && chain.any { it.split == "test" }, "no summary-chain rows: ${chain.groupingBy { it.split }.eachCount()}")
        chain.forEach { row ->
            val limit = Regex("Character limit: (\\d+)\\.").find(row.input)?.groupValues?.get(1)?.toInt()
            assertTrue(limit != null, "${row.id} does not show its limit")
            val node = MemoryDatasetGenerator.json.parseToJsonElement(row.expected).jsonObject.getValue("nodes").jsonArray.single().jsonObject
            assertEquals("Summary", node.getValue("kind").jsonPrimitive.content, row.id)
            assertTrue(node.getValue("text").jsonPrimitive.content.length <= limit!!, "${row.id} is over its limit")
            assertTrue(row.copy != null && row.copy != row.expected, "${row.id}: the copy shortcut must be wrong")
        }
    }

    @Test
    fun generationIsDeterministic() {
        assertEquals(rows, MemoryDatasetGenerator.generate(sessions = 150))
    }

    @Test
    fun inputsArePromptsTheRuntimeRenders() {
        rows.values.flatten().forEach { row ->
            assertTrue(row.input.startsWith("You are a local Haive memory micro-agent."), row.id)
            assertTrue(row.expected.startsWith("{"), row.id)
        }
    }

    /**
     * Opt-in export: `AIVE_EXPORT_MEMORY_DATASETS=<dir> ./gradlew :shared:desktopTest --tests '*MemoryDatasetGeneratorTest'`.
     * `AIVE_MEMORY_DATASET_SESSIONS` overrides the regular sessions; it prints rows per role and split.
     */
    @Test
    fun exportWhenRequested() {
        val target = System.getenv("AIVE_EXPORT_MEMORY_DATASETS")?.takeIf(String::isNotBlank) ?: return
        val root = File(target).apply { mkdirs() }
        val sessions = System.getenv("AIVE_MEMORY_DATASET_SESSIONS")?.toIntOrNull() ?: 600
        val json = MemoryDatasetGenerator.json
        MemoryDatasetGenerator.generate(sessions).forEach { (role, roleRows) ->
            val slug = MemoryDatasetGenerator.slug(role)
            File(root, "$slug.jsonl").writeText(
                roleRows.joinToString("") { json.encodeToString(MemoryDatasetRow.serializer(), it) + "\n" },
            )
            File(root, "$slug.config.json").writeText(
                json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), MemoryDatasetGenerator.config(role)) + "\n",
            )
            println("[memory-dataset] $slug ${roleRows.groupingBy { it.split }.eachCount()}")
        }
        MemoryDatasetGenerator.dropped.forEach { (key, count) -> println("[memory-dataset] ${key.first} ${key.second}: $count") }
    }
}
