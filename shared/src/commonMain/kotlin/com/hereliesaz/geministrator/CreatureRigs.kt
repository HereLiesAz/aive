package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.puppet.CompiledPuppetRig
import com.hereliesaz.geministrator.puppet.PuppetFit
import com.hereliesaz.geministrator.puppet.PuppetPoseBuffer
import com.hereliesaz.geministrator.puppet.PuppetRig
import com.hereliesaz.geministrator.puppet.PuppetRigResources
import com.hereliesaz.geministrator.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Role -> puppet-rig mapping for node creatures. This is the ONE place that decides which rig in
 * `composeResources/files/rigs/` draws which node, and it always decides: [slugFor] never returns
 * null, so every node is drawn with one of the current creature designs.
 *
 * Slugs come from the character directories in `docs/swarm-terrarium/characters/<NNN_role>/`
 * (parts-sheet rigs sliced by `tools/puppet-rig/slice/`; core roles without a parts sheet reuse
 * another role's rig, see [bySlugKind]). Where a role has several sheets the newest one wins:
 * the higher-numbered `sheet_3` directory over `sheet_1`/`sheet_2`, and the last variant of a sheet
 * (`idea-catalyst-2`). Resolution of a node label, first match wins:
 *
 * 1. A label that is exactly a rigged character's name ("Creative Synthesizer", "ML Engineer",
 *    "Orchestrator") gets that rig.
 * 2. A label that is a rig's own name ("creature", "data-miner-sheet-1-variant-1", also written
 *    with spaces or underscores) gets that rig, so every rig is reachable by its file name; this
 *    covers the rigs in [NAMED_ONLY_SLUGS] that no role kind maps to.
 * 3. A Store persona ([MascotCharacterCatalog], 94 of them) wears the creature of its catalog
 *    `rigKind`; that explicit table is the persona -> creature mapping.
 * 4. Any other label is classified ([classifyNodeCreatureRole]); a kind listed in [bySlugKind]
 *    gets its rig.
 * 5. Classifier kinds with no creature of their own use [KIND_FALLBACKS] (data engineer ->
 *    database administrator, storyteller -> communicator, ...), and the deterministic executors
 *    (Python, JavaScript, Test Runner, Deployment, Repository Operation, Nested Workflow) use
 *    [AUTOMATED_PROCESS_RIGS].
 * 6. Unknown/custom roles ([NodeCreatureRoleKind.Generic]) get one of `generic-01..06`, chosen by
 *    a stable hash of the normalized label, so the same custom role always looks the same.
 */
internal object CreatureRigMapping {
    val GENERIC_SLUGS = listOf("generic-01", "generic-02", "generic-03", "generic-04", "generic-05", "generic-06")

    /**
     * Rigs no role kind maps to (older sheets/variants superseded in [bySlugKind], and the
     * unclassified `creature`). Their file name is the role label that selects them.
     */
    val NAMED_ONLY_SLUGS = listOf(
        "data-miner-sheet-1-variant-1",
    )

    /** Classifier kinds that have no creature of their own, and the closest creature to draw. */
    val KIND_FALLBACKS: Map<NodeCreatureRoleKind, String> = mapOf(
        NodeCreatureRoleKind.DataEngineer to "database-administrator",
        NodeCreatureRoleKind.ContentCreator to "creative-synthesizer",
        NodeCreatureRoleKind.TechnicalAuthor to "documentation-specialist",
        NodeCreatureRoleKind.BugReporter to "test-explorer",
        NodeCreatureRoleKind.WorldBuilder to "architect",
        NodeCreatureRoleKind.Storyteller to "communicator",
        NodeCreatureRoleKind.Pathfinder to "strategy-scout",
    )

    /** The deterministic executors (normalized label -> creature). Anything else automated uses [DEFAULT_AUTOMATED_RIG]. */
    val AUTOMATED_PROCESS_RIGS: Map<String, String> = mapOf(
        "javascript" to "process-mapper",
        "python" to "process-mapper",
        "test runner" to "test-automation-engineer",
        "deployment" to "deployment-pilot",
        "repository operation" to "build-engineer",
        "nested workflow" to "flow-director",
    )
    const val DEFAULT_AUTOMATED_RIG = "process-mapper"

