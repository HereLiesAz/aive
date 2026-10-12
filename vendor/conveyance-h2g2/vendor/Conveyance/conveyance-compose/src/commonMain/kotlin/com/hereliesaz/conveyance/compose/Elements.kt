package com.hereliesaz.conveyance.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import com.hereliesaz.conveyance.Act
import com.hereliesaz.conveyance.ActEmphasis
import com.hereliesaz.conveyance.ActId
import com.hereliesaz.conveyance.ActState
import com.hereliesaz.conveyance.AuditElement
import com.hereliesaz.conveyance.AuditFrame
import com.hereliesaz.conveyance.Census
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Employment
import com.hereliesaz.conveyance.Job
import com.hereliesaz.conveyance.SubjectId
import com.hereliesaz.conveyance.Weight

/** Where a named element is, and whether the person can currently see it. */
@Immutable
data class Placement(
    val bounds: Rect,
    val visible: Boolean,
)

/**
 * Where every named element currently is.
 *
 * The registry resolves relationships the model already contains instead of making application code
 * restate them. That includes screen-relative Act emphasis, consequence destinations, and lifecycle
 * membership that Compose can prove from an Element being rendered inside an [ActScope].
 */
@Stable
class ElementRegistry {

    private val tenancy = mutableStateMapOf<ElementId, List<Tenant>>()

    private class Tenant(val owner: Any) {
        var placement: Placement? by mutableStateOf(null)
        var employment: Employment? by mutableStateOf(null)
        var lifecycleAct: ActId? by mutableStateOf(null)
        var token: (@Composable () -> Unit)? = null

        @OptIn(ExperimentalFoundationApi::class)
        var requester: BringIntoViewRequester? = null
    }

    private fun tenant(id: ElementId): Tenant? = tenancy[id]?.lastOrNull()

    private fun claim(id: ElementId, owner: Any): Tenant {
        val held = tenancy[id].orEmpty()
        held.firstOrNull { it.owner === owner }?.let { return it }
        val fresh = Tenant(owner)
        tenancy[id] = held + fresh
        return fresh
    }

    private fun composed(): Set<ElementId> =
        tenancy.keys.filterTo(mutableSetOf()) { tenant(it)?.placement != null }

    private val gateFlags = mutableStateMapOf<ElementId, Boolean>()

    val gateAddresses: Set<ElementId> get() = gateFlags.keys

    private class OfferClaim(val owner: Any, val act: Act, val at: ElementId)

    private val offered = mutableStateMapOf<ActId, List<OfferClaim>>()

    private val currentOffers: Map<ActId, Pair<Act, ElementId>>
        get() = offered.mapNotNull { (id, claims) -> claims.lastOrNull()?.let { id to (it.act to it.at) } }.toMap()

    var articulating: ElementId? by mutableStateOf(null)
        private set

    operator fun get(id: ElementId): Placement? = tenant(id)?.placement

    fun bounds(id: ElementId): Rect? = tenant(id)?.placement?.bounds

    fun resolves(id: ElementId): Boolean = tenant(id)?.placement != null

    fun visible(id: ElementId): Boolean = tenant(id)?.placement?.visible == true

    internal fun anchor(id: ElementId): Rect? {
        val held = tenancy[id].orEmpty()
        val below = held.getOrNull(held.lastIndex - 1) ?: held.lastOrNull()
        return below?.placement?.bounds
    }

    internal fun place(id: ElementId, owner: Any, placement: Placement) {
        claim(id, owner).placement = placement
    }

    internal fun token(id: ElementId): (@Composable () -> Unit)? = tenant(id)?.token

    internal fun employ(id: ElementId, owner: Any, employment: Employment) {
        claim(id, owner).employment = employment
    }

    internal fun attachLifecycle(id: ElementId, owner: Any, act: ActId) {
        claim(id, owner).lifecycleAct = act
    }

    internal fun offer(act: Act, at: ElementId, owner: Any) {
        val held = offered[act.id].orEmpty().filterNot { it.owner === owner }
        offered[act.id] = held + OfferClaim(owner, act, at)
    }

    internal fun markGate(id: ElementId) {
        gateFlags[id] = true
    }

    /**
     * Resolve an Act's declared emphasis against the other visible Acts on this screen.
     *
     * This follows the document-outline model: Heroic is the page title; Primary, Secondary and
     * Tertiary are progressively lower headings. Two visible title claims invalidate the unique
     * title slot, so every level drops once. Supporting is the floor.
     */
    fun resolvedEmphasis(act: Act): ActEmphasis {
        val heroic = currentOffers.values
            .asSequence()
            .filter { (candidate, at) -> candidate.emphasis == ActEmphasis.Heroic && visible(at) }
            .map { (candidate, _) -> candidate.id }
            .toMutableSet()

        // During an Offer's first composition its DisposableEffect may not have registered yet.
        // Include the Act being resolved so the rule is correct on that very first frame too.
        if (act.emphasis == ActEmphasis.Heroic) heroic += act.id

        return act.emphasis.resolve(heroic.size)
    }

    /** What this Element is actually doing, deriving every job the live semantic graph can prove. */
    fun jobsOf(id: ElementId): Set<Job> = buildSet {
        if (currentOffers.values.any { it.second == id }) add(Job.Invite)
        if (currentOffers.values.any { (act, _) -> act.consequence.target == id }) add(Job.Receive)
        if (gateFlags.containsKey(id)) {
            add(Job.Invite)
            add(Job.Locate)
        }
        if (tenant(id)?.token != null) add(Job.Identify)
        when (val declared = tenant(id)?.employment) {
            is Employment.Working -> addAll(declared.jobs)
            else -> Unit
        }
    }

