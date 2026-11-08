package com.hereliesaz.geministrator.workflow.refinement

import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.workflow.ArtifactVerdicts
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One training example from a refinement-ladder run: what a level was asked and answered, whether
 * the answer is structurally sound, and what its gate decided ([kind] `level`); or how the whole
 * ladder ended ([kind] `outcome`). Gate verdicts are the labels. They come from a reviewing agent,
 * not from an exact checker, so a corpus built from them says so (tools/refinement_training).
 *
 * Text fields are scrubbed of key shapes and truncated. The dataset is opt-in, stays on the device,
 * and leaves it only through an explicit export.
 */
@Serializable
data class LadderDatasetRecord(
    val schema: String = SCHEMA,
    /** Stable per observation, so a record seen twice is stored once. */
    val id: String,
    val kind: String,
    /** `<workflow run id>/<original task id>`: the records of one ladder share it. */
    val ladder: String,
    val taskId: String,
    val level: Int? = null,
    val total: Int? = null,
    val attempt: Int = 1,
    /** The level task's objective as its agent was given it. */
    val objective: String? = null,
    val artifact: String? = null,
    /** The artifact parsed as `aive.ladder.level.v1`. */
    val structured: Boolean = false,
    /** Structured and sound against the coarser level (empty [errors]). */
    val valid: Boolean = false,
    val errors: List<String> = emptyList(),
    val gateVerdict: String? = null,
    val finalStatus: String? = null,
    val verifierVerdict: String? = null,
    val capturedAtEpochMillis: Long,
) {
    companion object {
        const val SCHEMA: String = "aive.ladder.record.v1"
        const val KIND_LEVEL: String = "level"
        const val KIND_OUTCOME: String = "outcome"
    }
}

/** Derives records from a run. Pure and repeatable: the same run gives the same ids. */
object LadderDatasetExtractor {
    const val MAX_TEXT: Int = 6000

    fun extract(definition: WorkflowDefinition, run: WorkflowRun): List<LadderDatasetRecord> {
        val ladderTasks = definition.tasks.mapNotNull { task ->
            RefinementLadderIds.parse(task.id.value)?.let { task to it }
        }
        if (ladderTasks.isEmpty()) return emptyList()

        val records = mutableListOf<LadderDatasetRecord>()
        for (base in ladderTasks.map { it.second.baseTaskId }.distinct()) {
            val ladder = "${run.id.value}/$base"
            val levels = ladderTasks
                .filter { it.second.baseTaskId == base && it.second.kind == RefinementLadderIds.Kind.Level }
                .sortedBy { it.second.index }
            val total = levels.size
            var coarser: LadderArtifact? = null

            for ((task, parsed) in levels) {
                val index = parsed.index ?: continue
                val levelRun = run.taskRuns[task.id]
                val gateRun = run.taskRuns[TaskDefinitionId(RefinementLadderIds.gate(base, index))]
                val levelText = levelRun
                    ?.takeIf { it.status == TaskRunStatus.Completed }
                    ?.artifacts
                    ?.firstOrNull { it.kind == ArtifactKind.TaskPlan && !it.textContent.isNullOrBlank() }
                    ?.textContent
                if (levelRun == null || levelText == null || gateRun == null || !gateRun.status.isTerminal()) {
                    coarser = null
                    continue
                }
                val verdict = gateRun.artifacts.firstNotNullOfOrNull { ArtifactVerdicts.of(it) }
                if (gateRun.status != TaskRunStatus.Completed && verdict == null) {
                    coarser = null
                    continue
                }

                val decoded = LadderArtifactCodec.decode(levelText)
                val errors = when (decoded) {
                    is LadderParse.Parsed -> LadderArtifactValidator.validate(decoded.artifact, index, total, coarser)
                    is LadderParse.Malformed -> listOf(decoded.reason)
                }
                coarser = (decoded as? LadderParse.Parsed)?.artifact
                    ?.takeIf { errors.isEmpty() && verdict?.blocks == false }

                records += LadderDatasetRecord(
                    id = "$ladder/level-$index/a${levelRun.attempt}",
                    kind = LadderDatasetRecord.KIND_LEVEL,
                    ladder = ladder,
                    taskId = task.id.value,
                    level = index,
                    total = total,
                    attempt = levelRun.attempt,
                    objective = clean(task.objective),
                    artifact = clean(levelText),
                    structured = decoded is LadderParse.Parsed,
                    valid = errors.isEmpty(),
                    errors = errors,
                    gateVerdict = verdict?.name?.lowercase(),
                    capturedAtEpochMillis = run.updatedAtEpochMillis,
                )
            }

            val verifierRun = run.taskRuns[TaskDefinitionId(RefinementLadderIds.verifier(base))]
            if (verifierRun != null && verifierRun.status.isTerminal()) {
                records += LadderDatasetRecord(
                    id = "$ladder/outcome",
                    kind = LadderDatasetRecord.KIND_OUTCOME,
                    ladder = ladder,
                    taskId = base,
                    total = total,
                    finalStatus = run.taskRuns[TaskDefinitionId(base)]?.status?.name,
                    verifierVerdict = verifierRun.artifacts.firstNotNullOfOrNull { ArtifactVerdicts.of(it) }
                        ?.name?.lowercase(),
                    capturedAtEpochMillis = run.updatedAtEpochMillis,
                )
            }
        }
        return records
    }