    val bySlugKind: Map<NodeCreatureRoleKind, String> = mapOf(
        // Core roles 001-015 without a parts sheet (all but UX designer and architect) wear a creature already
        // rigged for another role, matched to the role's colour; the unused variants first, then rarer roles' rigs.
        NodeCreatureRoleKind.Orchestrator to "creature", // 001
        NodeCreatureRoleKind.UxDesigner to "ux-designer", // 002
        NodeCreatureRoleKind.Researcher to "data-scientist", // 003
        NodeCreatureRoleKind.ImplementationEngineer to "data-miner-sheet-1-variant-2", // 004
        NodeCreatureRoleKind.EpaRepresentative to "optimizer-sheet-2-variant", // 006
        NodeCreatureRoleKind.AdversarialReviewer to "idea-catalyst-1", // 007
        NodeCreatureRoleKind.QaEngineer to "ml-engineer", // 008
        NodeCreatureRoleKind.ReleaseEngineer to "compliance-officer", // 009
        NodeCreatureRoleKind.Antagonist to "threat-modeler", // 010
        NodeCreatureRoleKind.ProductManager to "knowledge-keeper-sheet-1-variant", // 011
        NodeCreatureRoleKind.CrashTestDummy to "scheduler-sheet-2-variant", // 012
        NodeCreatureRoleKind.RecoveryEngineer to "sustainability-advocate", // 013
        NodeCreatureRoleKind.CodeReviewer to "migration-engineer", // 014
        NodeCreatureRoleKind.HallMonitor to "disassembled-glossy-slime", // 015
        NodeCreatureRoleKind.Architect to "architect", // 005
        NodeCreatureRoleKind.DataMiner to "data-miner-sheet-3-variant", // 036 (newer than 016)
        NodeCreatureRoleKind.KnowledgeKeeper to "knowledge-keeper-sheet-2-variant", // 022 (newer than 017)
        NodeCreatureRoleKind.PatternSeeker to "pattern-seeker-sheet-1-variant", // 018
        NodeCreatureRoleKind.Translator to "translator-sheet-1-variant", // 019
        NodeCreatureRoleKind.Connector to "connector-sheet-1-variant", // 020
        NodeCreatureRoleKind.Scheduler to "scheduler-sheet-3-variant", // 040 (newer than 026)
        NodeCreatureRoleKind.Optimizer to "optimizer-sheet-3-variant", // 039 (newer than 027)
        NodeCreatureRoleKind.Simulator to "simulator", // 028
        NodeCreatureRoleKind.Documenter to "documenter", // 029
        NodeCreatureRoleKind.Guardian to "guardian", // 030
        NodeCreatureRoleKind.CreativeSynthesizer to "creative-synthesizer", // 031
        NodeCreatureRoleKind.ResourceManager to "resource-manager-sheet-3-variant", // 041 (032 has no rig)
        NodeCreatureRoleKind.FeedbackListener to "feedback-listener", // 033
        NodeCreatureRoleKind.DecisionHelper to "decision-helper", // 034
        NodeCreatureRoleKind.FlowDirector to "flow-director", // 035
        NodeCreatureRoleKind.PatternSeer to "pattern-seer", // 037
        NodeCreatureRoleKind.Communicator to "communicator", // 042
        NodeCreatureRoleKind.Integrator to "integrator", // 043
        NodeCreatureRoleKind.SecurityAnalyst to "security-analyst", // 044
        NodeCreatureRoleKind.QualityGuardian to "quality-guardian", // 045
        NodeCreatureRoleKind.DocumentationSpecialist to "documentation-specialist", // 046
        NodeCreatureRoleKind.TestExplorer to "test-explorer", // 047
        NodeCreatureRoleKind.DeploymentPilot to "deployment-pilot", // 048
        NodeCreatureRoleKind.MonitoringSentinel to "monitoring-sentinel", // 049
        NodeCreatureRoleKind.FeedbackCollector to "feedback-collector", // 050
        NodeCreatureRoleKind.IdeaCatalyst to "idea-catalyst-2", // 051, newest of two sheets
        NodeCreatureRoleKind.ProcessMapper to "process-mapper", // 052
        NodeCreatureRoleKind.StrategyScout to "strategy-scout", // 053
        NodeCreatureRoleKind.SystemMaintainer to "system-maintainer", // 054
        NodeCreatureRoleKind.SustainabilityAdvocate to "sustainability-advocate", // 055
        NodeCreatureRoleKind.SystemsAnalyst to "systems-analyst", // 056
        NodeCreatureRoleKind.DatabaseAdministrator to "database-administrator", // 057
        NodeCreatureRoleKind.NetworkEngineer to "network-engineer", // 058
        NodeCreatureRoleKind.SiteReliabilityEngineer to "site-reliability-engineer", // 059
        NodeCreatureRoleKind.BuildEngineer to "build-engineer", // 060
        NodeCreatureRoleKind.TestAutomationEngineer to "test-automation-engineer", // 061
        NodeCreatureRoleKind.ThreatModeler to "threat-modeler", // 062
        NodeCreatureRoleKind.ComplianceOfficer to "compliance-officer", // 063
        NodeCreatureRoleKind.MigrationEngineer to "migration-engineer", // 064
        NodeCreatureRoleKind.ObservabilityEngineer to "observability-engineer", // 065
        NodeCreatureRoleKind.BusinessAnalyst to "business-analyst", // 066
        NodeCreatureRoleKind.ContentStrategist to "content-strategist", // 067
        NodeCreatureRoleKind.SupportAgent to "support-agent", // 068
        NodeCreatureRoleKind.DataScientist to "data-scientist", // 069
        NodeCreatureRoleKind.MlEngineer to "ml-engineer", // 070
        NodeCreatureRoleKind.LegalCounsel to "legal-counsel", // 071
    )

