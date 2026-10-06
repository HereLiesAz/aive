package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Role -> puppet-rig mapping for node creatures. This is the ONE place that decides which rig in
 * `composeResources/files/rigs/` draws which node.
 *
 * Slugs come from the character directories in `docs/swarm-terrarium/characters/<NNN_role>/` as
 * sliced by `tools/puppet-rig/slice/` (see its `rigs-report.json`). Where a role has several sheets
 * the newest one wins: the higher-numbered `sheet_3` directory over `sheet_1`/`sheet_2`, and the
 * last variant of a sheet (`idea-catalyst-2`). Resolution of a node label:
 *
 * 1. A label that is exactly a rigged character's name ("Creative Synthesizer", "ML Engineer")
 *    gets that rig, even where the fuzzy classifier would file it under a neighbouring kind.
 * 2. Otherwise the label is resolved exactly as the old renderer does it ([MascotCharacterCatalog]
 *    then [classifyNodeCreatureRole]) and a role kind listed in [bySlugKind] gets its rig.
 * 3. A Store persona (explicit catalog entry) keeps its bespoke phenotype unless its label *is*
 *    the rigged role's name (e.g. "Content Strategist"); the catalog forbids collapsing distinct
 *    personas into one shared body, so "Brand Strategist" is not drawn as the content strategist.
 * 4. Unknown/custom roles ([NodeCreatureRoleKind.Generic]) get one of `generic-01..06`, chosen by
 *    a stable hash of the normalized label, so the same custom role always looks the same.
 * 5. Anything else (no sheet was sliced yet: orchestrator, researcher, QA, ...; UX designer, whose
 *    hand-transcribed sample rig draws much smaller than its bespoke surface) returns null and
 *    stays on the existing sprite/procedural rendering.
 */
internal object CreatureRigMapping {
    val GENERIC_SLUGS = listOf("generic-01", "generic-02", "generic-03", "generic-04", "generic-05", "generic-06")

    val bySlugKind: Map<NodeCreatureRoleKind, String> = mapOf(
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

    /** Rig slug for a node's role label, or null to keep the pre-rig rendering. */
    fun slugFor(roleLabel: String): String? {
        val normalized = roleLabel.trim().lowercase()
        if (normalized.isEmpty()) return null
        byRoleName[normalized]?.let { return it }
        val character = MascotCharacterCatalog.forRole(roleLabel)
        val kind = character?.motionArchetype ?: classifyNodeCreatureRole(roleLabel)
        if (kind == NodeCreatureRoleKind.Generic) return genericSlugFor(normalized)
        val slug = bySlugKind[kind] ?: return null
        val isPersona = character != null && MascotCharacterCatalog.explicitForRole(roleLabel) != null
        return if (isPersona) null else slug
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

    /** All slugs the mapping can produce. */
    val allSlugs: Set<String> get() = bySlugKind.values.toSet() + GENERIC_SLUGS

    private fun stableHash(value: String): Int {
        var hash = 0x45D9F3B
        value.forEach { char -> hash = (hash * 31) xor char.code }
        return hash
    }
}

/** User-facing switch (debug/settings) that forces the pre-rig node rendering. */
object CreatureRigSettings {
    var enabled: Boolean by mutableStateOf(true)
}

/** A rig ready for live drawing: compiled once, atlas decoded once, shared by every node using it. */
class LoadedCreatureRig(val slug: String, val compiled: CompiledPuppetRig, val atlas: ImageBitmap) {
    val rig: PuppetRig get() = compiled.rig
}

/**
 * Process-wide rig cache. Each slug is read, parsed, compiled and decoded at most once (on
 * [Dispatchers.Default]); failures are remembered as misses so a broken rig is not retried every
 * frame and the node keeps its old rendering. [peek] is snapshot state, so composables reading it
 * recompose when a rig finishes loading.
 */
object CreatureRigCache {
    private val ready = mutableStateMapOf<String, LoadedCreatureRig>()
    private val inFlight = HashMap<String, Deferred<LoadedCreatureRig?>>()
    private val lock = Mutex()

    fun peek(slug: String): LoadedCreatureRig? = ready[slug]

    suspend fun get(slug: String): LoadedCreatureRig? {
        ready[slug]?.let { return it }
        var owner = false
        val deferred = lock.withLock {
            inFlight.getOrPut(slug) {
                owner = true
                CompletableDeferred()
            }
        }
        if (owner) {
            val result = runCatching { load(slug) }.getOrNull()
            if (result != null) ready[slug] = result
            (deferred as CompletableDeferred).complete(result)
        }
        return deferred.await()
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
internal fun rememberCreatureRig(slug: String?): LoadedCreatureRig? {
    if (slug == null) return null
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
    phase: Float,
    modifier: Modifier = Modifier,
) {
    val compiled = loaded.compiled
    val buffer = remember(compiled) { compiled.newBuffer() }
    val matrix = remember { Matrix() }
    val stateIndex = remember(compiled, state) { compiled.stateIndex(state.name) }
    Canvas(modifier) {
        compiled.evaluate(stateIndex, phase, buffer)
        val fit = PuppetFit.of(compiled.rig.canvas, size.width, size.height, CREATURE_RIG_CONTENT_SCALE)
        compiled.draw(this, loaded.atlas, buffer, fit, matrix)
    }
}

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
