package com.hereliesaz.conveyance.h2g2

import kotlin.math.abs
import kotlin.math.sqrt

enum class H2g2SwarmBehavior {
    Wander,
    Rest,
    Investigate,
    Avoid,
    Approach,
    Confer,
    Transfer,
    Follow,
    PrepareSpawn,
    Birth,
    Working,
    Blocked,
    Startled,
    Recover,
}

enum class H2g2SwarmIdentityKind {
    /** Canonical, host-supplied Haive logo character. Never procedurally redrawn by H2G2. */
    Orchestrator,
    /** Stable procedural creature generated from [H2g2SwarmAgentSpec.identitySeed]. */
    Generated,
}

enum class H2g2SwarmContactKind {
    Transfer,
    Confer,
    Birth,
}

data class H2g2SwarmAgentSpec(
    val id: String,
    val identitySeed: String = id,
    val identityKind: H2g2SwarmIdentityKind = H2g2SwarmIdentityKind.Generated,
    val habitatX: Float,
    val habitatY: Float,
    val parentId: String? = null,
)

data class H2g2SwarmPersonality(
    val activity: Float,
    val curiosity: Float,
    val sociability: Float,
    val personalSpace: Float,
    val territoriality: Float,
    val preferredSpeed: Float,
    val decisionPeriodMillis: Float,
)

data class H2g2SwarmContact(
    val kind: H2g2SwarmContactKind,
    val sourceId: String,
    val targetId: String,
    val ageMillis: Float,
    val durationMillis: Float,
) {
    val progress: Float
        get() = if (durationMillis <= 0f) 1f else (ageMillis / durationMillis).coerceIn(0f, 1f)
}

data class H2g2SwarmAgentSnapshot(
    val id: String,
    val identitySeed: String,
    val identityKind: H2g2SwarmIdentityKind,
    val parentId: String?,
    val x: Float,
    val y: Float,
    val velocityX: Float,
    val velocityY: Float,
    val headingX: Float,
    val headingY: Float,
    val habitatX: Float,
    val habitatY: Float,
    val behavior: H2g2SwarmBehavior,
    val targetId: String?,
    val personality: H2g2SwarmPersonality,
    val behaviorAgeMillis: Float,
)

/**
 * Cheap artificial-life simulation for a workflow terrarium.
 *
 * There is one shared world step, but every creature owns an independent decision accumulator,
 * cadence, state machine, personality and RNG stream. A caller can therefore drive the whole world
 * from one render clock without reducing the colony to one scripted animation loop.
 *
 * Rendering is deliberately absent from this class. H2G2 renderers interpolate snapshots at the
 * display frame rate while behavioral decisions happen at roughly 4-9 Hz per creature.
 */
