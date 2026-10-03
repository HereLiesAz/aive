package com.hereliesaz.geministrator.memory

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/** One corpus row, in the same shape as the orchestration corpus. */
@Serializable
data class MemoryDatasetRow(
    val id: String,
    val input: String,
    val expected: String,
    val split: String,
    val tags: List<String>,
)

/**
 * Seed corpus for the eight generative memory clerks, derived from the running memory layer.
 *
 * Synthetic sessions are consolidated with the programmatic clerks behind recorders that present
 * the local model's limits, so every packet is fitted exactly as the runtime fits it for a model.
 * `input` is [MemoryMicroAgentPrompts.render] of that packet (the user turn; the system turn is the
 * config's `system_prompt`) and `expected` is the programmatic clerk's answer in the JSON contract.
 * A row is kept only when its label decodes through [StructuredMemoryMicroAgent] back to the clerk's
 * own batch, so the labels are exactly what the runtime accepts. Like the orchestration corpus, it is
 * a distillation seed: it teaches the contract and the conservative baseline, nothing beyond it.
 */
object MemoryDatasetGenerator {
    val ROLES: List<MemoryMicroAgentRole> =
        MemoryMicroAgentRouter.REQUIRED_ROLES.filter { it != MemoryMicroAgentRole.AssociationLinker }

    val json: Json = Json { encodeDefaults = true }

    fun slug(role: MemoryMicroAgentRole): String =
        role.name.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()

    /** Rows per role; regular sessions split 70/15/15 by session, hand-written ones are adversarial. */
    fun generate(sessions: Int = 120, seed: Int = 8): Map<MemoryMicroAgentRole, List<MemoryDatasetRow>> {
        val random = Random(seed)
        val regular = (0 until sessions).map { index -> SyntheticSession(session(index, random), split(index, sessions)) }
        val adversarial = ADVERSARIAL.mapIndexed { index, text ->
            SyntheticSession(envelope("adversarial-$index", "Remember this.", text, sessions + index), "adversarial")
        }
        dropped.clear()
        val recorded = record(regular + adversarial)
        ROLES.forEach { role -> dropped[role to "recorded"] = recorded.count { it.role == role } }
        val seen = HashSet<Pair<MemoryMicroAgentRole, String>>()
        return ROLES.associateWith { role ->
            recorded.filter { it.role == role }
                .mapNotNull { record -> labelled(record)?.takeIf { seen.add(role to it.first) }?.let { record to it } }
                .mapIndexed { index, (record, pair) ->
                    MemoryDatasetRow(
                        id = "${slug(role)}-${index.toString().padStart(4, '0')}",
                        input = pair.first,
                        expected = pair.second,
                        split = record.split,
                        tags = listOf(record.packet.stage.name, if (record.split == "adversarial") "adversarial" else "regular"),
                    )
                }
        }
    }

    /** Pipeline config for [role]; answers are scored with `json_exact`. */
    fun config(role: MemoryMicroAgentRole): JsonObject = JsonObject(
        mapOf(
            "specialist_id" to JsonPrimitive("memory:${slug(role)}"),
            "system_prompt" to JsonPrimitive(MemoryMicroAgentPrompts.system(role)),
            "foundation_model_id" to JsonPrimitive("Qwen/Qwen2.5-0.5B-Instruct"),
            "metric" to JsonPrimitive("json_exact"),
            "gates" to JsonObject(
                mapOf(
                    "min_test_score" to JsonPrimitive(0.9),
                    "min_adversarial_score" to JsonPrimitive(0.9),
                ),
            ),
        ),
    )

    private data class SyntheticSession(val envelope: MemorySessionEnvelope, val split: String)

    private data class Recorded(
        val role: MemoryMicroAgentRole,
        val packet: MemoryWorkPacket,
        val batch: MemoryMutationBatch,
        val split: String,
    )

