package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.inference.CompoundInferenceFabric
import com.hereliesaz.geministrator.inference.GovernedCompoundInferenceFabric
import com.hereliesaz.geministrator.inference.InferenceGenealogyGovernanceRuntime
import com.hereliesaz.geministrator.inference.LocalModelLibrary
import com.hereliesaz.geministrator.inference.SettingsCompoundInferenceFabric
import com.hereliesaz.geministrator.inference.SettingsInferenceGenealogyGraph
import com.hereliesaz.geministrator.inference.SettingsInferenceStateStore
import com.hereliesaz.geministrator.inference.withLocalModelLibrary
import com.hereliesaz.geministrator.memory.MemoryEpoch8LocalModelLibrary
import com.hereliesaz.geministrator.orchestration.AgentRouteCandidate
import com.hereliesaz.geministrator.orchestration.AgentRouteDecision
import com.hereliesaz.geministrator.orchestration.AgentRoutingInput
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationUtilityFamily
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.OnDeviceTextAgentProvider
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.withLock

data class ProviderSelectionRequest(
    val preferredProviderId: AgentProviderId? = null,
    val requiredCapabilities: Set<AgentCapability> = emptySet(),
    val constraints: ProviderConstraints = ProviderConstraints.None,
    val repository: RepositoryRef? = null,
    /**
     * Rough size of the task's input ([OnDeviceTextAgentProvider.estimateTokens]); null when unknown.
     * Only a known size under the on-device provider's limit lets a task go to the device.
     */
    val estimatedInputTokens: Int? = null,
    /**
     * Providers already tried (and failed) for this piece of work; [AgentProviderRegistry.select]
     * skips them unless a provider is required by id.
     */
    val excludedProviderIds: Set<AgentProviderId> = emptySet(),
    /** The task declares tools: only a provider that enforces the allowed set may be chosen. */
    val requiresToolFilter: Boolean = false,
    /**
     * True when the keeper left the provider to the app (no creature pinned to one): a failover-worthy
     * failure then moves the work to another connected provider (ProviderFailover.kt). False for a
     * pinned creature, which reports the failure instead of switching silently.
     */
    val failoverAllowed: Boolean = false,
)