class H2g2SwarmWorld(
    specs: Collection<H2g2SwarmAgentSpec>,
) {
    private val runtimes = linkedMapOf<String, AgentRuntime>()
    private val contacts = mutableListOf<ContactRuntime>()
    private var spawnSerial: Long = 0L

    init {
        require(specs.map { it.id }.distinct().size == specs.size) { "Swarm agent IDs must be unique" }
        specs.forEach(::addSpec)
    }

    val size: Int
        get() = runtimes.size

    fun snapshots(): List<H2g2SwarmAgentSnapshot> = runtimes.values.map(AgentRuntime::snapshot)

    fun snapshot(id: String): H2g2SwarmAgentSnapshot? = runtimes[id]?.snapshot()

    fun activeContacts(): List<H2g2SwarmContact> = contacts.map(ContactRuntime::snapshot)

    /**
     * Advances each creature's independent behavior loop and then integrates motion once for the
     * shared visual time step. Large stalls are clamped so background tabs do not explode the bowl.
     */
    fun step(deltaMillis: Float) {
        if (deltaMillis <= 0f || runtimes.isEmpty()) return
        val dt = deltaMillis.coerceAtMost(MaxStepMillis)

        contacts.forEach { it.ageMillis += dt }
        val expiredContacts = contacts.filter { it.ageMillis >= it.durationMillis }
        contacts.removeAll(expiredContacts.toSet())
        expiredContacts
            .flatMap { listOf(it.sourceId, it.targetId) }
            .distinct()
            .forEach { agentId ->
                val runtime = runtimes[agentId] ?: return@forEach
                val stillInteracting = contacts.any { it.sourceId == agentId || it.targetId == agentId }
                if (!stillInteracting && runtime.behavior in InteractionBehaviors) {
                    runtime.behavior = H2g2SwarmBehavior.Recover
                    runtime.targetId = null
                    runtime.behaviorAgeMillis = 0f
                }
            }

        runtimes.values.forEach { runtime ->
            runtime.behaviorAgeMillis += dt
            runtime.decisionAccumulatorMillis += dt
            while (runtime.decisionAccumulatorMillis >= runtime.personality.decisionPeriodMillis) {
                runtime.decisionAccumulatorMillis -= runtime.personality.decisionPeriodMillis
                decide(runtime, runtime.personality.decisionPeriodMillis)
            }
        }

        val seconds = dt / 1000f
        runtimes.values.forEach { runtime -> integrate(runtime, seconds) }
    }

    fun moveHabitat(id: String, x: Float, y: Float, moveCreature: Boolean = false) {
        val runtime = requireRuntime(id)
        runtime.habitatX = x.coerceIn(MinX, MaxX)
        runtime.habitatY = y.coerceIn(MinY, MaxY)
        if (moveCreature) {
            runtime.x = runtime.habitatX
            runtime.y = runtime.habitatY
            runtime.velocityX = 0f
            runtime.velocityY = 0f
        }
    }

    fun setBehavior(id: String, behavior: H2g2SwarmBehavior, targetId: String? = null) {
        require(targetId == null || targetId in runtimes) { "Unknown swarm target $targetId" }
        val runtime = requireRuntime(id)
        runtime.behavior = behavior
        runtime.targetId = targetId
        runtime.behaviorAgeMillis = 0f
    }

    fun beginTransfer(sourceId: String, targetId: String, durationMillis: Float = 1700f) {
        beginContact(H2g2SwarmContactKind.Transfer, sourceId, targetId, durationMillis)
        setBehavior(sourceId, H2g2SwarmBehavior.Transfer, targetId)
        setBehavior(targetId, H2g2SwarmBehavior.Approach, sourceId)
    }

    fun beginConference(firstId: String, secondId: String, durationMillis: Float = 2100f) {
        beginContact(H2g2SwarmContactKind.Confer, firstId, secondId, durationMillis)
        setBehavior(firstId, H2g2SwarmBehavior.Confer, secondId)
        setBehavior(secondId, H2g2SwarmBehavior.Confer, firstId)
    }

    /**
     * Adds a real simulated child beside its parent. The host remains responsible for deciding
     * whether that visual birth corresponds to an actual workflow spawn/delegation.
     */
    fun spawnChild(
        parentId: String,
        childId: String,
        identitySeed: String = childId,
        durationMillis: Float = 2200f,
    ): H2g2SwarmAgentSnapshot {
        require(childId !in runtimes) { "Swarm agent $childId already exists" }
        val parent = requireRuntime(parentId)
        spawnSerial += 1
        val offsetRandom = StableRandom(stableSeed("$identitySeed:$spawnSerial"))
        val spec = H2g2SwarmAgentSpec(
            id = childId,
            identitySeed = identitySeed,
            identityKind = H2g2SwarmIdentityKind.Generated,
            habitatX = (parent.x + offsetRandom.float(-.08f, .08f)).coerceIn(MinX, MaxX),
            habitatY = (parent.y + offsetRandom.float(.06f, .13f)).coerceIn(MinY, MaxY),
            parentId = parentId,
        )
        val child = addSpec(spec)
        child.x = parent.x
        child.y = parent.y
        child.velocityX = 0f
        child.velocityY = 0f
        child.behavior = H2g2SwarmBehavior.Birth
        child.targetId = parentId
        child.behaviorAgeMillis = 0f
        beginContact(H2g2SwarmContactKind.Birth, parentId, childId, durationMillis)
        return child.snapshot()
    }

    private fun beginContact(
        kind: H2g2SwarmContactKind,
        sourceId: String,
        targetId: String,
        durationMillis: Float,
    ) {
        require(sourceId != targetId) { "A swarm contact requires two different agents" }
        requireRuntime(sourceId)
        requireRuntime(targetId)
        contacts.removeAll { it.kind == kind && it.sourceId == sourceId && it.targetId == targetId }
        contacts += ContactRuntime(
            kind = kind,
            sourceId = sourceId,
            targetId = targetId,
            durationMillis = durationMillis.coerceAtLeast(1f),
        )
    }

    private fun addSpec(spec: H2g2SwarmAgentSpec): AgentRuntime {
        require(spec.id !in runtimes) { "Duplicate swarm agent ${spec.id}" }
        val random = StableRandom(stableSeed(spec.identitySeed))
        val personality = personalityFor(spec, random)
        val runtime = AgentRuntime(
            spec = spec,
            x = spec.habitatX.coerceIn(MinX, MaxX),
            y = spec.habitatY.coerceIn(MinY, MaxY),
            habitatX = spec.habitatX.coerceIn(MinX, MaxX),
            habitatY = spec.habitatY.coerceIn(MinY, MaxY),
            velocityX = random.float(-.028f, .028f),
            velocityY = random.float(-.028f, .028f),
            personality = personality,
            random = random,
            decisionAccumulatorMillis = random.float(0f, personality.decisionPeriodMillis),
        )
        runtimes[spec.id] = runtime
        return runtime
    }

    private fun personalityFor(spec: H2g2SwarmAgentSpec, random: StableRandom): H2g2SwarmPersonality {
        if (spec.identityKind == H2g2SwarmIdentityKind.Orchestrator) {
            return H2g2SwarmPersonality(
                activity = .38f,
                curiosity = .72f,
                sociability = .96f,
                personalSpace = .072f,
                territoriality = .92f,
                preferredSpeed = .018f,
                decisionPeriodMillis = 165f,
            )
        }
        return H2g2SwarmPersonality(
            activity = random.float(.42f, .96f),
            curiosity = random.float(.20f, .98f),
            sociability = random.float(.15f, .97f),
            personalSpace = random.float(.035f, .070f),
            territoriality = random.float(.35f, .92f),
            preferredSpeed = random.float(.018f, .034f),
            decisionPeriodMillis = random.float(110f, 260f),
        )
    }

    private fun decide(runtime: AgentRuntime, decisionMillis: Float) {
        var accelerationX = 0f
        var accelerationY = 0f

        // Local personal-space avoidance. This is O(n²), intentionally tiny for terrarium-scale
        // colonies; a spatial hash can replace it if Haive ever renders hundreds of creatures.
        runtimes.values.forEach { other ->
            if (other === runtime) return@forEach
            val dx = runtime.x - other.x
            val dy = runtime.y - other.y
            val combinedSpace = runtime.personality.personalSpace + other.personality.personalSpace
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared in 0.000001f..(combinedSpace * combinedSpace)) {
                val direction = normalized(dx, dy)
                accelerationX += direction.first * .0025f
                accelerationY += direction.second * .0025f
            }
        }

        // Territorial pull keeps wandering readable instead of turning the bowl into Brownian soup.
        val homeDx = runtime.habitatX - runtime.x
        val homeDy = runtime.habitatY - runtime.y
        val homeDistanceSquared = homeDx * homeDx + homeDy * homeDy
        if (homeDistanceSquared > .05f) {
            val direction = normalized(homeDx, homeDy)
            val homeForce = .0007f + runtime.personality.territoriality * .0008f
            accelerationX += direction.first * homeForce
            accelerationY += direction.second * homeForce
        }

        val target = runtime.targetId?.let(runtimes::get)
        when (runtime.behavior) {
            H2g2SwarmBehavior.Rest,
            H2g2SwarmBehavior.Blocked,
            -> {
                runtime.velocityX *= .80f
                runtime.velocityY *= .80f
            }

            H2g2SwarmBehavior.Approach,
            H2g2SwarmBehavior.Follow,
            H2g2SwarmBehavior.Confer,
            H2g2SwarmBehavior.Transfer,
            H2g2SwarmBehavior.Birth,
            -> if (target != null) {
                val direction = normalized(target.x - runtime.x, target.y - runtime.y)
                val force = when (runtime.behavior) {
                    H2g2SwarmBehavior.Follow -> .0010f
                    H2g2SwarmBehavior.Birth -> .00045f
                    else -> .0018f
                }
                accelerationX += direction.first * force
                accelerationY += direction.second * force
            }

            H2g2SwarmBehavior.Startled -> {
                accelerationX += runtime.random.float(-.006f, .006f)
                accelerationY += runtime.random.float(-.006f, .006f)
            }

            else -> {
                val jitter = when (runtime.behavior) {
                    H2g2SwarmBehavior.Working -> .0020f
                    H2g2SwarmBehavior.Investigate -> .0016f
                    else -> .0013f
                } * (.65f + runtime.personality.activity)
                accelerationX += runtime.random.float(-jitter, jitter)
                accelerationY += runtime.random.float(-jitter, jitter)
            }
        }

        runtime.velocityX = ((runtime.velocityX + accelerationX) * .965f)
            .coerceIn(-runtime.personality.preferredSpeed, runtime.personality.preferredSpeed)
        runtime.velocityY = ((runtime.velocityY + accelerationY) * .965f)
            .coerceIn(-runtime.personality.preferredSpeed, runtime.personality.preferredSpeed)

        val forcedInteraction = runtime.behavior in InteractionBehaviors
        val stateLimit = stateDurationMillis(runtime)
        if (!forcedInteraction && runtime.behaviorAgeMillis >= stateLimit) chooseAmbientBehavior(runtime)

        // Silence unused warning while documenting that decision time is intentionally discrete.
        @Suppress("UNUSED_VARIABLE")
        val discreteDecisionMillis = decisionMillis
    }

    private fun chooseAmbientBehavior(runtime: AgentRuntime) {
        val r = runtime.random.nextFloat()
        runtime.targetId = null
        runtime.behavior = if (runtime.spec.identityKind == H2g2SwarmIdentityKind.Orchestrator) {
            when {
                r < .50f -> H2g2SwarmBehavior.Rest
                r < .78f -> H2g2SwarmBehavior.Investigate
                else -> H2g2SwarmBehavior.Approach.also {
                    runtime.targetId = randomOtherId(runtime)
                }
            }
        } else {
            when {
                r < .10f * (1f - runtime.personality.activity) -> H2g2SwarmBehavior.Rest
                r < .22f -> H2g2SwarmBehavior.Investigate
                r < .22f + .19f * runtime.personality.sociability -> H2g2SwarmBehavior.Approach.also {
                    runtime.targetId = nearestOtherId(runtime)
                }
                r < .38f + .18f * runtime.personality.curiosity -> H2g2SwarmBehavior.Follow.also {
                    runtime.targetId = nearestOtherId(runtime)
                }
                r < .47f -> H2g2SwarmBehavior.Working
                else -> H2g2SwarmBehavior.Wander
            }
        }
        runtime.behaviorAgeMillis = 0f
    }

    private fun stateDurationMillis(runtime: AgentRuntime): Float {
        val base = runtime.random.float(900f, 3100f)
        return if (runtime.behavior == H2g2SwarmBehavior.Rest) base + 1600f else base
    }

    private fun randomOtherId(runtime: AgentRuntime): String? {
        val candidates = runtimes.keys.filterNot { it == runtime.spec.id }
        if (candidates.isEmpty()) return null
        return candidates[runtime.random.int(candidates.size)]
    }

    private fun nearestOtherId(runtime: AgentRuntime): String? = runtimes.values
        .asSequence()
        .filterNot { it === runtime }
        .minByOrNull { other ->
            val dx = other.x - runtime.x
            val dy = other.y - runtime.y
            dx * dx + dy * dy
        }
        ?.spec
        ?.id

    private fun integrate(runtime: AgentRuntime, seconds: Float) {
        runtime.x += runtime.velocityX * seconds * 5f
        runtime.y += runtime.velocityY * seconds * 5f

        if (runtime.x < MinX) {
            runtime.x = MinX
            runtime.velocityX = abs(runtime.velocityX)
        } else if (runtime.x > MaxX) {
            runtime.x = MaxX
            runtime.velocityX = -abs(runtime.velocityX)
        }
        if (runtime.y < MinY) {
            runtime.y = MinY
            runtime.velocityY = abs(runtime.velocityY)
        } else if (runtime.y > MaxY) {
            runtime.y = MaxY
            runtime.velocityY = -abs(runtime.velocityY)
        }
    }

    private fun requireRuntime(id: String): AgentRuntime = requireNotNull(runtimes[id]) {
        "Unknown swarm agent $id"
    }

    private data class AgentRuntime(
        val spec: H2g2SwarmAgentSpec,
        var x: Float,
        var y: Float,
        var habitatX: Float,
        var habitatY: Float,
        var velocityX: Float,
        var velocityY: Float,
        val personality: H2g2SwarmPersonality,
        val random: StableRandom,
        var decisionAccumulatorMillis: Float,
        var behavior: H2g2SwarmBehavior = H2g2SwarmBehavior.Wander,
        var behaviorAgeMillis: Float = 0f,
        var targetId: String? = null,
    ) {
        fun snapshot(): H2g2SwarmAgentSnapshot {
            val heading = normalized(velocityX, velocityY)
            return H2g2SwarmAgentSnapshot(
                id = spec.id,
                identitySeed = spec.identitySeed,
                identityKind = spec.identityKind,
                parentId = spec.parentId,
                x = x,
                y = y,
                velocityX = velocityX,
                velocityY = velocityY,
                headingX = heading.first,
                headingY = heading.second,
                habitatX = habitatX,
                habitatY = habitatY,
                behavior = behavior,
                targetId = targetId,
                personality = personality,
                behaviorAgeMillis = behaviorAgeMillis,
            )
        }
    }

    private data class ContactRuntime(
        val kind: H2g2SwarmContactKind,
        val sourceId: String,
        val targetId: String,
        var ageMillis: Float = 0f,
        val durationMillis: Float,
    ) {
        fun snapshot() = H2g2SwarmContact(
            kind = kind,
            sourceId = sourceId,
            targetId = targetId,
            ageMillis = ageMillis,
            durationMillis = durationMillis,
        )
    }

    private class StableRandom(seed: Long) {
        private var state: Long = if (seed == 0L) GoldenSeed else seed

        fun nextFloat(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            val bits = (state ushr 40).toInt() and 0xFFFFFF
            return bits.toFloat() / 0xFFFFFF.toFloat()
        }

        fun float(min: Float, max: Float): Float = min + nextFloat() * (max - min)

        fun int(bound: Int): Int {
            require(bound > 0)
            return (nextFloat() * bound).toInt().coerceIn(0, bound - 1)
        }
    }

    companion object {
        private const val MinX = .055f
        private const val MaxX = .945f
        private const val MinY = .075f
        private const val MaxY = .925f
        private const val MaxStepMillis = 100f
        private const val GoldenSeed = -7046029254386353131L

        private val InteractionBehaviors = setOf(
            H2g2SwarmBehavior.Confer,
            H2g2SwarmBehavior.Transfer,
            H2g2SwarmBehavior.Birth,
            H2g2SwarmBehavior.PrepareSpawn,
        )

        private fun stableSeed(value: String): Long {
            var hash = -3750763034362895579L
            value.forEach { char ->
                hash = hash xor char.code.toLong()
                hash *= 1099511628211L
            }
            return hash
        }

        private fun normalized(x: Float, y: Float): Pair<Float, Float> {
            val length = sqrt(x * x + y * y)
            return if (length <= .000001f) 0f to 0f else x / length to y / length
        }
    }
}
