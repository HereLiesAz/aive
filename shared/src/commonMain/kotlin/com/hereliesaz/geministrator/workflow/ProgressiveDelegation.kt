package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.RoleSurface
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Progressive delegation (docs/THREAT_MODEL.md, Progressive delegation). When the keeper keeps
 * approving the same non-mutating gate for the same role on the same provider, The Aive offers,
 * once, to stop asking. Accepted, those gates approve themselves and say so in the run's events.
 *
 * Only [DelegationPolicy.eligibility] decides what may be delegated, and only a gate it calls
 * eligible ever yields a [DelegationKey]. Mutating gates (invariant 4: HumanApproval tasks,
 * repository mutations, deployments, external writes, and gates raised by context taint) never do.
 */

/**
 * The gate kinds a keeper can delegate. Only kinds that cannot let anything change a repository,
 * deploy, or write elsewhere belong here; adding one is a threat-model decision.
 */
@Serializable
enum class DelegableGateKind {
    /** A plan drafted by a role that cannot write: approving it only lets the role start thinking. */
    PlanApproval,
}

/** One delegable habit: this role's [gateKind] gates on this provider. Made only by [DelegationPolicy]. */
@ConsistentCopyVisibility
@Serializable
data class DelegationKey internal constructor(
    val roleId: String,
    val gateKind: DelegableGateKind,
    val providerId: String,
)

/** What delegation needs to know about one gate the keeper is asked to decide. */
data class GateDelegationContext(
    val gateKind: ApprovalGateKind,
    /** The task's resolved executor (its run's executor when it has one). */
    val executor: TaskExecutor,
    val role: RoleDefinition?,
    /** The authority the task exercises, when the workflow states it. */
    val requiredRoleAuthority: RoleAuthority?,
    val providerId: AgentProviderId?,
    /** What the assigned provider declares it can do; null when unknown. */
    val providerCapabilities: Set<AgentCapability>?,
    /** The gate was raised because untrusted context reached the task. */
    val raisedByContextTaint: Boolean = false,
)

sealed interface DelegationEligibility {
    data class Eligible(val key: DelegationKey) : DelegationEligibility

    data class Ineligible(val reason: String) : DelegationEligibility
}

object DelegationPolicy {
    /**
     * The single check for whether a gate may ever be delegated. Anything that could let work
     * change a repository, deploy, or write outside The Aive is ineligible, as is anything unknown.
     */
    fun eligibility(context: GateDelegationContext): DelegationEligibility {
        if (context.raisedByContextTaint) return DelegationEligibility.Ineligible("Raised by context taint")
        val kind = delegableKind(context.gateKind)
            ?: return DelegationEligibility.Ineligible("${context.gateKind} gates are never delegated")
        val roleId = when (val executor = context.executor) {
            is TaskExecutor.RoleAgent -> executor.roleId
            is TaskExecutor.HumanApproval -> return DelegationEligibility.Ineligible("Human approval tasks are never delegated")
            is TaskExecutor.RepositoryOperation -> return DelegationEligibility.Ineligible("Repository operations are never delegated")
            is TaskExecutor.Deployment -> return DelegationEligibility.Ineligible("Deployments are never delegated")
            is TaskExecutor.ExternalService -> return DelegationEligibility.Ineligible("External services are never delegated")
            is TaskExecutor.GitHubAction,
            is TaskExecutor.Script,
            is TaskExecutor.TestRunner,
            -> return DelegationEligibility.Ineligible("Executors that run code are never delegated")
            is TaskExecutor.NestedWorkflow -> return DelegationEligibility.Ineligible("Nested workflows are never delegated")
            is TaskExecutor.Distributed -> return DelegationEligibility.Ineligible("Work placed on another device is never delegated")
        }
        val role = context.role ?: return DelegationEligibility.Ineligible("Unknown role")
        if (role.id != roleId) return DelegationEligibility.Ineligible("Role does not match the task's executor")
        if (role.executionSource != RoleExecutionSource.Agent) {
            return DelegationEligibility.Ineligible("Role runs code rather than an agent")
        }
        if (role.authorities.any(RoleAuthority::mutates)) {
            return DelegationEligibility.Ineligible("Role can write, integrate or release")
        }
        if (context.requiredRoleAuthority?.mutates() == true) {
            return DelegationEligibility.Ineligible("Task exercises a writing authority")
        }
        if (role.capabilitiesRequired.any(AgentCapability::mutates)) {
            return DelegationEligibility.Ineligible("Role needs a capability that writes or runs code")
        }
        if (role.surfaces.any(RoleSurface::writes)) {
            return DelegationEligibility.Ineligible("Role writes to an external surface")
        }
        val providerId = context.providerId ?: return DelegationEligibility.Ineligible("No provider")
        val capabilities = context.providerCapabilities
            ?: return DelegationEligibility.Ineligible("Provider capabilities are unknown")
        if (capabilities.any(AgentCapability::mutates)) {
            return DelegationEligibility.Ineligible("Provider can write to a repository or run code")
        }
        return DelegationEligibility.Eligible(DelegationKey(roleId.value, kind, providerId.value))
    }

