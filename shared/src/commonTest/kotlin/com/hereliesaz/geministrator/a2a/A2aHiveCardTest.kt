package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.toTaskExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A2aHiveCardTest {
    private val researcher = BuiltInRoles.Researcher
    private val architect = BuiltInRoles.Architect
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val roles = listOf(researcher, architect, engineer)

    private fun task(
        id: String,
        role: RoleDefinition,
        dependsOn: Set<String> = emptySet(),
        pin: String? = null,
        objective: String = "Do the ${role.name} work",
    ) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = role.name,
        objective = objective,
        roleId = role.id,
        dependsOn = dependsOn.mapTo(LinkedHashSet(), ::TaskDefinitionId),
        executor = role.executionSource.toTaskExecutor(role.id),
        providerConstraints = pin?.let { ProviderConstraints.RequireProvider(AgentProviderId(it)) } ?: ProviderConstraints.None,
    )

    private val hive = WorkflowDefinition(
        id = WorkflowDefinitionId("hive-1"),
        name = "Spec to code",
        tasks = listOf(
            task("research", researcher, pin = "gemini"),
            task("design", architect, dependsOn = setOf("research"), pin = "anthropic"),
            task("build", engineer, dependsOn = setOf("design")),
            TaskDefinition(
                id = TaskDefinitionId("sign-off"),
                name = "Sign off",
                objective = "A person approves",
                roleId = null,
                dependsOn = setOf(TaskDefinitionId("build")),
                executor = TaskExecutor.HumanApproval("Sign off"),
            ),
        ),
    )

    private fun context(
        connected: Set<String> = setOf("gemini", "anthropic"),
        taken: Set<String> = setOf("hive-1"),
        swarm: Collection<RoleDefinition> = roles,
    ) = HiveCardImportContext(connected, swarm, taken)

    private fun exported(
        options: HiveCardOptions = HiveCardOptions(),
        urls: Map<String, String> = emptyMap(),
        definition: WorkflowDefinition = hive,
    ): String = (A2aHiveCard.export(definition, roles, options, urls) as HiveCardExport.Ready).text

    private fun ready(text: String, context: HiveCardImportContext = context()): HiveCardImport.Ready =
        assertIs<HiveCardImport.Ready>(A2aHiveCard.import(text, context))

    private fun refused(text: String, context: HiveCardImportContext = context()): HiveCardProblem =
        assertIs<HiveCardImport.Refused>(A2aHiveCard.import(text, context)).problem

    /** A card whose record is [params], for hostile-input cases. */
    private fun cardWith(params: JsonObject): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("name", "x")
            put(
                "capabilities",
                buildJsonObject {
                    put(
                        "extensions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("uri", A2aHiveCard.EXTENSION_URI)
                                    put("params", params)
                                },
                            )
                        },
                    )
                },
            )
        },
    )

    private fun creature(
        id: String,
        kind: String = "role",
        role: String? = "researcher",
        extra: Map<String, JsonElement> = emptyMap(),
    ) = buildJsonObject {
        put("id", id)
        put("name", id)
        put("kind", kind)
        role?.let { put("role", it) }
        put("assignment", "auto")
        extra.forEach { (key, value) -> put(key, value) }
    }

    private fun record(vararg creatures: JsonObject, version: Int = 1) = buildJsonObject {
        put("format", A2aHiveCard.FORMAT)
        put("version", version)
        put("creatures", JsonArray(creatures.toList()))
    }

    private fun strands(vararg ids: String): JsonElement = buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }

    @Test
    fun theCardIsAnA2aCardWithTheRecordInAnExtension() {
        val card = A2aCodec.decodeCard(Json.parseToJsonElement(exported()))
        assertEquals("Spec to code", card.name)
        assertEquals(listOf("creature:research", "creature:design", "creature:build", "creature:sign-off"), card.skills.map { it.id })
        assertTrue("provider:gemini" in card.skills[0].tags && "role:researcher" in card.skills[0].tags)
        assertTrue("provider:auto" in card.skills[2].tags)
        assertEquals(listOf(A2aHiveCard.EXTENSION_URI), card.extensions.map { it.uri })
        assertFalse(card.extensions.single().required)
        // It describes a hive; it serves nothing.
        assertTrue(card.interfaces.isEmpty())
    }

    @Test
    fun exportThenImportKeepsCreaturesStrandsAndAssignments() {
        val result = ready(exported())
        val made = result.definition
        assertEquals("hive-2", made.id.value)
        assertEquals("Spec to code", made.name)
        assertEquals(hive.tasks.map { it.id }, made.tasks.map { it.id })
        assertEquals(hive.tasks.map { it.dependsOn }, made.tasks.map { it.dependsOn })
        assertEquals(hive.tasks.map { it.roleId }, made.tasks.map { it.roleId })
        assertEquals(hive.tasks.map { it.providerConstraints }, made.tasks.map { it.providerConstraints })
        assertEquals(TaskExecutor.HumanApproval("Sign off"), made.tasks.last().executor)
        assertEquals(2, result.summary.pinned.size)
        assertTrue(result.summary.needsProviders.isEmpty() && result.summary.needsRoles.isEmpty())
        assertEquals(3, result.summary.strandsKept)
    }

    @Test
    fun aRolesPreferredProviderIsRecordedAsTheAssignment() {
        val preferring = researcher.copy(preferredProviderId = AgentProviderId("anthropic"))
        val definition = hive.copy(tasks = listOf(task("research", preferring)))
        val text = (A2aHiveCard.export(definition, listOf(preferring), HiveCardOptions()) as HiveCardExport.Ready).text
        val result = ready(text, context(swarm = roles))
        assertEquals(ProviderConstraints.RequireProvider(AgentProviderId("anthropic")), result.definition.tasks.single().providerConstraints)
    }

    @Test
    fun aProviderNotConnectedHereBecomesAutoAndIsListed() {
        val result = ready(exported(), context(connected = setOf("gemini")))
        val design = result.definition.tasks.first { it.id.value == "design" }
        assertEquals(ProviderConstraints.None, design.providerConstraints)
        assertEquals(listOf("anthropic"), result.summary.needsProviders)
        assertEquals(1, result.summary.pinned.size)
        assertEquals(4, result.definition.tasks.size)
    }

    @Test
    fun aRemoteAgentProviderAppearsOnlyAsACardAddressTheKeeperCanBringIn() {
        val remote = hive.copy(tasks = listOf(task("research", researcher, pin = "a2a:agent.example")))
        val text = exported(urls = mapOf("a2a:agent.example" to "https://agent.example/.well-known/agent-card.json"), definition = remote)
        val result = ready(text, context(connected = emptySet()))
        assertEquals(listOf("https://agent.example/.well-known/agent-card.json"), result.summary.drifterCards)
        assertEquals(ProviderConstraints.None, result.definition.tasks.single().providerConstraints)
        // Already linked here: it is pinned, and nothing is offered.
        val linked = ready(text, context(connected = setOf("a2a:agent.example")))
        assertTrue(linked.summary.drifterCards.isEmpty())
        assertEquals(1, linked.summary.pinned.size)
    }

    @Test
    fun aCardAddressThatCouldHoldASecretIsLeftOut() {
        val unsafe = listOf(
            "https://user:hunter2@agent.example/card",
            "https://agent.example/card?key=abc",
            "https://agent.example/card#frag",
            "ftp://agent.example/x",
            "javascript:alert(1)",
        )
        for (url in unsafe) {
            val remote = hive.copy(tasks = listOf(task("research", researcher, pin = "a2a:agent.example")))
            val text = exported(urls = mapOf("a2a:agent.example" to url), definition = remote)
            assertFalse("hunter2" in text || "key=abc" in text || "frag" in text || "alert" in text, url)
            assertTrue(ready(text, context(connected = emptySet())).summary.drifterCards.isEmpty(), url)
        }
    }

    @Test
    fun aMissingRoleLeavesThatCreatureOutAndSaysSo() {
        val result = ready(exported(), context(swarm = listOf(researcher, engineer)))
        assertEquals(listOf("architect"), result.summary.needsRoles)
        assertEquals(listOf("research", "build", "sign-off"), result.definition.tasks.map { it.id.value })
        // The strands that led through the missing creature are dropped, and counted.
        assertEquals(1, result.summary.strandsDropped)
        assertTrue(result.definition.tasks.all { task -> task.dependsOn.all { dep -> result.definition.tasks.any { it.id == dep } } })
    }

    @Test
    fun aDisabledRoleCountsAsMissing() {
        val result = ready(exported(), context(swarm = listOf(researcher, architect.copy(enabled = false), engineer)))
        assertEquals(listOf("architect"), result.summary.needsRoles)
    }

    @Test
    fun stepsThatRunCodeAreNeverImported() {
        val withDeploy = hive.copy(
            tasks = hive.tasks + TaskDefinition(
                id = TaskDefinitionId("deploy"),
                name = "Deploy",
                objective = "Ship it",
                roleId = null,
                dependsOn = setOf(TaskDefinitionId("sign-off")),
                executor = TaskExecutor.Deployment("production"),
            ),
        )
        val text = exported(definition = withDeploy)
        assertFalse("production" in text)
        val result = ready(text)
        assertEquals(1, result.summary.leftOut)
        assertEquals(4, result.definition.tasks.size)
        assertTrue(result.definition.tasks.none { it.executor is TaskExecutor.Deployment })
    }

    @Test
    fun theImportIsAlwaysANewHive() {
        val taken = setOf("hive-1", "hive-2", "hive-3")
        assertEquals("hive-4", ready(exported(), context(taken = taken)).definition.id.value)
    }

    @Test
    fun objectivesAreLeftOutUnlessAsked() {
        val plain = exported()
        assertFalse("Do the Researcher work" in plain)
        assertEquals(researcher.description, ready(plain).definition.tasks.first().objective)
        val withObjectives = exported(HiveCardOptions(includeObjectives = true))
        assertTrue("Do the Researcher work" in withObjectives)
        assertEquals("Do the Researcher work", ready(withObjectives).definition.tasks.first().objective)
    }

    @Test
    fun objectivesAreOneBoundedLine() {
        val long = "line one\nline two ${"x".repeat(500)}"
        val definition = hive.copy(tasks = listOf(task("research", researcher, objective = long)))
        val text = exported(HiveCardOptions(includeObjectives = true), definition = definition)
        val objective = ready(text).definition.tasks.single().objective
        assertFalse('\n' in objective)
        assertTrue(objective.length <= A2aHiveCard.MAX_OBJECTIVE_CHARS)
    }

    @Test
    fun aHiveOverTheCreatureCapIsNotExported() {
        val big = hive.copy(tasks = (1..13).map { task("c$it", researcher) })
        assertEquals(HiveCardProblem.TooManyCreatures(13), (A2aHiveCard.export(big, roles) as HiveCardExport.Refused).problem)
        val twelve = hive.copy(tasks = (1..12).map { task("c$it", researcher) })
        assertEquals(12, ready(exported(definition = twelve)).definition.tasks.size)
    }

    // ---- hostile input ---------------------------------------------------------------------

    @Test
    fun junkIsRefusedWithoutCrashing() {
        val junk = listOf(
            "", "   ", "not json", "{", "}", "[]", "null", "42", "\"text\"", "{\"a\":", "{}", "{\"capabilities\":5}",
            "{\"capabilities\":{\"extensions\":5}}", "{\"capabilities\":{\"extensions\":[1,2,null,\"x\",[]]}}",
            "﻿{", "<html></html>", "{\"name\":\"an ordinary agent card\",\"skills\":[]}",
            "\u0000\u0001\u0002", "{\"capabilities\":{\"extensions\":[{\"uri\":\"${A2aHiveCard.EXTENSION_URI}\"}]}}",
            "{\"capabilities\":{\"extensions\":[{\"uri\":\"${A2aHiveCard.EXTENSION_URI}\",\"params\":[]}]}}",
        )
        for (text in junk) {
            assertIs<HiveCardImport.Refused>(A2aHiveCard.import(text, context()), text)
        }
    }

    @Test
    fun anOversizedCardIsRefusedUnread() {
        val padded = "{\"x\":\"" + "a".repeat(A2aHiveCard.MAX_CARD_BYTES + 10) + "\"}"
        assertEquals(HiveCardProblem.TooLarge, refused(padded))
        // Multi-byte text counts in bytes.
        val wide = "{\"x\":\"" + "é".repeat(A2aHiveCard.MAX_CARD_BYTES / 2 + 10) + "\"}"
        assertEquals(HiveCardProblem.TooLarge, refused(wide))
    }

    @Test
    fun veryDeepNestingIsRefusedBeforeItIsParsed() {
        val deep = "{\"a\":" + "[".repeat(5000) + "]".repeat(5000) + "}"
        assertEquals(HiveCardProblem.NotAHiveCard, refused(deep))
        val unbalanced = "{" + "[".repeat(100_000)
        assertEquals(HiveCardProblem.NotAHiveCard, refused(unbalanced))
    }

    @Test
    fun aBracketInsideAStringDoesNotCountAsNesting() {
        assertEquals(0, A2aHiveCard.nestingDepth("\"[[[[[[[[[[[[[[[[[[[[[[[[\""))
        assertEquals(1, A2aHiveCard.nestingDepth("{\"a\":\"\\\"[[[[\"}"))
    }

    @Test
    fun anUnknownVersionIsRefused() {
        for (version in listOf(0, 2, 99, -1)) {
            assertIs<HiveCardProblem.UnsupportedVersion>(refused(cardWith(record(creature("a"), version = version))), "v$version")
        }
        val stringVersion = buildJsonObject {
            put("format", A2aHiveCard.FORMAT)
            put("version", "1")
            put("creatures", JsonArray(listOf(creature("a"))))
        }
        assertIs<HiveCardProblem.UnsupportedVersion>(refused(cardWith(stringVersion)))
        val missing = buildJsonObject {
            put("format", A2aHiveCard.FORMAT)
            put("creatures", JsonArray(listOf(creature("a"))))
        }
        assertIs<HiveCardProblem.UnsupportedVersion>(refused(cardWith(missing)))
    }

    @Test
    fun aWrongFormatIsNotAHiveCard() {
        val wrong = buildJsonObject {
            put("format", "something-else")
            put("version", 1)
            put("creatures", JsonArray(listOf(creature("a"))))
        }
        assertEquals(HiveCardProblem.NotAHiveCard, refused(cardWith(wrong)))
    }

    @Test
    fun duplicateCreatureIdsAreRefused() {
        assertEquals(HiveCardProblem.DuplicateCreature("a"), refused(cardWith(record(creature("a"), creature("a")))))
    }

    @Test
    fun aCycleIsRefusedByTheSameValidatorAsAuthoredHives() {
        val a = creature("a", extra = mapOf("dependsOn" to strands("b")))
        val b = creature("b", extra = mapOf("dependsOn" to strands("a")))
        assertIs<HiveCardProblem.Invalid>(refused(cardWith(record(a, b))))
        val self = creature("a", extra = mapOf("dependsOn" to strands("a")))
        assertIs<HiveCardProblem.Invalid>(refused(cardWith(record(self))))
    }

    @Test
    fun aStrandToAnUnknownCreatureIsRefused() {
        val a = creature("a", extra = mapOf("dependsOn" to strands("ghost")))
        assertEquals(HiveCardProblem.UnknownStrand("a", "ghost"), refused(cardWith(record(a))))
    }

    @Test
    fun moreThanTwelveCreaturesAreRefused() {
        val many = (1..13).map { creature("c$it") }.toTypedArray()
        assertEquals(HiveCardProblem.TooManyCreatures(13), refused(cardWith(record(*many))))
        assertEquals(HiveCardProblem.TooManyCreatures(200), refused(cardWith(record(*Array(200) { creature("c$it") }))))
        assertEquals(HiveCardProblem.NoCreatures, refused(cardWith(record())))
    }

    @Test
    fun badIdsAndFieldsAreRefusedNotTrusted() {
        val badIds = listOf("", " ", "a b", "../x", "a/b", "-lead", "x".repeat(65), "a\u0000b", "é", "<script>", "a;b", ".hidden")
        for (id in badIds) {
            assertIs<HiveCardProblem.BadField>(refused(cardWith(record(creature(id)))), "id '$id'")
        }
        assertIs<HiveCardProblem.BadField>(refused(cardWith(record(creature("a", role = "bad role")))))
        assertIs<HiveCardProblem.BadField>(refused(cardWith(record(creature("a", kind = "script")))))
        assertIs<HiveCardProblem.BadField>(refused(cardWith(record(creature("a", role = null)))))
        val badAssignment = creature("a", extra = mapOf("assignment" to JsonPrimitive("force")))
        assertIs<HiveCardProblem.BadField>(refused(cardWith(record(badAssignment))))
        val pinNoProvider = creature("a", extra = mapOf("assignment" to JsonPrimitive("pin")))
        assertIs<HiveCardProblem.BadField>(refused(cardWith(record(pinNoProvider))))
        val badProvider = creature("a", extra = mapOf("assignment" to JsonPrimitive("pin"), "provider" to JsonPrimitive("a b")))
        assertIs<HiveCardProblem.BadField>(refused(cardWith(record(badProvider))))
        val badStrands = creature("a", extra = mapOf("dependsOn" to JsonPrimitive("b")))
        assertIs<HiveCardProblem.BadField>(refused(cardWith(record(badStrands))))
        val numericStrand = creature("a", extra = mapOf("dependsOn" to buildJsonArray { add(JsonPrimitive(7)) }))
        assertIs<HiveCardProblem.BadField>(refused(cardWith(record(numericStrand))))
        val notObjects = buildJsonObject {
            put("format", A2aHiveCard.FORMAT)
            put("version", 1)
            put("creatures", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive(1)); add(JsonArray(emptyList())) })
        }
        assertIs<HiveCardProblem.BadField>(refused(cardWith(notObjects)))
        val creaturesNotArray = buildJsonObject {
            put("format", A2aHiveCard.FORMAT)
            put("version", 1)
            put("creatures", "many")
        }
        assertIs<HiveCardProblem.BadField>(refused(cardWith(creaturesNotArray)))
    }

    @Test
    fun extraFieldsAreIgnored() {
        val extra = creature(
            "a",
            extra = mapOf(
                "futureField" to JsonPrimitive("x"),
                "executor" to buildJsonObject { put("script", "rm -rf /") },
                "repository" to JsonPrimitive("/home/me/private"),
            ),
        )
        val params = buildJsonObject {
            put("format", A2aHiveCard.FORMAT)
            put("version", 1)
            put("somethingNew", true)
            put("creatures", JsonArray(listOf(extra)))
        }
        val made = ready(cardWith(params)).definition.tasks.single()
        assertEquals("a", made.id.value)
        assertEquals(researcher.id, made.roleId)
        // The executor comes from this device's role, never from the card.
        assertEquals(researcher.executionSource.toTaskExecutor(researcher.id), made.executor)
    }

    @Test
    fun namesAreSanitisedToOneBoundedLine() {
        val shouty = creature("a", extra = mapOf("name" to JsonPrimitive("Line\none\t\u0007${"y".repeat(300)}")))
        val params = buildJsonObject {
            put("format", A2aHiveCard.FORMAT)
            put("version", 1)
            put("hive", buildJsonObject { put("name", "My\nhive\u0000") })
            put("creatures", JsonArray(listOf(shouty)))
        }
        val result = ready(cardWith(params))
        assertEquals("My hive", result.definition.name)
        val name = result.definition.tasks.single().name
        assertTrue(name.length <= A2aHiveCard.MAX_NAME_CHARS)
        assertTrue(name.none { it.isISOControl() })
    }

    @Test
    fun aHiveOfOnlyMissingRolesIsNothingUsableRatherThanAnEmptyHive() {
        val onlyRoles = hive.copy(tasks = hive.tasks.take(3))
        val nothing = assertIs<HiveCardProblem.NothingUsable>(refused(exported(definition = onlyRoles), context(swarm = emptyList())))
        assertEquals(listOf("researcher", "architect", "implementation-engineer"), nothing.needsRoles)
    }

    // ---- nothing private -------------------------------------------------------------------

    @Test
    fun theCardNeverCarriesSecretsPathsOrPrompts() {
        val busyRole = researcher.copy(
            instructions = "Use the key sk-LIVE-FAKE-0001 and read /home/keeper/private/repo",
            description = "Talks to https://user:pass@internal.example/v1?token=FAKE-TOKEN-0002",
            preferredProviderId = AgentProviderId("openai"),
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("hive-9"),
            name = "Private",
            description = "Account me@example.com balance 12.34",
            tasks = listOf(task("research", busyRole, objective = "Read /home/keeper/private/repo with ghp_FAKEGITHUBTOKEN0003")),
        )
        for (options in listOf(HiveCardOptions(false), HiveCardOptions(true))) {
            val text = (A2aHiveCard.export(definition, listOf(busyRole), options, mapOf("openai" to "https://a:b@x.example/card")) as HiveCardExport.Ready).text
            val leaks = listOf("sk-LIVE-FAKE-0001", "FAKE-TOKEN-0002", "user:pass", "me@example.com", "12.34", "internal.example", "a:b@x.example", "Use the key")
            for (secret in leaks) {
                assertFalse(secret in text, "$secret leaked with $options")
            }
            if (!options.includeObjectives) assertFalse("ghp_FAKE" in text || "/home/keeper/private" in text)
        }
    }

    @Test
    fun theCardHoldsOnlyTheDocumentedFields() {
        val params = Json.parseToJsonElement(exported(HiveCardOptions(true))).jsonObject["capabilities"]!!.jsonObject["extensions"]!!.jsonArray
            .single().jsonObject["params"]!!.jsonObject
        assertEquals(setOf("format", "version", "hive", "includesObjectives", "creatures"), params.keys)
        val allowed = setOf("id", "name", "kind", "role", "assignment", "provider", "cardUrl", "dependsOn", "objective")
        val creatures = params["creatures"]!!.jsonArray
        creatures.forEach { assertTrue(allowed.containsAll(it.jsonObject.keys), it.jsonObject.keys.toString()) }
        assertNull(creatures[2].jsonObject["provider"])
        assertEquals("auto", creatures[2].jsonObject["assignment"]!!.jsonPrimitive.content)
    }

    @Test
    fun aCardImportsOnAnotherDeviceWithNothingConnected() {
        val result = ready(exported(), context(connected = emptySet()))
        assertEquals(setOf("gemini", "anthropic"), result.summary.needsProviders.toSet())
        assertTrue(result.definition.tasks.all { it.providerConstraints == ProviderConstraints.None })
    }
}
