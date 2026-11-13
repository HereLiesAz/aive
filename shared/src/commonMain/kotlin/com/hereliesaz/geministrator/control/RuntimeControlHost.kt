package com.hereliesaz.geministrator.control

import com.hereliesaz.geministrator.ApplicationRuntime
import com.hereliesaz.geministrator.HiveDeleteRefusal
import com.hereliesaz.geministrator.HiveDeleteVerdict
import com.hereliesaz.geministrator.azphalt.AuthoredWorkflowStore
import com.hereliesaz.geministrator.classifyError
import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.workflow.isTerminal
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.launchSavedWorkflow
import com.hereliesaz.geministrator.hiveDeleteVerdict
import com.hereliesaz.geministrator.workflow.WorkflowCompositionService
import com.hereliesaz.geministrator.workflow.MUTATING_CAPABILITIES
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.humanReadable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * [ControlHost] over the app's own [ApplicationRuntime]: the same persistence, role save and
 * launch paths the screens use, so what an agent does shows up in the app at once.
 *
 * Rules that make this safe to hand to an outside agent (THREAT_MODEL, Control API):
 *  - No approval gate is reachable: this class holds the runtime for reading, launching and
 *    stopping runs, and never calls anything that decides a gate, lifts a spend stop or reads a key.
 *  - Hives are authored from two kinds of creature only: an agent working as a switched-on role, and a
 *    human-approval gate. Scripts, deployments and repository operations cannot be authored here, and a
 *    role whose capabilities include repository write, pull-request creation or shell execution is refused
 *    ([roleFor]), so no creature made here can change a repository or run a command. (A hive made in the
 *    app can hold such a creature; the next rule keeps it behind its approval.)
 *  - An approval can be added but never lowered: a creature that waits for a person, or whose
 *    approval is "human", cannot be removed or relaxed over this interface, and no edit may move a
 *    creature out from behind one ([refuseRelaxedApprovals]).
 *  - A run started here has an external objective for the context-taint rule: a step that can change
 *    something waits at a gate for a person.
 *  - Only hives made in the app (the authored list) can be changed or deleted, and not while a run is live.
 */
