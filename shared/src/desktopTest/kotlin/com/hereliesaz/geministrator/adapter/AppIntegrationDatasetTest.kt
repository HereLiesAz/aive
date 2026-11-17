package com.hereliesaz.geministrator.adapter

import com.hereliesaz.geministrator.memory.relevantMemoryText
import com.hereliesaz.geministrator.providers.OnDeviceTextAgentProvider
import com.hereliesaz.geministrator.providers.OnDeviceTextGeneration
import com.hereliesaz.geministrator.providers.OnDeviceTextGenerator
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppIntegrationDatasetTest {
    private val rowsPerFamily = System.getenv(ROWS_ENV)?.toIntOrNull() ?: AppIntegrationDatasetGenerator.DEFAULT_ROWS_PER_FAMILY
    private val built: List<BuiltRow> by lazy { AppIntegrationDatasetGenerator.built(rowsPerFamily) }
    private val rows: List<AppIntegrationRow> get() = built.map { it.row }

    @Test
    fun splitsAreByDomainAndRoughlySeventyFifteenFifteen() {
        val expectedDomains = mapOf(
            "train" to setOf("mobile", "web", "backend", "tooling"),
            "validation" to setOf("data pipelines"),
            "test" to setOf("games", "infrastructure", "research", "embedded"),
            "adversarial" to setOf("adversarial"),
        )
        expectedDomains.forEach { (split, domains) ->
            val seen = rows.filter { it.split == split }.map { it.domain }.toSet()
            assertTrue(domains.containsAll(seen) && seen.isNotEmpty(), "domains of $split: $seen")
        }
        AppIntegrationDatasetGenerator.FAMILIES.forEach { family ->
            val regular = rows.filter { it.family == family && it.split != "adversarial" }
            assertEquals(rowsPerFamily, regular.size, "regular rows of $family")
            assertEquals(rowsPerFamily * 70 / 100, regular.count { it.split == "train" })
            assertEquals(rowsPerFamily * 15 / 100, regular.count { it.split == "validation" })
        }
    }

    @Test
    fun adversarialSplitCoversEveryFamily() {
        val adversarial = rows.filter { it.split == "adversarial" }
        assertTrue(adversarial.size >= 30, "only ${adversarial.size} adversarial rows")
        AppIntegrationDatasetGenerator.FAMILIES.forEach { family ->
            val count = adversarial.count { it.family == family }
            assertTrue(count >= if (family == "F6") 8 else 5, "$family has $count adversarial rows")
        }
    }

    @Test
    fun generationIsDeterministic() {
        assertEquals(AppIntegrationDatasetGenerator.generate(60), AppIntegrationDatasetGenerator.generate(60))
    }

    @Test
    fun noDuplicateIdsOrInputsAcrossSplits() {
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        assertEquals(rows.size, rows.map { it.user }.toSet().size, "a duplicate input would leak test rows into training")
    }

    /** The corpus is only useful if the model sees exactly the bytes the app sends. */
    @Test
    fun inputsAreByteIdenticalToWhatTheRealProviderSends() = runBlocking<Unit> {
        built.forEach { b ->
            val seen = ArrayList<Triple<String, String, Int>>()
            val generator = object : OnDeviceTextGenerator {
                override fun isReady(): Boolean = true
                override suspend fun generate(system: String, user: String, maxNewTokens: Int): OnDeviceTextGeneration {
                    seen += Triple(system, user, maxNewTokens)
                    return OnDeviceTextGeneration(b.row.expected)
                }
            }
            val provider = OnDeviceTextAgentProvider(generator)
            if (b.planOnly) {
                provider.draftPlan(b.request)
            } else {
                val handle = provider.start(b.request)
                provider.observe(handle.providerRunId).toList()
            }
            val call = seen.single()
            assertEquals(OnDeviceTextAgentProvider.SYSTEM_PROMPT, call.first, "system prompt of ${b.row.id}")
            assertEquals(b.row.system, call.first)
            assertEquals(b.row.user, call.second, "user text of ${b.row.id}")
            assertEquals(OnDeviceTextAgentProvider.DEFAULT_MAX_NEW_TOKENS, call.third)
        }
    }

    @Test
    fun memoryAndHabitTextIsTheRealRendererOutput() {
        built.forEach { b ->
            if (b.recallHits.isNotEmpty()) {
                assertTrue(b.row.user.contains(relevantMemoryText(b.recallHits)), "recall block of ${b.row.id}")
            }
            b.habitBlock?.let { assertTrue(b.row.user.contains(it), "habit block of ${b.row.id}") }
        }
    }

    @Test
    fun inputsFitTheOnDeviceBudget() {
        rows.forEach {
            assertTrue(
                OnDeviceTextAgentProvider.estimateTokens(it.user.length) <= OnDeviceTextAgentProvider.DEFAULT_MAX_INPUT_TOKENS,
                "${it.id} is too long to be routed on device",
            )
        }
    }

    @Test
    fun everyExpectedAnswerPassesItsOwnVerifierAndChecks() {
        rows.forEach { row ->
            val named = AppIntegrationVerifiers.verify(row.verifier, row.check, row.expected)
            assertTrue(named.ok, "${row.id} (${row.sub}) fails ${row.verifier}: ${named.reason}\n${row.expected}")
            val declared = AppIntegrationChecks.evaluate(row.checks, row.expected)
            assertTrue(declared.ok, "${row.id} (${row.sub}) fails its declarative checks: ${declared.reason}\n${row.expected}")
        }
    }

    /** The declarative checks (what Python scores) must agree with the Kotlin verifiers on wrong answers too. */
    @Test
    fun declarativeChecksAgreeWithVerifiersOnFailingPredictions() {
        rows.forEach { row ->
            val e = row.expected
            val predictions = listOf(
                "", "```\n$e\n```", "Sure, here is the answer: $e", "$e I ran the tests and they passed.", "I read the file. $e",
                e.uppercase(), e.lowercase(), e.replace(Regex("\\d"), "7"), e.take(e.length / 2), "/remindme\n$e", "$e\n/remindme",
                "$e\n/forget", e.lines().reversed().joinToString("\n"), NOT_IN_TEXT, "Memory holds two versions. Both are fine, definitely.",
                e.replace(Regex("^\\d+\\. ", RegexOption.MULTILINE), ""),
            )
            predictions.forEach { p ->
                val named = AppIntegrationVerifiers.verify(row.verifier, row.check, p)
                val declared = AppIntegrationChecks.evaluate(row.checks, p)
                assertEquals(named.ok, declared.ok, "${row.id} (${row.verifier}) disagrees on: $p (named: ${named.reason}; declared: ${declared.reason})")
            }
        }
    }

    @Test
    fun everyRowKeepsTheCacheLayout() {
        rows.forEach { row ->
            val v = AppIntegrationLayout.check(row.system, row.user)
            assertTrue(v.ok, "${row.id}: ${v.reason}")
        }
    }

    @Test
    fun cachingGroupsShareAByteIdenticalStablePrefix() {
        val groups = rows.filter { it.family == "F7" }.groupBy { it.split + "/" + it.group }
        assertTrue(groups.isNotEmpty())
        groups.forEach { (name, members) ->
            assertEquals(1, members.map { AppIntegrationLayout.prefixOf(it.user) }.toSet().size, "prefix differs in $name")
            assertEquals(1, members.map { it.system }.toSet().size)
            assertTrue(members.size >= 2, "group $name is too small")
            assertTrue(members.map { it.user }.toSet().size == members.size, "suffixes repeat in $name")
        }
    }

    @Test
    fun everyRowCarriesChecksAndChatLinesRoundTrip() {
        rows.take(200).forEach { row ->
            assertTrue(row.checks.isNotEmpty())
            val parsed = Json.parseToJsonElement(AppIntegrationDatasetGenerator.chatJson(row)).jsonObject
            val messages = parsed.getValue("messages").jsonArray
            assertEquals(row.system, messages[0].jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(row.user, messages[1].jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(row.expected, messages[2].jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(CHECKS_VERSION, parsed.getValue("checks_version").jsonPrimitive.content.toInt())
        }
    }

    companion object {
        const val ROWS_ENV = "AIVE_APP_INTEGRATION_ROWS"
    }
}