class AgentProviderRegistry(
    providers: Collection<AgentProvider>,
    inferenceFabric: CompoundInferenceFabric? = null,
    genealogyGovernance: InferenceGenealogyGovernanceRuntime? = null,
    private val orchestrationUtilities: LocalOrchestrationUtilityFamily =
        DeterministicLocalOrchestrationUtilities,
    localModelLibrary: LocalModelLibrary = MemoryEpoch8LocalModelLibrary.library,
    /**
     * Backing store for the default fabric and genealogy graph when those are not given; see
     * [com.hereliesaz.geministrator.persistence.WorkflowPersistence.inferenceSettings]. Null means
     * the platform's durable store, opened only if a default is actually needed.
     */
    inferenceSettings: Settings? = null,
    /** Which providers are passed over for now after a failover-worthy failure. */
    private val bench: ProviderBench = ProviderBench(),
    /** Keeps keyless Sources inside the request limits their catalog text states. */
    private val keylessPacer: KeylessPacer = KeylessPacer(),
) {
    private val defaultSettings: Settings by lazy { inferenceSettings ?: durableInferenceSettings() }

    val genealogyGovernance: InferenceGenealogyGovernanceRuntime = genealogyGovernance
        ?: InferenceGenealogyGovernanceRuntime(graph = SettingsInferenceGenealogyGraph(defaultSettings))
    private val defaultedFabric: CompoundInferenceFabric = inferenceFabric
        ?: SettingsCompoundInferenceFabric(SettingsInferenceStateStore(defaultSettings))

    val inferenceFabric: CompoundInferenceFabric = GovernedCompoundInferenceFabric(
        delegate = defaultedFabric.withLocalModelLibrary(localModelLibrary),
        governance = this.genealogyGovernance,
    )
    private val providersById = providers.associateBy { it.id }

    /** Per-provider outcomes this process has seen: failures in a row and the last runs' results. */
    private data class ProviderHealth(val consecutiveFailures: Int = 0, val recent: List<Boolean> = emptyList())

    private val healthMutex = kotlinx.coroutines.sync.Mutex()

    /** The pacer under the registry's own lock, for callers outside the registry (the queen's model calls) to keep the same Source limits. */
    val sourcePacing: KeylessPacing = KeylessPacing(keylessPacer, healthMutex)

    /** How many Auto selections were spread so far; guarded by [healthMutex]. */
    private var autoRotation = 0
    private val health = HashMap<AgentProviderId, ProviderHealth>()

    /** Records how a provider run ended; feeds the router's circuit breaker and reliability. */
    suspend fun recordOutcome(id: AgentProviderId, success: Boolean) = healthMutex.withLock {
        if (success) bench.clear(id.value)
        val current = health[id] ?: ProviderHealth()
        health[id] = ProviderHealth(
            consecutiveFailures = if (success) 0 else current.consecutiveFailures + 1,
            recent = (current.recent + success).takeLast(HEALTH_WINDOW),
        )
    }

    /** Failures in a row this process has recorded against [id]; what the router's circuit breaker reads. */
    internal suspend fun consecutiveFailuresOf(id: AgentProviderId): Int = healthOf(id).consecutiveFailures

    private val _failoverNotes = kotlinx.coroutines.flow.MutableStateFlow<List<FailoverNote>>(emptyList())

    /** The most recent notes saying work moved to another provider, oldest first, bounded. */
    val failoverNotes: kotlinx.coroutines.flow.StateFlow<List<FailoverNote>> = _failoverNotes

    @OptIn(kotlin.time.ExperimentalTime::class)
    fun recordFailoverNote(text: String) {
        _failoverNotes.value = (_failoverNotes.value + FailoverNote(text, kotlin.time.Clock.System.now().toEpochMilliseconds()))
            .takeLast(MAX_FAILOVER_NOTES)
    }

    /** The keeper-facing name of a provider; the catalog's name when it has one. */
    fun displayName(id: AgentProviderId): String =
        com.hereliesaz.geministrator.ProviderCatalog.entry(id.value)?.displayName ?: id.value

    /**
     * Records a failed provider run with its [raw] reason. A failover-worthy reason (credit, key,
     * rate limit, offline, timeout, 5xx) benches the provider for a cooldown; returns that failure,
     * or null when the reason is not the provider's own and nothing is benched.
     */
    suspend fun recordFailure(id: AgentProviderId, raw: String?): ProviderFailure? {
        recordOutcome(id, success = false)
        val failure = ProviderFailover.failureOf(id.value, displayName(id), raw)
        if (!ProviderFailover.shouldFailOver(failure.kind)) return null
        healthMutex.withLock { bench.bench(failure) }
        return failure
    }

    /**
     * How [raw] would be classified for [id], without recording anything: the failure when it is the
     * provider's own (a reason to fail over), null otherwise. For a failure already recorded.
     */
    fun classifyFailure(id: AgentProviderId, raw: String?): ProviderFailure? =
        ProviderFailover.failureOf(id.value, displayName(id), raw).takeIf { ProviderFailover.shouldFailOver(it.kind) }

    /** The last failover-worthy failure of each of [ids] that has one, for an all-failed message. */
    suspend fun failuresOf(ids: Collection<AgentProviderId>): List<ProviderFailure> =
        healthMutex.withLock { ids.mapNotNull { bench.lastFailure(it.value) } }

    /** True when more than one provider could be chosen automatically, so failover can mean anything. */
    fun hasAlternatives(): Boolean =
        providersById.values.count { !it.explicitOnly && it !is OnDeviceTextAgentProvider } > 1

    private suspend fun healthOf(id: AgentProviderId): ProviderHealth = healthMutex.withLock { health[id] ?: ProviderHealth() }

    init {
        require(providersById.size == providers.size) { "Provider IDs must be unique" }
    }

    val providerIds: Set<AgentProviderId> get() = providersById.keys

    fun provider(id: AgentProviderId): AgentProvider? = providersById[id]

    /** The linked keyless Sources that have no room under their stated request limit now (the queen's calls yield to a creature waiting for one). */
    suspend fun keylessWithoutRoom(): Set<String> = healthMutex.withLock {
        providersById.values.filter { it.keyless && !keylessPacer.hasRoom(it.id.value) }.mapTo(mutableSetOf()) { it.id.value }
    }

    /** The Sources the queen's planner and reviewer could be answered by: those [select] may choose on its own. */
    fun linkedSourceIds(): List<String> =
        providersById.values.filterNot { it.explicitOnly || it is OnDeviceTextAgentProvider }.map { it.id.value }

    /**
     * Whether a provider [select] may choose on its own supports every capability in [required]. The
     * on-device provider does not count: it takes only small tasks, so it staffs no role by itself.
     */
    suspend fun canStaff(required: Set<AgentCapability>, repository: RepositoryRef? = null): Boolean =
        providersById.values
            .filterNot { it.explicitOnly || it is OnDeviceTextAgentProvider }
            .any { provider ->
                readableCapabilities(provider)?.supported?.containsAll(required) == true &&
                    provider.supportsRepository(repository)
            }

    /**
     * What [provider] says it can do, or null when it cannot say (it threw): one provider's fault
     * must not fail the choice among the others. Cancellation still propagates.
     */
    private suspend fun readableCapabilities(provider: AgentProvider): AgentCapabilities? = try {
        provider.capabilities()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** Counts a request given to a keyless provider against its limit. */
    private suspend fun notePicked(provider: AgentProvider) {
        if (provider.keyless) healthMutex.withLock { keylessPacer.record(provider.id.value) }
    }

    suspend fun select(request: ProviderSelectionRequest): AgentProvider {
        val required = buildSet {
            addAll(request.requiredCapabilities)
            val constraints = request.constraints
            if (constraints is ProviderConstraints.RequireCapabilities) {
                addAll(constraints.capabilities)
            }
        }
        val repositoryAccessRequired =
            AgentCapability.RepositoryRead in required || AgentCapability.RepositoryWrite in required

        when (val constraints = request.constraints) {
            is ProviderConstraints.RequireProvider -> {
                val provider = providersById[constraints.providerId]
                    ?: error("Required provider ${constraints.providerId.value} is not registered")
                if (!provider.capabilities().supported.containsAll(required)) {
                    error("Provider ${constraints.providerId.value} does not satisfy required capabilities $required")
                }
                if (request.requiresToolFilter && !provider.enforcesToolFilter) {
                    error("Provider ${constraints.providerId.value} cannot limit a step to its allowed tools")
                }
                if (!provider.supportsRepository(request.repository)) {
                    error(
                        if (request.repository == null) {
                            "Provider ${constraints.providerId.value} works only inside a linked repository"
                        } else {
                            "Provider ${constraints.providerId.value} cannot operate in the context of the linked " +
                                "${request.repository.source.displayName()} repository"
                        },
                    )
                }
                return provider
            }
            else -> Unit
        }

        // The on-device provider is a candidate only for a small task that needs no repository access
        // (docs/Orchestration_layer.md, Agent Router); then it goes ahead of every provider but the
        // role's own preferred one. Otherwise it is left out, even as the role's preferred provider.
        fun onDeviceFits(provider: AgentProvider): Boolean {
            if (provider !is OnDeviceTextAgentProvider) return true
            val estimate = request.estimatedInputTokens ?: return false
            return !repositoryAccessRequired && estimate <= provider.maxInputTokens && provider.isAvailable()
        }

        // Explicit-only providers join the candidates only as the role's preferred provider.
        val ordered = buildList {
            request.preferredProviderId?.let { providersById[it] }?.takeIf(::onDeviceFits)?.let(::add)
            providersById.values.forEach { provider ->
                if (provider is OnDeviceTextAgentProvider && provider !in this && onDeviceFits(provider)) add(provider)
            }
            providersById.values.forEach { provider ->
                if (provider !in this && !provider.explicitOnly && provider !is OnDeviceTextAgentProvider) add(provider)
            }
        }

        val eligibleProviders = mutableListOf<Pair<AgentProvider, Set<AgentCapability>>>()
        val unreadable = mutableListOf<AgentProvider>()
        for (provider in ordered) {
            if (provider.id in request.excludedProviderIds) continue
            if (request.requiresToolFilter && !provider.enforcesToolFilter) continue
            val capabilities = readableCapabilities(provider)?.supported
            if (capabilities == null) {
                unreadable += provider
                continue
            }
            // Asked even without a repository: an agent that only works inside one (OpenCode on
            // GitHub, a local or GitLab workspace) says no to repository-less work.
            if (capabilities.containsAll(required) && provider.supportsRepository(request.repository)) {
                eligibleProviders += provider to capabilities
            }
        }

        // A creature pinned to a provider is never moved to another one: not while that provider is benched
        // for a failure, and not by the router's circuit breaker. The task is started on it, and fails with
        // its own reason and through the creature's own retry and escalation policy if it is still unwell
        // (ProviderFailover: "a pinned creature reports the failure instead of switching silently").
        request.preferredProviderId
            ?.let { pin -> eligibleProviders.firstOrNull { (provider, _) -> provider.id == pin } }
            ?.let { pinned -> return pinned.also { notePicked(it.first) }.first }

        // A pin that cannot be met (its provider is not connected) is never met by a keyless Source: what a
        // keyless gateway is sent leaves the device, and the keeper chose another provider for this creature.
        if (request.preferredProviderId != null) eligibleProviders.removeAll { (provider, _) -> provider.keyless }

        // Providers on the bench (failed lately for a reason that is theirs) go last: the router sees
        // only healthy ones while any exists, and the benched ones only when nothing else can do it. Keyless
        // Sources come after every keyed one, and only while under the limit their catalog text states.
        if (eligibleProviders.size > 1) {
            val preferred = healthMutex.withLock {
                preferKeyedThenKeyless(
                    eligibleProviders.toList(),
                    isBenched = bench::isBenched,
                    hasRoom = keylessPacer::hasRoom,
                )
            }
            eligibleProviders.clear()
            eligibleProviders.addAll(preferred)
        }

        // Auto work spreads over every healthy provider that can do it, one after another, instead of
        // all of it going to whichever registered first: a swarm then uses all the providers its
        // keeper connected (docs/Orchestration_layer.md, Provider spread). A role's own preferred
        // provider, and the on-device one where it fits, keep the front.
        if (request.preferredProviderId == null && eligibleProviders.size > 1) {
            val spread = healthMutex.withLock { spreadAuto(eligibleProviders.toList(), autoRotation++) }
            eligibleProviders.clear()
            eligibleProviders.addAll(spread)
        }

        if (eligibleProviders.isNotEmpty()) {
            val route = orchestrationUtilities.routeAgent(
                AgentRoutingInput(
                    requiredCapabilities = required.mapTo(linkedSetOf()) { it.name },
                    requiredContextTokens = request.estimatedInputTokens?.coerceAtLeast(0) ?: 0,
                    candidates = eligibleProviders.mapIndexed { index, (provider, capabilities) ->
                        val health = healthOf(provider.id)
                        AgentRouteCandidate(
                            id = provider.id.value,
                            capabilities = capabilities.mapTo(linkedSetOf()) { it.name },
                            contextLimitTokens = (provider as? OnDeviceTextAgentProvider)?.maxInputTokens ?: Int.MAX_VALUE,
                            // Preserve current preferred/registration ordering unless a specialist router overrides it.
                            preferenceRank = index,
                            consecutiveFailures = health.consecutiveFailures,
                            successRate = health.recent.takeIf { it.isNotEmpty() }?.let { runs -> runs.count { it }.toDouble() / runs.size },
                        )
                    },
                    requiredContextType = if (request.repository == null) "task" else "repository-task",
                ),
            )
            if (route.decision == AgentRouteDecision.Local) {
                val selected = route.selectedAgent
                    ?: error("Local agent routing returned no selected provider")
                return (
                    eligibleProviders.firstOrNull { (provider, _) -> provider.id.value == selected }?.first
                        ?: error("Local agent routing selected unavailable provider $selected")
                    ).also { notePicked(it) }
            }
        }

        val repositorySuffix = if (request.repository != null) {
            if (repositoryAccessRequired) {
                " for linked ${request.repository.source.displayName()} repository"
            } else {
                " in the context of linked ${request.repository.source.displayName()} repository"
            }
        } else {
            ""
        }
        val explicitOnlyHint = providersById.values
            .filter { it.explicitOnly && it !in ordered }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = ". Not chosen automatically: ", postfix = " (assign to a role to use)") { it.id.value }
            .orEmpty()
        val unreadableHint = unreadable
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = ". Could not read the capabilities of: ") { it.id.value }
            .orEmpty()
        error("No agent provider satisfies required capabilities $required$repositorySuffix$explicitOnlyHint$unreadableHint")
    }
}

private const val HEALTH_WINDOW = 20
private const val MAX_FAILOVER_NOTES = 20

/** The app's durable inference store (platform default [Settings]). */
/**
 * [eligible] with its remote providers turned [turn] places, so successive Auto tasks start from
 * successive providers. The on-device provider, which fits only small work and is meant to go first,
 * stays in front. Pure.
 */
internal fun <T> spreadAuto(eligible: List<Pair<AgentProvider, T>>, turn: Int): List<Pair<AgentProvider, T>> {
    val (front, rest) = eligible.partition { (provider, _) -> provider is OnDeviceTextAgentProvider }
    if (rest.size < 2) return eligible
    val shift = turn.mod(rest.size)
    return front + rest.drop(shift) + rest.take(shift)
}

internal fun durableInferenceSettings(): Settings = chunkedInferenceSettings(Settings())

internal fun chunkedInferenceSettings(delegate: Settings): Settings = ChunkedStringSettings(
    delegate = delegate,
    chunkedKeys = setOf(
        SettingsInferenceStateStore.DEFAULT_STORAGE_KEY,
        SettingsInferenceGenealogyGraph.DEFAULT_STORAGE_KEY,
    ),
)