    private fun clean(text: String): String = ControlRedaction.scrub(text).take(MAX_TEXT)
}

interface LadderDatasetStore {
    /** Adds the records whose ids are new; returns how many were added. */
    suspend fun append(records: List<LadderDatasetRecord>): Int

    suspend fun all(): List<LadderDatasetRecord>

    suspend fun clear()
}

/** Keeps the newest [capacity] records; older ones are dropped. */
class InMemoryLadderDatasetStore(
    private val capacity: Int = DEFAULT_CAPACITY,
) : LadderDatasetStore {
    private val mutex = Mutex()
    private var records: List<LadderDatasetRecord> = emptyList()

    override suspend fun append(records: List<LadderDatasetRecord>): Int = mutex.withLock {
        val known = this.records.mapTo(mutableSetOf()) { it.id }
        val added = records.filter { known.add(it.id) }
        this.records = (this.records + added).takeLast(capacity)
        added.size
    }

    override suspend fun all(): List<LadderDatasetRecord> = mutex.withLock { records }

    override suspend fun clear() = mutex.withLock { records = emptyList() }

    companion object {
        const val DEFAULT_CAPACITY: Int = 200
    }
}

/**
 * A bounded dataset kept in [settings] as one JSON list. Unreadable content reads as empty and is
 * overwritten by the next append, so a corrupt value never blocks a run.
 */
class SettingsLadderDatasetStore(
    private val settings: Settings,
    private val key: String = KEY,
    private val capacity: Int = InMemoryLadderDatasetStore.DEFAULT_CAPACITY,
) : LadderDatasetStore {
    private val mutex = Mutex()

    override suspend fun append(records: List<LadderDatasetRecord>): Int = mutex.withLock {
        val existing = read()
        val known = existing.mapTo(mutableSetOf()) { it.id }
        val added = records.filter { known.add(it.id) }
        if (added.isNotEmpty()) {
            settings.putString(key, json.encodeToString(serializer, (existing + added).takeLast(capacity)))
        }
        added.size
    }

    override suspend fun all(): List<LadderDatasetRecord> = mutex.withLock { read() }

    override suspend fun clear() = mutex.withLock { settings.remove(key) }

    private fun read(): List<LadderDatasetRecord> {
        val stored = settings.getString(key, "")
        if (stored.isBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, stored)
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val KEY: String = "ladder.dataset.v1"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val serializer = ListSerializer(LadderDatasetRecord.serializer())
    }
}

object LadderDatasetExporter {
    private val json = Json { encodeDefaults = true }

    /** One JSON object per line, the corpus builder's input. */
    fun toJsonl(records: List<LadderDatasetRecord>): String =
        records.joinToString("\n") { json.encodeToString(LadderDatasetRecord.serializer(), it) }
}

/** Where the engine reports each persisted run, so a capture can read it. */
interface LadderDatasetSink {
    suspend fun observe(definition: WorkflowDefinition, run: WorkflowRun)
}

object NoOpLadderDatasetSink : LadderDatasetSink {
    override suspend fun observe(definition: WorkflowDefinition, run: WorkflowRun) = Unit
}

/** Records ladder runs while the keeper has switched capture on (off by default). */
class LadderDatasetCapture(
    private val settings: Settings,
    private val store: LadderDatasetStore = SettingsLadderDatasetStore(settings),
) : LadderDatasetSink {
    var enabled: Boolean
        get() = settings.getBoolean(ENABLED_KEY, false)
        set(value) = settings.putBoolean(ENABLED_KEY, value)

    override suspend fun observe(definition: WorkflowDefinition, run: WorkflowRun) {
        if (!enabled) return
        val records = LadderDatasetExtractor.extract(definition, run)
        if (records.isNotEmpty()) store.append(records)
    }

    suspend fun exportJsonl(): String = LadderDatasetExporter.toJsonl(store.all())

    suspend fun clear() = store.clear()

    companion object {
        const val ENABLED_KEY: String = "ladder.dataset.capture.enabled"
    }
}

/** Process-local seam, like `MemoryRuntimeBridge`: a no-op until a runtime attaches a capture. */
object LadderDatasetBridge {
    var sink: LadderDatasetSink = NoOpLadderDatasetSink
        private set

    var capture: LadderDatasetCapture? = null
        private set

    fun attach(settings: Settings): LadderDatasetCapture =
        LadderDatasetCapture(settings).also {
            capture = it
            sink = it
        }

    fun reset() {
        sink = NoOpLadderDatasetSink
        capture = null
    }
}
