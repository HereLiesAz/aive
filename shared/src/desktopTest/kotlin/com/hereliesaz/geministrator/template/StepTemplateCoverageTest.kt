package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskContract
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.haiveBuiltInRoles
import com.hereliesaz.geministrator.domain.isEffectful
import com.hereliesaz.geministrator.workflow.CodingWorkflowSuite
import com.hereliesaz.geministrator.workflow.NodeCharacterGenerationWorkflowFactory
import com.hereliesaz.geministrator.workflow.ShortFilmHive
import com.hereliesaz.geministrator.workflow.StarterWorkflowFactory
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.WorkflowValidationError
import com.hereliesaz.geministrator.workflow.checkStepOutputs
import com.hereliesaz.geministrator.workflow.improvement.SelfImprovementLoop
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every built-in role and every step of every built-in swarm and Azphalt package has a template record or a reasoned entry
 * (docs/Azphalt-Templates.md), so a new package cannot ship without one.
 */
class StepTemplateCoverageTest {
    private val json = Json { ignoreUnknownKeys = true; allowStructuredMapKeys = true }
    private val packages = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/azphalt-packages") }.first { it.isDirectory }
    private val packageDirs = packages.listFiles().orEmpty().filter { File(it, "manifest.json").isFile }.sortedBy { it.name }

    /** Swarms whose steps are owned by the system and read by code: the self-improvement loop's output is a fixed line grammar. */
    private val systemOwnedSwarms = setOf(SelfImprovementLoop.HIVE_ID)

    private fun packageRoles(): List<RoleDefinition> = packageDirs.flatMap { dir ->
        File(dir, "roles").listFiles().orEmpty().filter { it.extension == "json" }
            .map { json.decodeFromString(RoleDefinition.serializer(), it.readText()) }
    }