    /** Exact character names (`creative synthesizer`, `ml engineer`, ...) -> slug; checked before the classifier. */
    private val byRoleName: Map<String, String> by lazy {
        bySlugKind.entries.associate { (kind, slug) -> displayName(kind) to slug }
    }

    /** Rig slug for a node's role label. Never null: every label resolves to one of the creature designs. */
    fun slugFor(roleLabel: String): String {
        val normalized = roleLabel.trim().lowercase()
        byRoleName[normalized]?.let { return it }
        normalized.replace(LABEL_SEPARATORS, "-").takeIf { it in allSlugs }?.let { return it }
        val kind = MascotCharacterCatalog.explicitForRole(roleLabel)?.rigKind ?: classifyNodeCreatureRole(roleLabel)
        return slugForKind(kind, normalized)
    }

    private fun slugForKind(kind: NodeCreatureRoleKind, normalizedLabel: String): String = when (kind) {
        NodeCreatureRoleKind.Generic -> genericSlugFor(normalizedLabel)
        NodeCreatureRoleKind.AutomatedProcess -> AUTOMATED_PROCESS_RIGS[normalizedLabel] ?: DEFAULT_AUTOMATED_RIG
        else -> bySlugKind[kind] ?: KIND_FALLBACKS[kind] ?: genericSlugFor(normalizedLabel)
    }

    /** Deterministic generic body for an unknown role id/label. */
    fun genericSlugFor(key: String): String {
        val normalized = key.trim().lowercase()
        return GENERIC_SLUGS[stableHash(normalized).mod(GENERIC_SLUGS.size)]
    }

    /** `ContentStrategist` -> `content strategist`. */
    fun displayName(kind: NodeCreatureRoleKind): String =
        kind.name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase()

    /** Stable small per-node phase offset (0..1) so neighbouring creatures don't move in lockstep. */
    fun phaseOffset(seed: String): Float = stableHash(seed.trim().lowercase()).mod(997) / 997f

    private val LABEL_SEPARATORS = Regex("[\\s_]+")

    /** All slugs the mapping can produce. */
    val allSlugs: Set<String> get() = bySlugKind.values.toSet() + NAMED_ONLY_SLUGS + GENERIC_SLUGS

    private fun stableHash(value: String): Int {
        var hash = 0x45D9F3B
        value.forEach { char -> hash = (hash * 31) xor char.code }
        return hash
    }
}

/** A rig ready for live drawing: compiled once, atlas decoded once, shared by every node using it. */
class LoadedCreatureRig(val slug: String, val compiled: CompiledPuppetRig, val atlas: ImageBitmap) {
    val rig: PuppetRig get() = compiled.rig
}

/**
 * Process-wide rig cache. Each slug is read, parsed, compiled and decoded at most once (on
 * [Dispatchers.Default]); failures are remembered as misses ([hasFailed]) so a broken rig is not retried every
 * frame and the node keeps its quiet placeholder. A load that is cancelled (its composable left
 * composition) is not a failure: it is forgotten so the next request retries. At most [MAX_RIGS]
 * decoded atlases stay resident (about 0.7 MB each); the least recently used one is dropped and
 * reloads on demand. [peek] is snapshot state, so composables reading it recompose when a rig
 * finishes loading.
 */