    /** Consolidates [sessions] in order, recording every packet a generative clerk receives. */
    private fun record(sessions: List<SyntheticSession>): List<Recorded> = runBlocking {
        val recorded = mutableListOf<Recorded>()
        var split = "train"
        val agents = ProgrammaticMemoryClerks.all { 1L }.map { clerk ->
            if (clerk.role == MemoryMicroAgentRole.AssociationLinker) {
                clerk
            } else {
                object : MemoryMicroAgent {
                    override val role = clerk.role

                    // The model's input limits fit the packet; output limits stay the clerk's so the
                    // router does not reject an answer the label filter will judge on its own.
                    override val model = MemoryEpoch8ModelCatalog.modelSpec(clerk.role).copy(
                        maxOutputChars = clerk.model.maxOutputChars,
                        maxMutations = clerk.model.maxMutations,
                    )

                    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch =
                        clerk.process(packet).also { recorded += Recorded(role, packet, it, split) }
                }
            }
        }
        // A fresh SQLite store per block keeps the graph, and each commit, bounded.
        sessions.chunked(SESSIONS_PER_STORE).forEachIndexed { block, chunk ->
            val file = File.createTempFile("aive-memory-dataset", ".db").apply { deleteOnExit() }
            val layer = AgentMemoryLayer.createWithMicroAgents(desktopSqlMemoryStore(file), agents)
            chunk.forEachIndexed { offset, session ->
                split = session.split
                layer.queue.enqueueSession(session.envelope)
                val now = (block * SESSIONS_PER_STORE + offset) * 1_000L
                while (layer.consolidateOne(now) != MemoryConsolidationResult.Idle) Unit
            }
            file.delete()
        }
        recorded
    }

    /** Why recorded packets were not used, per role and reason, from the last [generate]. */
    val dropped: MutableMap<Pair<MemoryMicroAgentRole, String>, Int> = linkedMapOf()

    /** (input, expected) when the label fits the model and decodes back to the clerk's batch. */
    private fun labelled(record: Recorded): Pair<String, String>? {
        fun drop(reason: String): Pair<String, String>? {
            dropped.merge(record.role to reason, 1, Int::plus)
            return null
        }
        val model = MemoryEpoch8ModelCatalog.modelSpec(record.role)
        val input = MemoryMicroAgentPrompts.render(record.packet, record.role)
        val expected = record.batch.toMicroAgentProposalJson(record.packet) ?: return drop("not expressible")
        if (expected.length > model.maxOutputChars || record.batch.size > model.maxMutations) return drop("over output budget")
        val decoded = runCatching {
            runBlocking {
                StructuredMemoryMicroAgent(record.role, model, ReplayRuntime(input, expected), nowEpochMillis = { 1L })
                    .process(record.packet)
            }
        }.getOrElse { return drop("decode failed: ${it.message?.take(80)}") }
        if (canonical(decoded) != canonical(record.batch)) return drop("round trip differs")
        return input to expected
    }

    /** What a batch means, independent of the IDs the decoder or a clerk generated. */
    private fun canonical(batch: MemoryMutationBatch): List<Any> {
        val newIndex = batch.nodesToAdd.mapIndexed { index, node -> node.id to "new:$index" }.toMap()
        fun meta(metadata: Map<String, String>) = metadata - setOf("microAgentRole", "semanticSource")
        return listOf(
            batch.sectionsToAdd.map { listOf(it.text.trim(), it.sourceChunkIds, meta(it.metadata)) },
            batch.nodesToAdd.map {
                listOf(it.kind, it.text.trim(), it.salience, it.confidence, it.sourceSectionIds, meta(it.metadata))
            },
            batch.edgesToAdd.map {
                listOf(newIndex[it.from] ?: it.from.value, newIndex[it.to] ?: it.to.value, it.relation, it.weight, meta(it.metadata))
            },
        )
    }

    /** Answers the decoder with the label, and fails if the prompt is not the corpus input. */
    private class ReplayRuntime(private val input: String, private val answer: String) : MemoryGenerativeInferenceRuntime {
        override val platform = MemoryMicroAgentPlatform.Linux
        override val computePreference = MemoryComputePreference.AUTO
        override val capabilityDetector = object : HardwareCapabilityDetector {
            override suspend fun discover(): List<MemoryComputeDevice> = emptyList()
        }

        override suspend fun isAvailable(model: MemoryMicroAgentModelSpec, artifact: MemoryMicroAgentArtifact) = true

        override suspend fun generate(request: MemoryGenerativeInferenceRequest): MemoryGenerativeInferenceResult {
            check(request.prompt == input) { "decoder prompt differs from the corpus input" }
            return MemoryGenerativeInferenceResult(answer)
        }

        override suspend fun executionReport(modelId: String): MemoryExecutionReport? = null
    }

    private const val SESSIONS_PER_STORE = 150

    private fun split(index: Int, total: Int): String = when {
        index < total * 70 / 100 -> "train"
        index < total * 85 / 100 -> "validation"
        else -> "test"
    }

    private fun envelope(id: String, prompt: String, text: String, index: Int) = MemorySessionEnvelope(
        sourceSessionId = id,
        projectId = "project-${index % 4}",
        workflowRunId = "run-${index / 5}",
        userPrompt = prompt,
        parts = listOf(MemorySessionPart(MemorySourceKind.Message, "Agent", text)),
        closedAtEpochMillis = index * 1_000L,
    )