    private fun packageWorkflows(): List<WorkflowDefinition> = packageDirs.flatMap { dir ->
        File(dir, "workflows").listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }
            .map { json.decodeFromString(WorkflowDefinition.serializer(), it.readText()) }
    }

    /** The swarm key each built-in is looked up under (the starter's id is the caller's, so it has a key of its own), and its definition. */
    private fun builtInSwarms(): List<Pair<String, WorkflowDefinition>> =
        CodingWorkflowSuite.definitions().map { it.id.value to it } +
            (ShortFilmHive.ID to ShortFilmHive.definition()) +
            (StepTemplateAssignments.STARTER_KEY to StarterWorkflowFactory.create(WorkflowDefinitionId("starter-coverage"), "Do the thing"))

    private fun allSwarms(): List<Pair<String, WorkflowDefinition>> = builtInSwarms() + packageWorkflows().map { it.id.value to it }

    @Test
    fun everyRoleHasARoleTemplateRecordAtGenerationOne() {
        val roles = (haiveBuiltInRoles + NodeCharacterGenerationWorkflowFactory.roles + packageRoles()).distinctBy { it.id }
        val catalog = TemplateCatalog(BuiltInTemplates.bases(roles))
        assertTrue(roles.size > 100, "the built-ins and the packages' roles are all counted")
        for (role in roles) {
            val record = assertNotNull(catalog.current(TemplateKind.Role, role.id.value), "no role template for ${role.id.value}")
            assertEquals(1, record.version, role.id.value)
            assertEquals(1, catalog.generationOf(TemplateKind.Role, role.id.value, record.version), role.id.value)
            assertEquals(role.id.value, record.locked["id"])
            assertEquals(role.instructions, record.body.wording, role.id.value)
        }
    }

    @Test
    fun aRoleIdShippedByTwoPackagesIsOneRecordAndTheCollisionsAreTheKnownOnes() {
        val duplicated = packageRoles().groupBy { it.id.value }.filterValues { it.size > 1 }.keys
        // Two packages ship each of these ids with different instructions; the role template is by id, so one record serves both.
        val known = setOf(
            "agent-security-architect", "data-investigator", "evidence-skeptic", "failure-diagnostician",
            "feature-reviewer", "incident-commander", "ux-researcher",
        )
        assertEquals(known, duplicated, "a new package role that reuses an id must be reasoned here")
    }

    @Test
    fun everyStepOfEveryBuiltInSwarmAndPackageHasATemplateOrAReasonedEntry() {
        val missing = mutableListOf<String>()
        val staleGap = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for ((key, definition) in allSwarms()) {
            if (key in systemOwnedSwarms) continue
            for (task in definition.tasks) {
                val stepKey = "$key/${task.id.value}"
                seen += stepKey
                val gap = StepTemplateAssignments.gaps[stepKey]
                val ref = task.contract?.template
                when {
                    task.executor is TaskExecutor.HumanApproval -> if (ref != null || gap != null) staleGap += "$stepKey (a person's approval makes nothing)"
                    ref != null -> {
                        val template = BuiltInTemplates.byId[ref.id]
                        if (template == null || template.version != ref.version) missing += "$stepKey names an unknown template ${ref.key}"
                        if (gap != null) staleGap += "$stepKey has a template and a gap"
                    }
                    gap == null -> missing += stepKey
                }
            }
        }
        assertEquals(emptyList(), missing, "steps with no template and no reasoned entry")
        assertEquals(emptyList(), staleGap)
        assertEquals(emptySet(), StepTemplateAssignments.gaps.keys - seen, "gap entries for steps that do not exist")
    }

    @Test
    fun theCountsMatchWhatShips() {
        val steps = allSwarms().filter { it.first !in systemOwnedSwarms }.flatMap { it.second.tasks }
        val gates = steps.count { it.executor is TaskExecutor.HumanApproval }
        val withTemplate = steps.count { it.contract != null }
        assertEquals(steps.size - gates, withTemplate + StepTemplateAssignments.gaps.size)
        assertTrue(packageWorkflows().size >= 66 && packageWorkflows().sumOf { it.tasks.size } >= 101)
    }

    @Test
    fun everyContractOnABuiltInStepOnlyNamesItsStartingTemplate() {
        for ((_, definition) in allSwarms()) {
            assertEquals(
                emptyList(),
                WorkflowGraphValidator.validate(definition).filterIsInstance<WorkflowValidationError.UnboundContractInput>(),
                definition.id.value,
            )
            for (task in definition.tasks) {
                val contract: TaskContract = task.contract ?: continue
                val where = "${definition.id.value}/${task.id.value}"
                val template = BuiltInTemplates.byId.getValue(contract.template.id)
                assertTrue(contract.startingPoint, where)
                assertEquals(emptyList(), contract.allowedTools, where)
                assertTrue(contract.effects.none { it.isEffectful }, where)
                assertTrue(task.acceptanceCriteria.isNotEmpty() || task.objective.isNotBlank(), where)
                assertEquals(template.outputs, contract.outputs, where)
                assertEquals(emptyList(), StepInstantiation.missingInputs(contract), where)
                assertEquals(emptyList(), checkStepOutputs(contract, emptyList()), "$where is not held to its output")
                assertTrue(contract.inputs.values.all { StepInstantiation.boundTask(it) == null }, "$where binds no step")
                assertTrue(template.inputs.filter { it.required }.all { !contract.inputs[it.name].isNullOrBlank() }, where)
            }
        }
    }

    @Test
    fun everyGapNamesWhatIsMissing() {
        assertTrue(StepGap.entries.all { it.needs.isNotBlank() && it.reason.isNotBlank() })
        assertTrue(StepGap.entries.none { it.needs in BuiltInTemplates.byId }, "a gap is a template that does not exist yet")
    }

    @Test
    fun aSwarmSavedBeforeContractsExistedStillLoadsWithNoContract() {
        for (file in packageDirs.flatMap { dir -> File(dir, "workflows").listFiles().orEmpty().toList() }) {
            val old = stripContracts(json.parseToJsonElement(file.readText()))
            val loaded = json.decodeFromString(WorkflowDefinition.serializer(), old.toString())
            assertTrue(loaded.tasks.isNotEmpty() && loaded.tasks.all { it.contract == null }, file.name)
        }
        val bare = json.decodeFromString(TaskContract.serializer(), """{"template":{"id":"write-document","version":1}}""")
        assertTrue(!bare.startingPoint, "a contract written before the flag is an enforced one")
    }

    @Test
    fun everyPackageManifestMatchesItsFiles() {
        assertTrue(packageDirs.size >= 78)
        for (dir in packageDirs) {
            val manifest = json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
            assertTrue(Regex("""\d+\.\d+\.\d+""").matches(manifest.getValue("version").jsonPrimitive.content), dir.name)
            for ((path, digest) in manifest.getValue("files").jsonObject) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(dir, path).readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals("sha256-$actual", digest.jsonPrimitive.content, "${dir.name}/$path")
            }
        }
    }

    private fun stripContracts(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterKeys { it != "contract" }.mapValues { stripContracts(it.value) })
        is JsonArray -> JsonArray(element.map(::stripContracts))
        else -> element
    }
}