    internal fun withdraw(id: ActId, owner: Any) {
        val remaining = offered[id].orEmpty().filterNot { it.owner === owner }
        if (remaining.isEmpty()) offered.remove(id) else offered[id] = remaining
    }

    fun offering(id: ElementId): Act? = currentOffers.values.firstOrNull { it.second == id }?.first

    fun census(): Census {
        val composed = composed()
        val current = currentOffers
        val offering = current.filterValues { it.second in composed }
        val invitingIds = offering.values.map { it.second }.toSet()

        var content = 0
        var ambient = 0
        composed.forEach { id ->
            if (id in invitingIds) return@forEach
            if (tenant(id)?.employment == Employment.Ambient) {
                ambient++
                return@forEach
            }
            val jobs = jobsOf(id)
            if (jobs.any { it == Job.Identify || it == Job.Report }) content++
        }

        return Census(
            acts = current.size,
            reachable = offering.count { visible(it.value.second) },
            elements = composed.size,
            inviting = invitingIds.size,
            content = content,
            ambient = ambient,
            unreachable = current.filterValues { it.second !in composed }.keys.toList(),
            mute = composed.filter { id ->
                id !in invitingIds && Job.Invite in jobsOf(id)
            },
            contested = composed.filter { id -> (tenancy[id]?.size ?: 0) > 1 },
        )
    }

    /** Everything the framework knows about this live surface. */
    fun auditFrame(surface: String): AuditFrame {
        val byElement = currentOffers.values.associateBy { it.second }
        val elements = composed().map { id ->
            val placement = requireNotNull(tenant(id)?.placement)
            val act = byElement[id]?.first
            AuditElement(
                id = id,
                left = placement.bounds.left,
                top = placement.bounds.top,
                width = placement.bounds.width,
                height = placement.bounds.height,
                visible = placement.visible,
                act = act?.id,
                lifecycleAct = tenant(id)?.lifecycleAct,
                verb = act?.verb,
                consequence = act?.consequence?.let { "${act.verb} -> ${it.target}" },
                target = act?.consequence?.target,
                weight = act?.weight,
                reversible = act?.reversible == true,
                blocked = act?.state() is ActState.Blocked,
                jobs = jobsOf(id),
                emphasis = act?.emphasis,
                ambient = tenant(id)?.employment == Employment.Ambient,
            )
        }
        return AuditFrame(surface = surface, census = census(), elements = elements, gateAddresses = gateAddresses)
    }

    internal fun forget(id: ElementId, owner: Any) {
        val remaining = tenancy[id].orEmpty().filterNot { it.owner === owner }
        if (remaining.isEmpty()) {
            tenancy.remove(id)
            if (articulating == id) articulating = null
        } else {
            tenancy[id] = remaining
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    internal fun attach(id: ElementId, owner: Any, requester: BringIntoViewRequester) {
        claim(id, owner).requester = requester
    }

    internal fun attachToken(id: ElementId, owner: Any, token: @Composable () -> Unit) {
        claim(id, owner).token = token
    }

    @OptIn(ExperimentalFoundationApi::class)
    suspend fun escortTo(id: ElementId) {
        tenant(id)?.requester?.bringIntoView()
        articulating = id
    }

    fun settleArticulation() {
        articulating = null
    }

    val placed: Set<ElementId> get() = composed()
}

private val NoRegistry = ElementRegistry()

val LocalElements = staticCompositionLocalOf { NoRegistry }

/** The Act whose rendered lifecycle currently contains this composition, when there is one. */
internal val LocalActLifecycle = staticCompositionLocalOf<ActId?> { null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.element(
    id: ElementId,
    token: (@Composable () -> Unit)? = null,
    employment: Employment? = null,
): Modifier {
    val registry = LocalElements.current
    val lifecycleAct = LocalActLifecycle.current
    val requester = remember(id) { BringIntoViewRequester() }
    val claim = remember(id) { Any() }
    DisposableEffect(registry, id, token, employment, lifecycleAct) {
        registry.attach(id, claim, requester)
        if (token != null) registry.attachToken(id, claim, token)
        if (employment != null) registry.employ(id, claim, employment)
        if (lifecycleAct != null) registry.attachLifecycle(id, claim, lifecycleAct)
        onDispose { registry.forget(id, claim) }
    }

    val arriving = registry.articulating == id
    val settle = remember(id) { Animatable(0f) }
    LaunchedEffect(arriving) {
        if (arriving) {
            settle.animateTo(1f, Motion.spec(Weight.Light))
            settle.animateTo(0f, Motion.spec(Weight.Medium))
        }
    }

    return bringIntoViewRequester(requester)
        .graphicsLayer {
            val lift = settle.value
            scaleX = 1f + lift * 0.12f
            scaleY = 1f + lift * 0.12f
        }
        .onGloballyPositioned { coordinates ->
            val size = coordinates.size
            registry.place(
                id,
                claim,
                Placement(
                    bounds = Rect(
                        coordinates.localToRoot(Offset.Zero),
                        coordinates.localToRoot(Offset(size.width.toFloat(), size.height.toFloat())),
                    ),
                    visible = !coordinates.boundsInRoot().isEmpty,
                ),
            )
        }
}

fun subjectElement(subject: SubjectId): ElementId = ElementId("subject:${subject.value}")