    /**
     * A session mixing decisions, values, technical detail, plain prose and chatter. Every third one
     * restates a recurring fact in a shared project, so similar memories pile up past the
     * condensation threshold; some restatements change a value, which condensation must decline.
     */
    private fun session(index: Int, random: Random): MemorySessionEnvelope {
        fun pick(list: List<String>) = list[random.nextInt(list.size)]
        if (index % 3 == 0) {
            val fact = RECURRING[(index / 3) % RECURRING.size]
            val text = "${pick(RESTATEMENTS)} ${fact.replace("{n}", pick(listOf("30", "30", "30", "45")))}"
            return envelope("session-$index", "Note this.", text, index).copy(projectId = "project-recurring")
        }
        val subject = pick(SUBJECTS)
        val paragraphs = buildList {
            repeat(random.nextInt(2, 5)) {
                add(
                    when (random.nextInt(6)) {
                        0 -> "We decided to ${pick(ACTIONS)} $subject because ${pick(REASONS)}."
                        1 -> "${pick(PEOPLE)} said the ${pick(METRICS)} is now ${random.nextInt(2, 900)} ${pick(UNITS)}, " +
                            "down from ${random.nextInt(900, 2_000)}."
                        2 -> "I changed `${pick(FILES)}` to ${pick(ACTIONS)} ${pick(SUBJECTS)}; see #${random.nextInt(1, 500)}."
                        3 -> "${pick(PEOPLE)} prefers ${pick(PREFERENCES)} and does not want ${pick(PREFERENCES)}."
                        4 -> pick(CHATTER)
                        else -> "The meeting about ${pick(TOPICS)} moved to ${pick(DAYS)} at ${random.nextInt(8, 18)}:00."
                    },
                )
            }
        }
        return envelope("session-$index", "${pick(ACTIONS).replaceFirstChar(Char::uppercase)} $subject.", paragraphs.joinToString("\n\n"), index)
    }

    private val SUBJECTS = listOf(
        "the gradle build", "the release notes", "the onboarding flow", "the memory queue", "the invoice export",
        "the dark theme", "the planner prompt", "the API timeout", "the garden plan", "the studio lighting",
        "the mural sketch", "the backup script", "the travel budget", "the database migration", "the test suite",
    )
    private val ACTIONS = listOf("refactor", "fix", "replace", "migrate", "test", "document", "simplify", "remove", "postpone")
    private val REASONS = listOf(
        "the old one failed on Windows", "users asked for it", "latency doubled", "it was never used",
        "the deadline moved", "it cost too much", "nobody could read it",
    )
    private val PEOPLE = listOf("Az", "Maya", "Jordan", "the reviewer", "the client", "Sam")
    private val METRICS = listOf("build time", "error rate", "monthly cost", "page weight", "queue depth", "render time")
    private val UNITS = listOf("ms", "seconds", "dollars", "KB", "items", "percent")
    private val FILES = listOf("build.gradle.kts", "App.kt", "MemoryStore.kt", "README.md", "deploy.yml", "theme.css")
    private val PREFERENCES = listOf(
        "dark backgrounds", "short commit messages", "tildes in code fences", "monochrome palettes",
        "morning meetings", "tabs", "spaces", "verbose logs",
    )
    private val CHATTER = listOf("ok", "thanks!", "sounds good", "brb", "lol yes", "sure, go ahead")
    private val TOPICS = listOf("the launch", "the budget", "the mural", "hiring", "the roadmap")
    private val DAYS = listOf("Monday", "Tuesday", "Thursday", "Friday")

    private val RECURRING = listOf(
        "Az prefers dark backgrounds for the studio site.",
        "The API timeout is {n} seconds.",
        "The mural sketch is due before the gallery opening.",
    )
    private val RESTATEMENTS = listOf("Reminder:", "Again,", "For the record,", "Note:", "As before,")

    /** Edge cases: values that differ, negation, chatter-only, quoted strings, empty-ish content. */
    private val ADVERSARIAL = listOf(
        "The API timeout is 30 seconds.\n\nThe API timeout is 60 seconds.",
        "Az does not want verbose logs.\n\nAz wants verbose logs.",
        "ok\n\nthanks!\n\nsure, go ahead",
        "The banner must read \"Here lies Az\" and not \"Here lies AZ\".",
        "We will not migrate the database this week.",
        "Maya said the error rate is 0 percent.\n\nJordan said the error rate is 4 percent.",
    )
}
