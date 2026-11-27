package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.memory.MemoryEpisode
import com.hereliesaz.geministrator.memory.MemorySnapshot
import com.hereliesaz.geministrator.memory.WebNode
import kotlin.math.floor
import kotlin.math.pow

/*
 * What the spatial hive's light says, and what it is read from (tasks/hive-redesign.md, "Keeper ruling"). Every signal
 * here is a pure function of the run's own records, so light means something happened: a memory used, a delivery made,
 * a task finished. Presentation time (the clock the renderer passes in) only shapes how a real event looks; it never
 * creates one. Procedural morphology is still not evidence. The renderer's wiring of these into the GPU is separate.
 */

// ---- memory in use flips from its warm glow to blue ------------------------------------------------------------

/** A stored memory whose text a task's successful request carried (MemoryProvenance.kt), at the report's time. */
internal data class SporeUse(val task: TaskDefinitionId, val sporeId: String, val atEpochMillis: Long)

/** The web node for a recall address: "memory-node:<id>" is the node the Memory Web draws as "node:<id>". */
internal fun sporeIdOfAddress(address: String): String? =
    address.removePrefix("memory-node:").takeIf { it != address && it.isNotBlank() }?.let { "node:$it" }

/**
 * Tags, keywords, key phrases, summaries and every other kind alike: any carried node is activated. One use per node per
 * report, in time order. Replayed work, stale sessions and uninstrumented reports contribute nothing.
 */
internal fun memorySporeUses(run: WorkflowRun, reports: List<ProviderUsageRecorded>): List<SporeUse> =
    reports.filter { event ->
        val task = run.taskRuns[event.taskDefinitionId]
        event.workflowRunId == run.id && task != null && event.providerId == task.assignedProviderId &&
            event.providerRunId == task.providerRunId && task.status != TaskRunStatus.Ready &&
            event.tokensAvoided == 0L && event.deliveredMemoryAddresses != null
    }.flatMap { event ->
        event.deliveredMemoryAddresses.orEmpty().mapNotNull(::sporeIdOfAddress)
            .distinct().map { SporeUse(event.taskDefinitionId, it, event.occurredAtEpochMillis) }
    }.sortedWith(compareBy({ it.atEpochMillis }, { it.sporeId }, { it.task.value }))

internal const val BLUE_GLOW_MILLIS = 2_500L
private const val BLUE_RISE_MILLIS = 150L
private const val BLUE_HOLD_MILLIS = 1_000L

/**
 * How far an activated memory's glow has flipped from its resting warm toward blue: 0 is the warm glow the network
 * has at rest, 1 is fully blue. A quick flip at the moment of use, a hold, then an ease back to warm over a total
 * of [BLUE_GLOW_MILLIS]; 0 before the use and after. The renderer mixes the two colours by this.
 */
internal fun blueGlow(nowEpochMillis: Long, usedAtEpochMillis: Long): Float {
    val dt = nowEpochMillis - usedAtEpochMillis
    if (dt < 0 || dt >= BLUE_GLOW_MILLIS) return 0f
    if (dt < BLUE_RISE_MILLIS) return dt.toFloat() / BLUE_RISE_MILLIS
    if (dt < BLUE_RISE_MILLIS + BLUE_HOLD_MILLIS) return 1f
    val x = (dt - BLUE_RISE_MILLIS - BLUE_HOLD_MILLIS).toFloat() / (BLUE_GLOW_MILLIS - BLUE_RISE_MILLIS - BLUE_HOLD_MILLIS)
    return 1f - x * x * (3f - 2f * x)
}

/**
 * Every spore the world holds: the web's nodes, and the spores a finished task has released that no tardigrade has
 * collected yet. A released spore is actual memory data from the moment it is released, so it exists in the world
 * (floating by its creature) before it joins the web. Use this set for [blueSpores].
 */
internal fun heldSpores(webNodeIds: Set<String>, bursts: List<CompletionBurst>): Set<String> =
    webNodeIds + bursts.flatMap { it.spores }

/**
 * The blue mix for each spore at [nowEpochMillis], for the spores the world actually holds only ([spores], see
 * [heldSpores]): a used memory the world does not hold draws nothing, since the renderer can light only what it has.
 * Collected or not makes no difference: a released spore that no tardigrade has reached yet flips just the same.
 * Nothing else flips: not its neighbours, not its hyphae, not the rest of the network. Concurrent uses of one spore
 * take the strongest.
 */
internal fun blueSpores(nowEpochMillis: Long, uses: List<SporeUse>, spores: Set<String>): Map<String, Float> =
    uses.asSequence().filter { it.sporeId in spores }
        .map { it.sporeId to blueGlow(nowEpochMillis, it.atEpochMillis) }.filter { it.second > 0f }
        .groupBy({ it.first }, { it.second }).mapValues { (_, mixes) -> mixes.max() }

