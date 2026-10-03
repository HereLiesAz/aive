package com.hereliesaz.geministrator.memory

import java.io.File
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