    /** The delegable counterpart of [kind], or null: every other gate kind always asks. */
    fun delegableKind(kind: ApprovalGateKind): DelegableGateKind? = when (kind) {
        ApprovalGateKind.PlanApproval -> DelegableGateKind.PlanApproval
        ApprovalGateKind.ScopeChange,
        ApprovalGateKind.SecurityRisk,
        ApprovalGateKind.IntegrationApproval,
        ApprovalGateKind.ReleaseApproval,
        ApprovalGateKind.FailureEscalation,
        ApprovalGateKind.HallMonitorOrchestratorReview,
        ApprovalGateKind.HallMonitorHumanReview,
        -> null
    }
}

/** Authorities that let a role change a repository or release. Exhaustive, so new ones must be classified. */
internal fun RoleAuthority.mutates(): Boolean = when (this) {
    RoleAuthority.Implement,
    RoleAuthority.AuthorTests,
    RoleAuthority.ApproveIntegration,
    RoleAuthority.ApproveRelease,
    -> true
    RoleAuthority.ProposePlan,
    RoleAuthority.RejectPlan,
    RoleAuthority.ApprovePlan,
    RoleAuthority.SelectEnvironment,
    RoleAuthority.Verify,
    RoleAuthority.ReviewCode,
    RoleAuthority.DiagnoseFailure,
    -> false
}

/** Capabilities that write to a repository or run code. Exhaustive, so new ones must be classified. */
internal fun AgentCapability.mutates(): Boolean = when (this) {
    AgentCapability.RepositoryWrite,
    AgentCapability.ShellExecution,
    AgentCapability.Testing,
    AgentCapability.TestAuthoring,
    AgentCapability.PullRequestCreation,
    -> true
    AgentCapability.RepositoryRead,
    AgentCapability.PlanGeneration,
    AgentCapability.PlanApproval,
    AgentCapability.Messaging,
    AgentCapability.Research,
    AgentCapability.EnvironmentPlanning,
    -> false
}

internal fun RoleSurface.writes(): Boolean = when (this) {
    is RoleSurface.Spreadsheet -> writable
    is RoleSurface.Sql -> writable
    is RoleSurface.Flowchart -> false
}

const val DEFAULT_DELEGATION_THRESHOLD: Int = 5
const val MIN_DELEGATION_THRESHOLD: Int = 2
const val MAX_DELEGATION_THRESHOLD: Int = 20

/** The keeper's habit for one [key]. */
@Serializable
data class DelegationRecord(
    val key: DelegationKey,
    /** The role's name when last seen, for the Keeper's room. */
    val roleName: String,
    /** Approvals in a row since the last rejection or revocation. */
    val consecutiveApprovals: Int = 0,
    /** This streak has already been offered for delegation (accepted or not). */
    val offered: Boolean = false,
    val delegated: Boolean = false,
    val delegatedAtEpochMillis: Long? = null,
)