// ---- communication pulses --------------------------------------------------------------------------------------

/** [from]'s output reached [to]'s successful request: a real delivery, directed toward whom it is said to. */
internal data class TransferPulse(val from: TaskDefinitionId, val to: TaskDefinitionId, val atEpochMillis: Long)

/**
 * Each producer-to-consumer path with a recorded delivery (the evidence Echo and trophic support read), at the first
 * report that carried the producer's artifact. Unknown delivery is no pulse.
 */
internal fun transferPulses(run: WorkflowRun, reports: List<ProviderUsageRecorded>): List<TransferPulse> {
    val definition = run.definitionSnapshot ?: return emptyList()
    val deliveries = reportedArtifactDeliveries(run, reports)
    return definition.tasks.flatMap { consumer ->
        val target = run.taskRuns[consumer.id] ?: return@flatMap emptyList()
        consumer.dependsOn.mapNotNull { sourceId ->
            if (artifactDelivered(run, sourceId, consumer.id, deliveries) != true) return@mapNotNull null
            val artifacts = run.taskRuns.getValue(sourceId).artifacts.map { it.id.value }.toSet()
            val at = reports.asSequence().filter { event ->
                event.workflowRunId == run.id && event.taskDefinitionId == consumer.id &&
                    event.providerId == target.assignedProviderId && event.providerRunId == target.providerRunId &&
                    event.tokensAvoided == 0L && event.deliveredArtifactIds.orEmpty().any { it in artifacts }
            }.minOfOrNull { it.occurredAtEpochMillis } ?: return@mapNotNull null
            TransferPulse(sourceId, consumer.id, at)
        }
    }.sortedWith(compareBy({ it.atEpochMillis }, { it.from.value }, { it.to.value }))
}

/** Slow while it starts, quicker as it nears the end. Capped at 3 a second, the most a flashing light should do. */
internal const val PULSE_MIN_HZ = 0.5f
internal const val PULSE_MAX_HZ = 3f

/** Where a finished task's context stands in the memory layer. */
internal enum class ContextParse {
    /** No episode of its own is banked: nothing of it is being parsed into memory (or none has been banked yet). */
    Unbanked,

    /** At least one of its episodes is still queued for consolidation: its context is not all parsed. */
    Parsing,

    /** Its episodes are banked and none is queued: all its context has been parsed into memory. */
    Parsed,
}

/** The episodes a memory snapshot still has queued for consolidation: `MemorySnapshot.queue`'s episode ids. */
internal fun queuedEpisodeIds(snapshot: MemorySnapshot): Set<String> = snapshot.queue.mapTo(mutableSetOf()) { it.episodeId.value }

/**
 * Whether all of [taskRun]'s context has been parsed into memory: its own episodes (linked by the episode's task run)
 * exist and none is still in [queuedEpisodes]. A task with no banked episode is [ContextParse.Unbanked], so a creature
 * whose memory is off or not yet banked does not pulse waiting for nothing.
 */
internal fun contextParse(taskRun: TaskRun, episodes: List<MemoryEpisode>, queuedEpisodes: Set<String>): ContextParse {
    val own = episodes.filter { it.taskRunId == taskRun.id.value }
    if (own.isEmpty()) return ContextParse.Unbanked
    return if (own.any { it.id.value in queuedEpisodes }) ContextParse.Parsing else ContextParse.Parsed
}

/**
 * How fast a working creature's light beats, rising with its own reported progress. A working task that reports no
 * progress beats at the base rate, never faster: pace is not estimated. A task that has completed keeps beating, at
 * the top rate it had reached, while its context is still being parsed ([ContextParse.Parsing]), and stops when all
 * of it has been, which is when it bursts. Null when it is not beating (not started, failed or cancelled, or finished
 * with its context parsed or never banked).
 */
internal fun pulseRateHz(task: TaskRun?, parse: ContextParse? = null): Float? {
    val status = task?.status ?: return null
    if (status == TaskRunStatus.Completed) return PULSE_MAX_HZ.takeIf { parse == ContextParse.Parsing }
    val working = status == TaskRunStatus.Running || status == TaskRunStatus.Planning ||
        status == TaskRunStatus.Verifying || status == TaskRunStatus.Retrying
    if (!working) return null
    val progress = task.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    return PULSE_MIN_HZ + (PULSE_MAX_HZ - PULSE_MIN_HZ) * progress
}

/** A beat of a creature's clock: [phase] in 0..1, and whether this step crossed the beat (a flash begins). */
internal data class Beat(val phase: Float, val crossed: Boolean)

/**
 * One integrator per creature drives both its own light and the pulses it sends, so they share a rate and a phase
 * even as the rate climbs (a rate that changes is integrated, not multiplied by the time). Reduced motion keeps
 * the resting state: no beat.
 */