class RuntimeControlHost(
    private val runtime: () -> ApplicationRuntime?,
    private val authoredStore: AuthoredWorkflowStore,
    /** The linked planner's swarm draft; null where no planner is linked. */
    private val drafter: () -> (suspend (String) -> ControlDraftOutcome)? = { null },
    /** Settings an agent may read; the caller lists only what holds no key. */
    private val settingsView: () -> JsonObject = { JsonObject(emptyMap()) },
    private val appVersion: String = "dev",
) : ControlHost {

    companion object {
        const val MAX_CREATURES = 24
        private const val NAME_MAX = 60
        private const val OBJECTIVE_MAX = 4_000
        private const val CRITERION_MAX = 500
        private const val STRING_CLIP = 4_000
        private val SPEC_KEYS = setOf("id", "name", "objective", "kind", "roleId", "dependsOn", "acceptanceCriteria", "requiresHumanApproval", "label")
        private val PATCH_KEYS = SPEC_KEYS - setOf("id", "kind")
        private val json = Json { encodeDefaults = true }
    }

    private fun rt(): ApplicationRuntime =
        runtime() ?: controlError(ControlErrorCode.Unavailable, "The app has not finished starting; try again shortly")

    private fun fail(code: ControlErrorCode, message: String): Nothing = controlError(code, message)

    // ---- Read ----------------------------------------------------------------------------

    override suspend fun status(): JsonObject {
        val r = runtime()
        return buildJsonObject {
            put("app", "The Aive")
            put("version", appVersion)
            put("runtimeReady", r != null)
            if (r != null) {
                val runs = allRuns(r)
                put("hives", r.persistence.definitions.all().size)
                put("runs", runs.size)
                put("liveRuns", runs.count { it.status == WorkflowRunStatus.Running || it.status == WorkflowRunStatus.AwaitingHuman })
                put("pendingApprovals", pendingGates(r).size)
                runs.maxByOrNull { it.updatedAtEpochMillis }?.let { put("latestRunId", it.id.value) }
                put("plannerLinked", drafter() != null)
            }
        }
    }

    override suspend fun listHives(): JsonElement {
        val r = rt()
        val authored = authoredStore.all()
        return buildJsonArray {
            r.persistence.definitions.all().sortedBy { it.name.lowercase() }.forEach { add(hiveSummary(it, it.id in authored)) }
        }
    }

    override suspend fun getHive(id: String): JsonObject {
        val r = rt()
        val definition = r.persistence.definitions.get(WorkflowDefinitionId(id)) ?: fail(ControlErrorCode.NotFound, "No hive $id")
        return hiveDetail(definition, definition.id in authoredStore.all())
    }

    override suspend fun listRoles(): JsonElement {
        val r = rt()
        return buildJsonArray { roles(r).forEach { add(roleJson(it, withInstructions = false)) } }
    }

    override suspend fun getRole(id: String): JsonObject {
        val role = roles(rt()).firstOrNull { it.id.value == id } ?: fail(ControlErrorCode.NotFound, "No role $id")
        return roleJson(role, withInstructions = true)
    }

    override suspend fun listRuns(hiveId: String?, limit: Int): JsonElement {
        val r = rt()
        return buildJsonArray {
            allRuns(r)
                .filter { hiveId == null || it.workflowDefinitionId.value == hiveId }
                .sortedByDescending { it.updatedAtEpochMillis }
                .take(limit)
                .forEach { add(runSummary(it)) }
        }
    }

    override suspend fun getRun(id: String): JsonObject {
        val r = rt()
        val run = r.persistence.runs.get(WorkflowRunId(id)) ?: fail(ControlErrorCode.NotFound, "No run $id")
        val definition = r.persistence.definitions.get(run.workflowDefinitionId)
        val names = definition?.tasks?.associate { it.id to it.name }.orEmpty()
        return JsonObject(
            runSummary(run) + mapOf(
                "steps" to buildJsonArray {
                    run.taskRuns.values.forEach { t ->
                        add(
                            buildJsonObject {
                                put("creatureId", t.taskDefinitionId.value)
                                put("name", names[t.taskDefinitionId] ?: t.taskDefinitionId.value)
                                put("status", t.status.name)
                                put("attempt", t.attempt)
                                t.assignedRoleId?.let { put("roleId", it.value) }
                                t.assignedProviderId?.let { put("providerId", it.value) }
                                put("failedOverFrom", JsonArray(t.failedProviders.map { JsonPrimitive(it.value) }))
                                t.progress?.let { put("progress", it.toDouble()) }
                                t.progressMessage?.let { put("progressMessage", clip(it)) }
                                t.blockingReason?.let { put("blockedBy", "${it.code}: ${clip(it.message)}") }
                                t.keeperQuestion?.let { put("waitingOnQuestion", clip(it)) }
                                t.usage?.let {
                                    put("inputTokens", it.inputTokens)
                                    put("outputTokens", it.outputTokens)
                                }
                                put(
                                    "artifacts",
                                    buildJsonArray {
                                        t.artifacts.forEach { a ->
                                            add(
                                                buildJsonObject {
                                                    put("id", a.id.value)
                                                    put("kind", a.kind.name)
                                                    put("label", clip(a.label))
                                                    a.uri?.let { put("uri", clip(it)) }
                                                    a.textContent?.let { put("text", clip(it)) }
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            ),
        )
    }

    override suspend fun listEvents(runId: String, afterMillis: Long?, limit: Int): JsonElement {
        val r = rt()
        val id = WorkflowRunId(runId)
        if (r.persistence.runs.get(id) == null) fail(ControlErrorCode.NotFound, "No run $runId")
        val events = r.persistence.events.forRun(id)
            .filter { afterMillis == null || it.occurredAtEpochMillis > afterMillis }
            .sortedBy { it.occurredAtEpochMillis }
            .take(limit)
        return buildJsonArray {
            events.forEach { e ->
                add(clipJson(json.encodeToJsonElement(WorkflowEvent.serializer(), e)))
            }
        }
    }

    override suspend fun listApprovals(): JsonElement {
        val r = rt()
        return buildJsonObject {
            put("note", "Approvals are decided in the app. This list is read-only.")
            put(
                "gates",
                buildJsonArray {
                    pendingGates(r).forEach { g ->
                        add(
                            buildJsonObject {
                                put("gateId", g.id.value)
                                put("runId", g.workflowRunId.value)
                                g.taskDefinitionId?.let { put("creatureId", it.value) }
                                put("kind", g.kind.name)
                                put("status", g.status.name)
                                put("reason", clip(g.reason))
                                put("requiresHuman", g.requiresHuman)
                                g.requiredAuthority?.let { put("requiredAuthority", it.name) }
                                put("createdAt", g.createdAtEpochMillis)
                            },
                        )
                    }
                },
            )
            put(
                "creaturesWaiting",
                buildJsonArray {
                    allRuns(r).filter { !it.status.isTerminal() }.forEach { run ->
                        run.taskRuns.values
                            .filter { it.status == com.hereliesaz.geministrator.domain.TaskRunStatus.AwaitingApproval }
                            .forEach { t ->
                                add(
                                    buildJsonObject {
                                        put("runId", run.id.value)
                                        put("creatureId", t.taskDefinitionId.value)
                                        t.providerPlan?.let { put("plan", clip(it)) }
                                    },
                                )
                            }
                    }
                },
            )
        }
    }

    override suspend fun listProviders(): JsonObject {
        val r = rt()
        val health = r.checkProviderHealth()
        return buildJsonObject {
            put(
                "providers",
                buildJsonArray {
                    r.providerRegistry.providerIds.sortedBy { it.value }.forEach { id ->
                        val result = health[id.value]
                        add(
                            buildJsonObject {
                                put("id", id.value)
                                put("name", r.providerRegistry.displayName(id))
                                put("explicitOnly", r.providerRegistry.provider(id)?.explicitOnly ?: false)
                                put("connected", result?.isSuccess == true)
                                result?.getOrNull()?.let { caps -> put("capabilities", JsonArray(caps.supported.map { JsonPrimitive(it.name) }.sortedBy { it.content })) }
                                // Only the kind of failure, never the provider's own message.
                                result?.exceptionOrNull()?.let { put("problem", classifyError(it.message).name) }
                            },
                        )
                    }
                },
            )
            put(
                "failoverNotes",
                buildJsonArray {
                    r.providerRegistry.failoverNotes.value.forEach { n ->
                        add(buildJsonObject { put("text", clip(n.text)); put("at", n.atEpochMillis) })
                    }
                },
            )
        }
    }

    override suspend fun settings(): JsonObject = settingsView()

    // ---- Author --------------------------------------------------------------------------

    override suspend fun createHive(spec: JsonObject): JsonObject {
        val r = rt()
        requireKeys(spec, setOf("id", "name", "description", "creatures"))
        val name = spec.str("name")?.trim().orEmpty()
        if (name.isEmpty() || name.length > NAME_MAX) fail(ControlErrorCode.InvalidArgument, "name is required (at most $NAME_MAX characters)")
        val creatureSpecs = (spec["creatures"] as? JsonArray)?.map { it as? JsonObject ?: fail(ControlErrorCode.InvalidArgument, "creatures must be objects") }.orEmpty()
        if (creatureSpecs.isEmpty()) fail(ControlErrorCode.InvalidArgument, "A hive needs at least one creature")
        if (creatureSpecs.size > MAX_CREATURES) fail(ControlErrorCode.InvalidArgument, "At most $MAX_CREATURES creatures")
        val available = roles(r)
        val taken = mutableSetOf<String>()
        val tasks = creatureSpecs.map { c ->
            requireKeys(c, SPEC_KEYS)
            buildTask(c, available, taken)
        }
        val existing = r.persistence.definitions.all().map { it.id.value }.toSet()
        val id = spec.str("id")?.let(::slug)?.takeIf { it.isNotEmpty() }?.also {
            if (it in existing) fail(ControlErrorCode.Conflict, "A hive with id $it exists")
        } ?: uniqueId(slug(name).ifEmpty { "hive" }, existing)
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId(id),
            name = name,
            description = spec.str("description")?.trim()?.take(OBJECTIVE_MAX)?.ifEmpty { null },
            tasks = tasks,
        )
        save(r, definition)
        authoredStore.add(definition.id)
        return hiveDetail(definition, authored = true)
    }

    override suspend fun updateHive(id: String, patch: JsonObject): JsonObject {
        val r = rt()
        requireKeys(patch, setOf("name", "description"))
        val definition = editable(r, id)
        val name = patch.str("name")?.trim()
        if (name != null && (name.isEmpty() || name.length > NAME_MAX)) fail(ControlErrorCode.InvalidArgument, "name must be 1 to $NAME_MAX characters")
        val updated = definition.copy(
            name = name ?: definition.name,
            description = if ("description" in patch) patch.str("description")?.trim()?.take(OBJECTIVE_MAX)?.ifEmpty { null } else definition.description,
        )
        save(r, updated)
        return hiveDetail(updated, authored = true)
    }

    override suspend fun deleteHive(id: String): JsonObject {
        val r = rt()
        val definition = editable(r, id)
        val runs = allRuns(r)
        when (val verdict = hiveDeleteVerdict(definition.id, r.persistence.definitions.all(), runs, emptySet())) {
            HiveDeleteVerdict.Allowed -> Unit
            is HiveDeleteVerdict.Refused -> fail(
                ControlErrorCode.Conflict,
                when (verdict.reason) {
                    HiveDeleteRefusal.LiveRun -> "A run of this hive is live; stop it first"
                    HiveDeleteRefusal.UsedAsCircuit -> "Another hive uses this hive as a circuit"
                    HiveDeleteRefusal.FromPackage -> "This hive came from a package; remove the package in the Store"
                    HiveDeleteRefusal.Unknown -> "No hive $id"
                },
            )
        }
        r.persistence.definitions.delete(definition.id)
        authoredStore.remove(definition.id)
        return buildJsonObject { put("deleted", definition.id.value); put("pastRunsKept", true) }
    }

    override suspend fun addCreature(hiveId: String, spec: JsonObject): JsonObject {
        val r = rt()
        requireKeys(spec, SPEC_KEYS)
        val definition = editable(r, hiveId)
        if (definition.tasks.size >= MAX_CREATURES) fail(ControlErrorCode.InvalidArgument, "At most $MAX_CREATURES creatures")
        val taken = definition.tasks.mapTo(mutableSetOf()) { it.id.value }
        val task = buildTask(spec, roles(r), taken, known = taken.toSet())
        val updated = definition.copy(tasks = definition.tasks + task)
        save(r, updated)
        return hiveDetail(updated, authored = true)
    }

    override suspend fun updateCreature(hiveId: String, creatureId: String, patch: JsonObject): JsonObject {
        val r = rt()
        requireKeys(patch, PATCH_KEYS)
        val definition = editable(r, hiveId)
        val task = definition.tasks.firstOrNull { it.id.value == creatureId } ?: fail(ControlErrorCode.NotFound, "No creature $creatureId in hive $hiveId")
        val executor = task.executor
        if (executor !is TaskExecutor.RoleAgent && executor !is TaskExecutor.HumanApproval) {
            fail(ControlErrorCode.PolicyDenied, "This creature is not an agent or an approval gate; change it in the app")
        }
        var next = task
        patch.str("name")?.let { next = next.copy(name = it.trim().ifEmpty { fail(ControlErrorCode.InvalidArgument, "name must not be empty") }.take(NAME_MAX)) }
        patch.str("objective")?.let { next = next.copy(objective = it.trim().take(OBJECTIVE_MAX)) }
        if ("dependsOn" in patch) {
            val deps = strings(patch["dependsOn"], "dependsOn").map(::TaskDefinitionId).toSet()
            next = next.copy(dependsOn = deps)
        }
        if ("acceptanceCriteria" in patch) {
            next = next.copy(acceptanceCriteria = strings(patch["acceptanceCriteria"], "acceptanceCriteria").map { AcceptanceCriterion(it.trim().take(CRITERION_MAX)) })
        }
        if ("roleId" in patch) {
            if (executor !is TaskExecutor.RoleAgent) fail(ControlErrorCode.InvalidArgument, "Only an agent creature has a role")
            val role = roleFor(patch.str("roleId"), roles(r))
            next = next.copy(roleId = role.id, executor = TaskExecutor.RoleAgent(role.id))
        }
        if ("label" in patch) {
            if (executor !is TaskExecutor.HumanApproval) fail(ControlErrorCode.InvalidArgument, "Only an approval gate has a label")
            next = next.copy(executor = TaskExecutor.HumanApproval(patch.str("label")?.trim()?.take(NAME_MAX)?.ifEmpty { null } ?: "Human approval"))
        }
        if ("requiresHumanApproval" in patch) {
            val wanted = (patch["requiresHumanApproval"] as? JsonPrimitive)?.booleanOrNull
                ?: fail(ControlErrorCode.InvalidArgument, "requiresHumanApproval must be true or false")
            if (!wanted && task.approvalPolicy is ApprovalPolicy.HumanApproval) {
                fail(ControlErrorCode.PolicyDenied, "A human approval can be added over this interface but not removed; remove it in the app")
            }
            if (wanted) next = next.copy(approvalPolicy = ApprovalPolicy.HumanApproval)
        }
        val updated = definition.copy(tasks = definition.tasks.map { if (it.id == task.id) next else it })
        refuseRelaxedApprovals(definition, updated)
        save(r, updated)
        return hiveDetail(updated, authored = true)
    }

    override suspend fun removeCreature(hiveId: String, creatureId: String): JsonObject {
        val r = rt()
        val definition = editable(r, hiveId)
        val task = definition.tasks.firstOrNull { it.id.value == creatureId } ?: fail(ControlErrorCode.NotFound, "No creature $creatureId in hive $hiveId")
        if (task.executor is TaskExecutor.HumanApproval || task.approvalPolicy is ApprovalPolicy.HumanApproval) {
            fail(ControlErrorCode.PolicyDenied, "A creature that waits for a person cannot be removed over this interface; remove it in the app")
        }
        if (task.executor !is TaskExecutor.RoleAgent) {
            fail(ControlErrorCode.PolicyDenied, "This creature is not an agent; remove it in the app")
        }
        if (definition.tasks.size == 1) fail(ControlErrorCode.Conflict, "A hive keeps at least one creature; delete the hive instead")
        val remaining = definition.tasks.filterNot { it.id == task.id }.map { it.copy(dependsOn = it.dependsOn - task.id) }
        val updated = definition.copy(tasks = remaining)
        refuseRelaxedApprovals(definition, updated)
        save(r, updated)
        return hiveDetail(updated, authored = true)
    }

    override suspend fun draftHive(prompt: String): JsonObject {
        val draft = drafter() ?: fail(ControlErrorCode.Unavailable, "No queen is linked; connect a source in the app")
        if (prompt.isBlank()) fail(ControlErrorCode.InvalidArgument, "prompt is required")
        return when (val result = draft(prompt)) {
            is ControlDraftOutcome.Ready -> buildJsonObject {
                put("suggestedName", result.suggestedName)
                put("saved", false)
                put("notes", JsonArray(result.notes.map { JsonPrimitive(clip(it)) }))
                put(
                    "creatures",
                    buildJsonArray {
                        result.tasks.forEach { t ->
                            add(
                                buildJsonObject {
                                    put("id", t.id.value)
                                    put("name", t.name)
                                    put("objective", clip(t.objective))
                                    t.roleId?.let { put("roleId", it.value) }
                                    put("dependsOn", JsonArray(t.dependsOn.map { JsonPrimitive(it.value) }))
                                    put("requiresHumanApproval", t.approvalPolicy is ApprovalPolicy.HumanApproval)
                                },
                            )
                        }
                    },
                )
            }
            is ControlDraftOutcome.Failed -> fail(result.code, result.message)
        }
    }

    override suspend fun setRoleProvider(roleId: String, providerId: String?): JsonObject {
        val r = rt()
        val role = roles(r).firstOrNull { it.id.value == roleId } ?: fail(ControlErrorCode.NotFound, "No role $roleId")
        if (providerId != null && AgentProviderId(providerId) !in r.providerRegistry.providerIds) {
            fail(ControlErrorCode.NotFound, "No linked source $providerId")
        }
        // The same save the Swarm screen's provider picker uses.
        val saved = role.copy(preferredProviderId = providerId?.let(::AgentProviderId))
        r.saveRole(saved)
        return roleJson(saved, withInstructions = false)
    }

    // ---- Run control ---------------------------------------------------------------------

    override suspend fun startRun(spec: JsonObject): JsonObject {
        val r = rt()
        requireKeys(spec, setOf("hiveId", "objective", "projectName"))
        val hiveId = spec.str("hiveId") ?: fail(ControlErrorCode.InvalidArgument, "hiveId is required; start a run of a saved hive")
        val definition = r.persistence.definitions.get(WorkflowDefinitionId(hiveId)) ?: fail(ControlErrorCode.NotFound, "No hive $hiveId")
        val objective = spec.str("objective")?.trim().orEmpty()
        if (objective.isEmpty() || objective.length > 20_000) fail(ControlErrorCode.InvalidArgument, "objective is required (at most 20000 characters)")
        if (r.hasUnfinishedActiveRun()) fail(ControlErrorCode.Conflict, "A run is already unfinished; a new launch would replace it")
        try {
            WorkflowCompositionService(r.persistence).validated(definition)
            r.launchSavedWorkflow(
                definition = definition,
                projectName = spec.str("projectName")?.trim()?.take(NAME_MAX)?.ifEmpty { null } ?: "MCP: ${definition.name}".take(NAME_MAX),
                objective = objective,
                // Another agent wrote it, not the keeper: every step that can change something waits at a context-taint gate.
                objectiveProvenance = ContextProvenance.ExternalContent,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            fail(ControlErrorCode.InvalidArgument, clip(e.message ?: "The hive cannot be run"))
        }
        val run = allRuns(r).maxByOrNull { it.updatedAtEpochMillis } ?: fail(ControlErrorCode.Internal, "The run did not start")
        return runSummary(run)
    }

    override suspend fun stopRun(runId: String): JsonObject {
        val r = rt()
        val run = r.persistence.runs.get(WorkflowRunId(runId)) ?: fail(ControlErrorCode.NotFound, "No run $runId")
        if (run.status.isTerminal()) fail(ControlErrorCode.Conflict, "The run has already ended")
        if (!r.cancelActiveRun(run.id)) fail(ControlErrorCode.Conflict, "Only the active run can be stopped")
        return buildJsonObject { put("stopped", run.id.value) }
    }

    // ---- Helpers -------------------------------------------------------------------------

    private suspend fun allRuns(r: ApplicationRuntime): List<WorkflowRun> =
        r.persistence.projects.all().flatMap { r.persistence.runs.byProject(it.id) }

    private suspend fun pendingGates(r: ApplicationRuntime) =
        allRuns(r).filter { !it.status.isTerminal() }.flatMap { r.persistence.approvalGates.unresolved(it.id) }

    private suspend fun roles(r: ApplicationRuntime): List<RoleDefinition> =
        activeRoles(resolveRoleCollection(r.persistence.roles.all()))

    private suspend fun editable(r: ApplicationRuntime, id: String): WorkflowDefinition {
        val definition = r.persistence.definitions.get(WorkflowDefinitionId(id)) ?: fail(ControlErrorCode.NotFound, "No hive $id")
        if (definition.id !in authoredStore.all()) {
            fail(ControlErrorCode.Conflict, "Only hives made in the app or over this interface can be changed here")
        }
        if (allRuns(r).any { it.workflowDefinitionId == definition.id && (it.status == WorkflowRunStatus.Running || it.status == WorkflowRunStatus.AwaitingHuman) }) {
            fail(ControlErrorCode.Conflict, "A run of this hive is live; stop it first")
        }
        return definition
    }

    private suspend fun save(r: ApplicationRuntime, definition: WorkflowDefinition) {
        val errors = WorkflowGraphValidator.validate(definition)
        if (errors.isNotEmpty()) {
            fail(ControlErrorCode.InvalidArgument, "The hive is not valid: " + errors.joinToString(" ") { it.humanReadable() })
        }
        r.persistence.definitions.put(definition)
    }

    private fun roleFor(id: String?, available: List<RoleDefinition>): RoleDefinition {
        val role = available.firstOrNull { it.id.value == id } ?: fail(ControlErrorCode.NotFound, "No switched-on role ${id ?: "(missing roleId)"}")
        if (role.executionSource != com.hereliesaz.geministrator.domain.RoleExecutionSource.Agent) {
            fail(ControlErrorCode.PolicyDenied, "Role ${role.id.value} runs a script or an action, not an agent; bring it in with the app")
        }
        if (role.capabilitiesRequired.any { it in MUTATING_CAPABILITIES }) {
            fail(ControlErrorCode.PolicyDenied, "Role ${role.id.value} can change a repository or run commands; a hive that uses it is made in the app")
        }
        return role
    }

    /** Creatures that wait for a person: a human-approval gate, or an agent whose own approval is human. */
    private fun TaskDefinition.waitsForAPerson(): Boolean =
        executor is TaskExecutor.HumanApproval || approvalPolicy is ApprovalPolicy.HumanApproval

    /** For each creature, the creatures that wait for a person somewhere upstream of it (through any chain of dependencies). */
    private fun approvalAncestors(definition: WorkflowDefinition): Map<TaskDefinitionId, Set<TaskDefinitionId>> {
        val byId = definition.tasks.associateBy { it.id }
        return definition.tasks.associate { task ->
            val seen = mutableSetOf<TaskDefinitionId>()
            val pending = ArrayDeque(task.dependsOn)
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (seen.add(id)) byId[id]?.let { pending.addAll(it.dependsOn) }
            }
            task.id to seen.filterTo(mutableSetOf()) { byId[it]?.waitsForAPerson() == true }
        }
    }

    /**
     * An edit made here may not move a creature out from behind a person's approval (by changing its
     * dependencies, or by removing a creature that links it to one), and a gate may not wait on less than
     * it did. The validator only protects repository operations; this protects the order of the rest.
     */
    private fun refuseRelaxedApprovals(before: WorkflowDefinition, after: WorkflowDefinition) {
        val upstreamBefore = approvalAncestors(before)
        val upstreamAfter = approvalAncestors(after)
        val priorById = before.tasks.associateBy { it.id }
        after.tasks.forEach { task ->
            val was = upstreamBefore[task.id].orEmpty()
            if (!upstreamAfter[task.id].orEmpty().containsAll(was)) {
                fail(ControlErrorCode.PolicyDenied, "'${task.name}' would no longer wait behind a creature that waits for a person; change that in the app")
            }
            val prior = priorById[task.id]
            if (prior != null && prior.executor is TaskExecutor.HumanApproval && !task.dependsOn.containsAll(prior.dependsOn)) {
                fail(ControlErrorCode.PolicyDenied, "An approval gate can be made to wait on more over this interface but not on less; change that in the app")
            }
        }
    }

    private fun buildTask(spec: JsonObject, available: List<RoleDefinition>, taken: MutableSet<String>, known: Set<String> = emptySet()): TaskDefinition {
        val kind = spec.str("kind") ?: "agent"
        val name = spec.str("name")?.trim().orEmpty()
        if (name.isEmpty()) fail(ControlErrorCode.InvalidArgument, "Every creature needs a name")
        val id = uniqueId(spec.str("id")?.let(::slug)?.takeIf { it.isNotEmpty() } ?: slug(name).ifEmpty { "creature" }, taken)
        taken += id
        val deps = strings(spec["dependsOn"], "dependsOn").map(::TaskDefinitionId).toSet()
        val criteria = strings(spec["acceptanceCriteria"], "acceptanceCriteria").map { AcceptanceCriterion(it.trim().take(CRITERION_MAX)) }
        val objective = spec.str("objective")?.trim().orEmpty().take(OBJECTIVE_MAX)
        return when (kind) {
            "agent" -> {
                val role = roleFor(spec.str("roleId"), available)
                val wantsApproval = (spec["requiresHumanApproval"] as? JsonPrimitive)?.booleanOrNull == true
                TaskDefinition(
                    id = TaskDefinitionId(id),
                    name = name.take(NAME_MAX),
                    objective = objective.ifEmpty { role.description.ifBlank { role.name } },
                    roleId = role.id,
                    executor = TaskExecutor.RoleAgent(role.id),
                    dependsOn = deps,
                    acceptanceCriteria = criteria,
                    approvalPolicy = if (wantsApproval) ApprovalPolicy.HumanApproval else ApprovalPolicy.None,
                )
            }
            "approval" -> TaskDefinition(
                id = TaskDefinitionId(id),
                name = name.take(NAME_MAX),
                objective = objective.ifEmpty { "Wait for a person to approve." },
                roleId = null,
                executor = TaskExecutor.HumanApproval(spec.str("label")?.trim()?.take(NAME_MAX)?.ifEmpty { null } ?: "Human approval"),
                dependsOn = deps,
                acceptanceCriteria = criteria,
            )
            else -> fail(ControlErrorCode.InvalidArgument, "kind must be agent or approval; scripts, deployments and repository changes are made in the app")
        }
    }

    private fun requireKeys(o: JsonObject, allowed: Set<String>) {
        val unknown = o.keys - allowed
        if (unknown.isNotEmpty()) {
            fail(ControlErrorCode.InvalidArgument, "Unknown field(s) ${unknown.sorted().joinToString()}; allowed: ${allowed.sorted().joinToString()}")
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun strings(element: JsonElement?, field: String): List<String> {
        if (element == null || element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: fail(ControlErrorCode.InvalidArgument, "$field must be a list of strings")
        return array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: fail(ControlErrorCode.InvalidArgument, "$field must be a list of strings") }
    }

    private fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    private fun uniqueId(stem: String, taken: Set<String>): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) stem else "$stem-$it" }.first { it !in taken }

    private fun clip(text: String): String = if (text.length > STRING_CLIP) text.take(STRING_CLIP) + "…" else text

    private fun clipJson(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> if (element.isString) JsonPrimitive(clip(element.content)) else element
        is JsonArray -> JsonArray(element.map(::clipJson))
        is JsonObject -> JsonObject(element.mapValues { clipJson(it.value) })
    }

    private fun kindOf(task: TaskDefinition): String = when (task.executor) {
        is TaskExecutor.RoleAgent -> "agent"
        is TaskExecutor.HumanApproval -> "approval"
        null -> if (task.roleId != null) "agent" else "unknown"
        else -> "other"
    }

    private fun hiveSummary(d: WorkflowDefinition, authored: Boolean): JsonObject = buildJsonObject {
        put("id", d.id.value)
        put("name", d.name)
        d.description?.let { put("description", clip(it)) }
        put("creatures", d.tasks.size)
        put("authoredInApp", authored)
    }

    private fun hiveDetail(d: WorkflowDefinition, authored: Boolean): JsonObject = JsonObject(
        hiveSummary(d, authored) + mapOf(
            "editableOverControl" to JsonPrimitive(authored),
            "creatureList" to buildJsonArray {
                d.tasks.forEach { t ->
                    add(
                        buildJsonObject {
                            put("id", t.id.value)
                            put("name", t.name)
                            put("kind", kindOf(t))
                            put("objective", clip(t.objective))
                            t.roleId?.let { put("roleId", it.value) }
                            put("dependsOn", JsonArray(t.dependsOn.map { JsonPrimitive(it.value) }))
                            put(
                                "approval",
                                when (t.approvalPolicy) {
                                    ApprovalPolicy.None -> "none"
                                    is ApprovalPolicy.RoleApproval -> "role"
                                    ApprovalPolicy.HumanApproval -> "human"
                                },
                            )
                            put("acceptanceCriteria", JsonArray(t.acceptanceCriteria.map { JsonPrimitive(clip(it.description)) }))
                        },
                    )
                }
            },
        ),
    )

    private fun roleJson(role: RoleDefinition, withInstructions: Boolean): JsonObject = buildJsonObject {
        put("id", role.id.value)
        put("name", role.name)
        put("description", clip(role.description))
        put("enabled", role.enabled)
        put("executionSource", role.executionSource::class.simpleName ?: "Agent")
        put("preferredProviderId", role.preferredProviderId?.value)
        put("authorities", JsonArray(role.authorities.map { JsonPrimitive(it.name) }.sortedBy { it.content }))
        put("capabilitiesRequired", JsonArray(role.capabilitiesRequired.map { JsonPrimitive(it.name) }.sortedBy { it.content }))
        if (withInstructions) put("instructions", clip(role.instructions))
    }

    private fun runSummary(run: WorkflowRun): JsonObject = buildJsonObject {
        put("id", run.id.value)
        put("hiveId", run.workflowDefinitionId.value)
        put("projectId", run.projectId.value)
        put("objective", clip(run.objective))
        put("status", run.status.name)
        put("createdAt", run.createdAtEpochMillis)
        put("updatedAt", run.updatedAtEpochMillis)
        put("heldByHallMonitor", run.globalPause != null)
        put("heldBySpendStop", run.spendPause != null)
        put("creatures", run.taskRuns.size)
        put("creaturesDone", run.taskRuns.values.count { it.status == com.hereliesaz.geministrator.domain.TaskRunStatus.Completed })
    }
}