/** Every habit, the offer waiting on the keeper, and how many approvals in a row earn an offer. */
@Serializable
data class ProgressiveDelegationState(
    val threshold: Int = DEFAULT_DELEGATION_THRESHOLD,
    val records: List<DelegationRecord> = emptyList(),
    val pendingOffer: DelegationKey? = null,
) {
    fun record(key: DelegationKey): DelegationRecord? = records.firstOrNull { it.key == key }

    fun isDelegated(key: DelegationKey): Boolean = record(key)?.delegated == true

    val delegations: List<DelegationRecord> get() = records.filter(DelegationRecord::delegated)

    val pendingOfferRecord: DelegationRecord? get() = pendingOffer?.let(::record)

    /** The keeper approved [key]'s gate. Reaching [threshold] in a row offers delegation, once per streak. */
    fun afterApproval(key: DelegationKey, roleName: String): ProgressiveDelegationState {
        val current = record(key) ?: DelegationRecord(key = key, roleName = roleName)
        val streak = current.consecutiveApprovals + 1
        val offerNow = !current.delegated && !current.offered && pendingOffer == null && streak >= threshold
        val next = current.copy(
            roleName = roleName,
            consecutiveApprovals = streak,
            offered = current.offered || offerNow,
        )
        return withRecord(next).copy(pendingOffer = if (offerNow) key else pendingOffer)
    }

    /**
     * The keeper rejected a gate of [roleId] (on [providerId], or on any provider when null): every
     * matching streak starts over, any matching delegation ends, and a matching offer is withdrawn.
     */
    fun afterRejection(roleId: String, providerId: String?): ProgressiveDelegationState {
        fun matches(key: DelegationKey) = key.roleId == roleId && (providerId == null || key.providerId == providerId)
        return copy(
            records = records.map { record ->
                if (matches(record.key)) record.restarted() else record
            },
            pendingOffer = pendingOffer?.takeUnless(::matches),
        )
    }

    /** The keeper accepted the pending offer for [key]. Nothing else can create a delegation. */
    fun accept(key: DelegationKey, nowEpochMillis: Long): ProgressiveDelegationState {
        if (pendingOffer != key) return this
        val current = record(key) ?: return copy(pendingOffer = null)
        return withRecord(current.copy(delegated = true, delegatedAtEpochMillis = nowEpochMillis))
            .copy(pendingOffer = null)
    }

    /** The keeper declined: keep asking, and do not offer again during this streak. */
    fun decline(key: DelegationKey): ProgressiveDelegationState =
        if (pendingOffer == key) copy(pendingOffer = null) else this

    /** The keeper revoked [key]: its gates ask again, and a new streak starts. */
    fun revoke(key: DelegationKey): ProgressiveDelegationState {
        val current = record(key) ?: return this
        return withRecord(current.restarted())
    }

    fun withThreshold(value: Int): ProgressiveDelegationState =
        copy(threshold = value.coerceIn(MIN_DELEGATION_THRESHOLD, MAX_DELEGATION_THRESHOLD))

    private fun withRecord(record: DelegationRecord): ProgressiveDelegationState {
        val index = records.indexOfFirst { it.key == record.key }
        return copy(records = if (index < 0) records + record else records.toMutableList().also { it[index] = record })
    }

    private fun DelegationRecord.restarted(): DelegationRecord = copy(
        consecutiveApprovals = 0,
        offered = false,
        delegated = false,
        delegatedAtEpochMillis = null,
    )
}

/**
 * Persists [ProgressiveDelegationState] in the platform's settings store as one JSON value. It is
 * deliberately not part of the setup file or any export: a delegation is only ever granted here,
 * by the keeper accepting an offer on this device.
 */
class ProgressiveDelegationStore(
    private val settings: Settings,
    private val storageKey: String = DEFAULT_KEY,
) {
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<ProgressiveDelegationState> = mutable.asStateFlow()

    fun isDelegated(key: DelegationKey): Boolean = mutable.value.isDelegated(key)

    fun recordApproval(key: DelegationKey, roleName: String) = update { it.afterApproval(key, roleName) }

    fun recordRejection(roleId: String, providerId: String?) = update { it.afterRejection(roleId, providerId) }

    fun accept(key: DelegationKey, nowEpochMillis: Long) = update { it.accept(key, nowEpochMillis) }

    fun decline(key: DelegationKey) = update { it.decline(key) }

    fun revoke(key: DelegationKey) = update { it.revoke(key) }

    fun setThreshold(value: Int) = update { it.withThreshold(value) }

    private fun update(transform: (ProgressiveDelegationState) -> ProgressiveDelegationState): ProgressiveDelegationState {
        val next = mutable.updateAndGet(transform)
        settings.putString(storageKey, json.encodeToString(ProgressiveDelegationState.serializer(), next))
        return next
    }

    /** Unreadable state falls back to none: every gate asks again. */
    private fun load(): ProgressiveDelegationState =
        settings.getStringOrNull(storageKey)
            ?.let { runCatching { json.decodeFromString(ProgressiveDelegationState.serializer(), it) }.getOrNull() }
            ?.let { it.withThreshold(it.threshold) }
            ?: ProgressiveDelegationState()

    companion object {
        const val DEFAULT_KEY: String = "aive.approvals.progressive-delegation.v1"

        fun createDefault(): ProgressiveDelegationStore = ProgressiveDelegationStore(Settings())

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