internal fun advanceBeat(phase: Float, rateHz: Float, seconds: Float, reduced: Boolean): Beat {
    if (reduced || !rateHz.isFinite() || rateHz <= 0f || !seconds.isFinite() || seconds <= 0f) return Beat(phase, false)
    val next = phase + rateHz * seconds
    return Beat(next - floor(next), next >= 1f)
}

/** The creature's light at a beat phase: brightest as the beat crosses, easing out before the next. */
internal fun beatLight(phase: Float): Float = (1f - phase.coerceIn(0f, 1f)).pow(3)

internal const val PULSE_WINDOW_MILLIS = 4_000L
internal const val PULSE_TRAVEL_MILLIS = 1_200L

/**
 * Where along the connection (0 at [TransferPulse.from], 1 at [TransferPulse.to]) each pulse is, at [nowEpochMillis].
 * Pulses leave on the sender's beats, at its rate, for a window after the delivery, and each takes a fixed time to
 * travel. Sorted nearest the sender first.
 */
internal fun pulsesInFlight(nowEpochMillis: Long, pulse: TransferPulse, rateHz: Float): List<Float> {
    if (!rateHz.isFinite() || rateHz <= 0f) return emptyList()
    val interval = 1_000f / rateHz
    val sent = (PULSE_WINDOW_MILLIS / interval).toInt() + 1
    return (0 until sent).mapNotNull { k ->
        val travelled = nowEpochMillis - (pulse.atEpochMillis + (k * interval).toLong())
        if (travelled < 0 || travelled > PULSE_TRAVEL_MILLIS) null else travelled.toFloat() / PULSE_TRAVEL_MILLIS
    }.sortedDescending()
}

// ---- completion: the light bursts and the spores are released ----------------------------------------------------

/** A task finished; [spores] are the web nodes made from its own work, for the tardigrades to collect. */
internal data class CompletionBurst(val task: TaskDefinitionId, val atEpochMillis: Long, val spores: Set<String>)

/**
 * The spores a task releases: memories whose recorded sources include one of its own episodes, and those episodes'
 * nodes. Linked through the episode's task run, never by time or proximity. No episodes means none released.
 */
internal fun releasedSpores(taskRun: TaskRun, episodes: List<MemoryEpisode>, nodes: List<WebNode>): Set<String> {
    val own = episodes.filter { it.taskRunId == taskRun.id.value }.mapTo(mutableSetOf()) { it.id.value }
    if (own.isEmpty()) return emptySet()
    return nodes.filter { node -> node.id.removePrefix("episode:").let { it != node.id && it in own } ||
        // A node lists each source as "<episode id> (<session id>)" when the episode is known, else the bare id.
        node.sources.any { source -> own.any { source == it || source.startsWith("$it (") } } }.mapTo(linkedSetOf()) { it.id }
}

/**
 * One burst per completed task, once all its context has been parsed, so the spores released are all of them and the
 * count is right. A task whose episodes are still queued ([queuedEpisodes]) has no burst yet and keeps pulsing
 * ([pulseRateHz]). The burst is at the later of the journal's completion time and the moment the last of its spores
 * was recorded (the memory layer's ingest time), so it never precedes its spores. A task with no banked episode bursts
 * at completion with none. A task with no completion event has no burst.
 */
internal fun completionBursts(
    run: WorkflowRun, events: List<WorkflowEvent>, episodes: List<MemoryEpisode>, nodes: List<WebNode>,
    queuedEpisodes: Set<String> = emptySet(),
): List<CompletionBurst> = events.filterIsInstance<TaskCompleted>().filter { it.workflowRunId == run.id }.mapNotNull { event ->
    val task = run.taskRuns[event.taskDefinitionId]?.takeIf { it.status == TaskRunStatus.Completed } ?: return@mapNotNull null
    if (contextParse(task, episodes, queuedEpisodes) == ContextParse.Parsing) return@mapNotNull null
    val released = releasedSpores(task, episodes, nodes)
    val parsedAt = nodes.filter { it.id in released }.maxOfOrNull { it.createdAtEpochMillis } ?: 0L
    CompletionBurst(event.taskDefinitionId, maxOf(event.occurredAtEpochMillis, parsedAt), released)
}.distinctBy { it.task }.sortedWith(compareBy({ it.atEpochMillis }, { it.task.value }))

internal const val BURST_MILLIS = 1_800L
private const val BURST_RISE_MILLIS = 150L

/** The creature's flash at the burst: a fast rise, then a long fall; 0 before and after. */
internal fun burstLight(nowEpochMillis: Long, burst: CompletionBurst): Float {
    val dt = nowEpochMillis - burst.atEpochMillis
    if (dt < 0 || dt >= BURST_MILLIS) return 0f
    if (dt < BURST_RISE_MILLIS) return dt.toFloat() / BURST_RISE_MILLIS
    return (1f - (dt - BURST_RISE_MILLIS).toFloat() / (BURST_MILLIS - BURST_RISE_MILLIS)).pow(2)
}