object CreatureRigCache {
    internal const val MAX_RIGS = 64

    private val ready = mutableStateMapOf<String, LoadedCreatureRig>()
    private val failed = mutableStateMapOf<String, Boolean>()
    private val inFlight = HashMap<String, Deferred<LoadedCreatureRig?>>()
    private val lock = Mutex()
    // Use order is touched only from the composition/caller thread (peek and the code after load
    // returns in get), so it needs no lock; commonMain also has no `synchronized`.
    private val lastUsed = HashMap<String, Long>()
    private var useCounter = 0L

    fun peek(slug: String): LoadedCreatureRig? {
        val rig = ready[slug] ?: return null
        lastUsed[slug] = ++useCounter
        return rig
    }

    /** True once loading [slug] failed (snapshot state, so a placeholder reading it recomposes). */
    fun hasFailed(slug: String): Boolean = failed[slug] == true

    suspend fun get(slug: String): LoadedCreatureRig? {
        peek(slug)?.let { return it }
        var owner = false
        val deferred = lock.withLock {
            inFlight.getOrPut(slug) {
                owner = true
                CompletableDeferred()
            }
        }
        if (owner) {
            val completable = deferred as CompletableDeferred<LoadedCreatureRig?>
            try {
                val result = try {
                    load(slug)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (result != null) {
                    ready[slug] = result
                    trim(keep = slug)
                } else {
                    failed[slug] = true
                }
                completable.complete(result)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { lock.withLock { inFlight.remove(slug) } }
                completable.complete(null)
                throw cancelled
            }
        }
        return deferred.await()
    }

    /** Drops least recently used rigs beyond [MAX_RIGS], never [keep]; they reload when next needed. */
    private suspend fun trim(keep: String) {
        val evicted = ArrayList<String>()
        while (ready.size - evicted.size > MAX_RIGS) {
            val victim = ready.keys.filter { it != keep && it !in evicted }
                .minByOrNull { lastUsed[it] ?: 0L } ?: break
            evicted += victim
        }
        evicted.forEach { lastUsed.remove(it) }
        if (evicted.isEmpty()) return
        evicted.forEach { ready.remove(it) }
        lock.withLock { evicted.forEach { inFlight.remove(it) } }
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun load(slug: String): LoadedCreatureRig = withContext(Dispatchers.Default) {
        val rig = PuppetRig.parse(Res.readBytes("${PuppetRigResources.DIRECTORY}/$slug.rig.json").decodeToString())
        val atlas = Res.readBytes("${PuppetRigResources.DIRECTORY}/${rig.atlas.image}").decodeToImageBitmap()
        LoadedCreatureRig(slug, CompiledPuppetRig(rig), atlas)
    }
}

/** Starts loading [slug] (once) and returns it when ready; null while loading or on failure. */
@Composable
internal fun rememberCreatureRig(slug: String): LoadedCreatureRig? {
    LaunchedEffect(slug) { CreatureRigCache.get(slug) }
    return CreatureRigCache.peek(slug)
}

/** Share of the node box the rig artboard may fill; leaves room for the state/name pills. */
internal const val CREATURE_RIG_CONTENT_SCALE = 0.78f

/** Rig phase for a node: the shared terrarium clock plus a stable per-node offset. Same speed as before. */
internal fun creatureRigPhase(motionPhase: Float, seed: String): Float =
    motionPhase + CreatureRigMapping.phaseOffset(seed)

/**
 * Draws a loaded creature rig in a node box. Evaluation is allocation-free per frame
 * ([CompiledPuppetRig.evaluate] into a remembered buffer).
 */
@Composable
internal fun RiggedCreatureSurface(
    loaded: LoadedCreatureRig,
    state: H2g2WorkflowState,
    /** Read inside the draw scope, so a ticking clock invalidates drawing only, not composition. */
    phase: () -> Float,
    modifier: Modifier = Modifier,
    /**
     * Points, in this box's px, the creature's tendrils reach for: one tendril each, the one already
     * facing that way. Read in the draw scope.
     */
    reach: () -> List<TendrilReach> = { emptyList() },
) {
    val compiled = loaded.compiled
    val buffer = remember(compiled) { compiled.newBuffer() }
    val matrix = remember { Matrix() }
    val stateIndex = remember(compiled, state) { compiled.stateIndex(state.name) }
    Canvas(modifier) {
        val fit = PuppetFit.of(compiled.rig.canvas, size.width, size.height, CREATURE_RIG_CONTENT_SCALE)
        buffer.clearReach()
        compiled.evaluate(stateIndex, phase(), buffer)
        val targets = reach()
        val parts = if (targets.isNotEmpty() && compiled.tendrilParts.isNotEmpty()) {
            assignTendrils(compiled, buffer, targets.map { Offset((it.at.x - fit.offsetX) / fit.scale, (it.at.y - fit.offsetY) / fit.scale) })
                .also { compiled.evaluate(stateIndex, phase(), buffer) }
        } else {
            emptyList()
        }
        compiled.draw(this, loaded.atlas, buffer, fit, matrix)
        targets.forEachIndexed { k, target ->
            val part = parts.getOrNull(k) ?: return@forEachIndexed
            val pulse = target.pulse ?: return@forEachIndexed
            compiled.drawPulse(this, loaded.atlas, buffer, fit, matrix, part, pulse, target.pulseColor)
        }
    }
}

/**
 * Gives each artboard target the free tendril pointing most nearly at it, in an evaluated [pose],
 * and returns the part per target. More targets than tendrils: the rest go unreached (null).
 */
internal fun assignTendrils(compiled: CompiledPuppetRig, pose: PuppetPoseBuffer, targets: List<Offset>): List<Int?> {
    val free = compiled.tendrilParts.toMutableList()
    return targets.map { target ->
        val best = free.maxByOrNull { part ->
            val base = compiled.tendrilBase(part, pose)
            val along = compiled.tendrilTip(part, pose) - base
            val toward = target - base
            val n = along.getDistance() * toward.getDistance()
            if (n == 0f) -1f else (along.x * toward.x + along.y * toward.y) / n
        } ?: return@map null
        free -= best
        pose.reachX[best] = target.x
        pose.reachY[best] = target.y
        best
    }
}

/**
 * One tendril's reach: [at] in the creature's box px, and the slice of light travelling it,
 * [pulse] from 0 (base) to 1 (tip), or null when nothing flows.
 */
internal data class TendrilReach(
    val at: Offset,
    val pulse: Float? = null,
    val pulseColor: androidx.compose.ui.graphics.Color = Azphalt.Yellow,
)

/**
 * Where a node's arm socket sits on screen.
 *
 * The node box (side [boxSizePx], centred on [nodeCenter]) is scaled by [zoom] around its centre;
 * the rig artboard is fitted into the box by [PuppetFit] with [CREATURE_RIG_CONTENT_SCALE].
 */
internal fun creatureRigPointToScreen(
    artboardX: Float,
    artboardY: Float,
    rig: PuppetRig,
    boxSizePx: Float,
    nodeCenter: Offset,
    zoom: Float,
    contentScale: Float = CREATURE_RIG_CONTENT_SCALE,
): Offset {
    val fit = PuppetFit.of(rig.canvas, boxSizePx, boxSizePx, contentScale)
    val half = boxSizePx / 2f
    return Offset(
        x = nodeCenter.x + (fit.mapX(artboardX) - half) * zoom,
        y = nodeCenter.y + (fit.mapY(artboardY) - half) * zoom,
    )
}

/**
 * Picks the `arm-socket` attachment facing ([dirX], [dirY]) from the artboard centre, using an
 * evaluated [pose]. Returns its index, or -1 when no socket faces that way (caller falls back to
 * the silhouette-edge anchor).
 */
internal fun pickArmSocket(compiled: CompiledPuppetRig, pose: PuppetPoseBuffer, dirX: Float, dirY: Float): Int {
    val cx = compiled.rig.canvas.width / 2f
    val cy = compiled.rig.canvas.height / 2f
    var best = -1
    var bestDot = 0f
    for (k in 0 until compiled.attachmentCount) {
        if (compiled.attachmentKinds[k] != ARM_SOCKET) continue
        val dot = (pose.attachX[k] - cx) * dirX + (pose.attachY[k] - cy) * dirY
        if (dot > bestDot) {
            bestDot = dot
            best = k
        }
    }
    return best
}

internal const val ARM_SOCKET = "arm-socket"
