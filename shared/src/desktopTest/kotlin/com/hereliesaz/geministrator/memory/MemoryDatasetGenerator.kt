package com.hereliesaz.geministrator.memory

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** One corpus row, in the same shape as the orchestration corpus. */
@Serializable
data class MemoryDatasetRow(
    val id: String,
    val input: String,
    val expected: String,
    val split: String,
    /** `[stage, regular|adversarial, class:<class>]`; the notebook gates every class on its own. */
    val tags: List<String>,
    /**
     * The copy shortcut for this row: the label's shape with every text copied verbatim from its
     * first source (for a summary-chain row, every paragraph joined; for a keep-apart condensation,
     * the condensation the shortcut would write). Null when the label writes nothing. The notebook
     * rejects a clerk that answers it on rows whose label differs.
     */
    val copy: String? = null,
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

    /**
     * Rows per role; regular sessions split 70/15/15 by session, hand-written ones are adversarial.
     *
     * Besides the packets the clerks receive, each split gets contrast material the engine never
     * offers (every condensation packet again with one member changed to state another value or
     * contrast, labelled as a decline) and the Summary Synthesizer gets the summary tree's and pair
     * summaries' requests (the chain's packets, labelled with the extractive engine's summary).
     */
    fun generate(sessions: Int = 120, seed: Int = 8): Map<MemoryMicroAgentRole, List<MemoryDatasetRow>> {
        val random = Random(seed)
        val regular = (0 until sessions).map { index -> SyntheticSession(session(index, random), split(index, sessions)) }
        val adversarial = ADVERSARIAL.mapIndexed { index, text ->
            SyntheticSession(envelope("adversarial-$index", "Remember this.", text, sessions + index), "adversarial")
        } + adversarialRestatements(sessions + ADVERSARIAL.size)
        dropped.clear()
        // Adversarial sessions share one store, so their restatements meet and condense.
        val (recorded, summaries) = record(regular.chunked(SESSIONS_PER_STORE) + listOf(adversarial))
        ROLES.forEach { role -> dropped[role to "recorded"] = recorded.count { it.role == role } }
        val candidates = recorded.mapNotNull { record ->
            labelled(record)?.let { (input, expected) -> Candidate(record.role, input, expected, record.split, record.packet.stage.name, classOf(record), copyOf(expected, record.packet)) }
        } + keepApart(recorded)
        val stageSummaries = candidates.filter { it.role == MemoryMicroAgentRole.SummarySynthesizer }.groupingBy { it.split }.eachCount()
        val chain = balancedChain(summaries.mapNotNull(::chainRow), stageSummaries)
        val seen = HashSet<Pair<MemoryMicroAgentRole, String>>()
        return ROLES.associateWith { role ->
            (candidates + chain).filter { it.role == role && seen.add(role to it.input) }
                .mapIndexed { index, candidate ->
                    MemoryDatasetRow(
                        id = "${slug(role)}-${index.toString().padStart(4, '0')}",
                        input = candidate.input,
                        expected = candidate.expected,
                        split = candidate.split,
                        tags = listOf(candidate.stage, if (candidate.split == "adversarial") "adversarial" else "regular", "class:${candidate.rowClass}"),
                        copy = candidate.copy,
                    )
                }
        }
    }

    /**
     * The tree and pair summaries ask far more often than the Summaries stage does; unbounded, chain
     * rows would swamp the stage's own rows. Per split, at most [CHAIN_ROWS_PER_STAGE_ROW] per stage
     * row (at least [MIN_CHAIN_ROWS]), taken round-robin over the chain's classes (unique inputs).
     */
    private fun balancedChain(rows: List<Candidate>, stageRows: Map<String, Int>): List<Candidate> =
        rows.distinctBy { it.input }.groupBy { it.split }.flatMap { (split, inSplit) ->
            val cap = maxOf(MIN_CHAIN_ROWS, CHAIN_ROWS_PER_STAGE_ROW * (stageRows[split] ?: 0))
            val queues = inSplit.groupBy { it.rowClass }.values.map { ArrayDeque(it) }
            buildList {
                while (size < cap && queues.any { it.isNotEmpty() }) queues.forEach { q -> if (size < cap) q.removeFirstOrNull()?.let(::add) }
            }.also { kept -> dropped.merge(MemoryMicroAgentRole.SummarySynthesizer to "summary chain: over the $split cap", inSplit.size - kept.size, Int::plus) }
        }

    private const val CHAIN_ROWS_PER_STAGE_ROW = 2
    private const val MIN_CHAIN_ROWS = 20

    private data class Candidate(
        val role: MemoryMicroAgentRole,
        val input: String,
        val expected: String,
        val split: String,
        val stage: String,
        val rowClass: String,
        val copy: String?,
    )

    private fun classOf(record: Recorded): String = when {
        record.role == MemoryMicroAgentRole.CondensationRewriter -> if (record.batch.size == 0) "keep-apart" else "condense"
        record.batch.size == 0 -> "empty"
        else -> "writes"
    }

    /**
     * The copy shortcut for [expected]: each section's and node's text replaced by its first source
     * item's text. Null when the label writes nothing (the empty shortcut covers those rows).
     */
    private fun copyOf(expected: String, packet: MemoryWorkPacket): String? {
        val items = (packet.items + packet.neighborhood).associate { it.id to it.text }
        val label = json.parseToJsonElement(expected).jsonObject
        fun copied(key: String) = JsonArray(
            label[key]?.jsonArray.orEmpty().map { entry ->
                val draft = entry.jsonObject
                val source = draft["sourceIds"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                val text = items[source] ?: return@map draft
                JsonObject(draft + ("text" to JsonPrimitive(text)))
            },
        )
        if (label["sections"]?.jsonArray.isNullOrEmpty() && label["nodes"]?.jsonArray.isNullOrEmpty()) return null
        return JsonObject(label + ("sections" to copied("sections")) + ("nodes" to copied("nodes"))).toString()
    }

    /**
     * For every condensation packet, the same packet with its last member changed to state another
     * value (or, without a value to change, to contrast: same frame, different filler). The engine
     * never offers such a cluster; a clerk that is shown one must decline it. Labelled by the
     * programmatic clerk (which declines), in the source packet's split, so the classes stay balanced.
     */
    private fun keepApart(recorded: List<Recorded>): List<Candidate> {
        val clerk = ProgrammaticMemoryClerks.all { 1L }.first { it.role == MemoryMicroAgentRole.CondensationRewriter }
        return recorded.filter { it.role == MemoryMicroAgentRole.CondensationRewriter && it.batch.size > 0 }
            .mapIndexedNotNull { index, source ->
                val items = source.packet.items
                val last = items.last()
                // Alternate the two kinds; a member with nothing to contrast (a one-word tag) changes a value.
                val values = { changedValue(last.text, index)?.let { "values" to it } }
                val contrast = { contrasted(last.text)?.let { "contrast" to it } }
                val (kind, text) = (if (index % 2 == 0) values() ?: contrast() else contrast() ?: values())
                    ?: return@mapIndexedNotNull null.also { dropped.merge(source.role to "no keep-apart variant", 1, Int::plus) }
                val changed = items.dropLast(1) + last.copy(text = text)
                if (!condensationWouldAdjudicate(changed.map(MemoryWorkItem::text))) return@mapIndexedNotNull null
                val packet = source.packet.copy(
                    items = changed,
                    packetKey = "condense-${changed.joinToString("|") { it.id + it.text }.hashCode().toString(16)}",
                )
                val batch = runBlocking { clerk.process(packet) }
                val record = Recorded(source.role, packet, batch, source.split)
                val (input, expected) = labelled(record) ?: return@mapIndexedNotNull null
                val shortcut = labelled(source)?.second?.let { copyOf(it, packet) }
                Candidate(source.role, input, expected, source.split, packet.stage.name, "keep-apart:$kind", shortcut)
            }
    }

    private fun changedValue(text: String, index: Int): String? {
        val number = Regex("\\b\\d+\\b").find(text)
        if (number != null) return text.replaceRange(number.range, (number.value.toInt() + 1 + index % 7).toString())
        val quoted = Regex("\"([^\"]+)\"").find(text)
        if (quoted != null) {
            return text.replaceRange(quoted.groups[1]!!.range, quoted.groupValues[1].uppercase().takeIf { it != quoted.groupValues[1] } ?: (quoted.groupValues[1] + " v2"))
        }
        // No value to change: flip the polarity, which is part of the claim too.
        val negation = Regex("\\b(not|never) ").find(text)
        if (negation != null) return text.removeRange(negation.range)
        val copula = Regex("\\b(is|are|must|does|will) ").find(text)
        return if (copula != null) text.replaceRange(copula.range, "${copula.groupValues[1]} not ") else "not $text"
    }

    private fun contrasted(text: String): String? = CONTRAST_SWAPS.firstNotNullOfOrNull { (from, to) ->
        Regex("\\b$from\\b").find(text)?.let { text.replaceRange(it.range, to) }?.takeIf { MemoryContrast.contrasts(text, it) }
    }

    private val CONTRAST_SWAPS = listOf(
        "dark" to "light", "warm" to "cool", "Maya" to "Jordan", "Jordan" to "Maya", "self-hosted" to "hosted",
        "before" to "after", "nightly" to "weekly", "every" to "no", "under" to "over", "owns" to "reviews",
        "reviews" to "owns", "does not" to "does", "never" to "always", "does" to "does not",
    )

    /** A summary-chain request as a Summary Synthesizer row, or null (counted) when it cannot be one. */
    private fun chainRow(record: ChainRecord): Candidate? {
        val role = MemoryMicroAgentRole.SummarySynthesizer
        fun drop(reason: String): Candidate? {
            dropped.merge(role to "summary chain: $reason", 1, Int::plus)
            return null
        }
        val request = record.request
        val model = MemoryEpoch8ModelCatalog.modelSpec(role)
        val packet = memorySummaryChainPacket(request.paragraphs, request.limit, request.instruction)
        val input = MemoryMicroAgentPrompts.render(packet, role)
        if (input.length > model.maxInputChars) return drop("over input budget")
        val text = record.summary.text.trim()
        // The chain validates a model's summary as abstractive; the label must pass that check too.
        if (text.isBlank() || !MemorySummarizerChain().validate(text, request, abstractive = true)) return drop("label fails the chain's check")
        fun proposal(summary: String) = MemoryMutationBatch(
            nodesToAdd = listOf(
                MemoryNode(
                    id = MemoryNodeId("summary"),
                    kind = MemoryNodeKind.Summary,
                    text = summary,
                    sourceSectionIds = packet.items.mapTo(linkedSetOf()) { MemorySectionId(it.id) },
                    createdAtEpochMillis = 1L,
                ),
            ),
        ).toMicroAgentProposalJson(packet)
        val expected = proposal(text) ?: return drop("not expressible")
        if (expected.length > model.maxOutputChars) return drop("over output budget")
        val decoded = runCatching {
            runBlocking {
                StructuredMemoryMicroAgent(role, model, ReplayRuntime(input, expected), nowEpochMillis = { 1L }).process(packet)
            }
        }.getOrElse { return drop("decode failed: ${it.message?.take(80)}") }
        if (decoded.nodesToAdd.singleOrNull()?.text != text) return drop("round trip differs")
        val rowClass = when {
            request.forbidden != null -> "chain:divergence"
            request.separator != PARAGRAPH_SEPARATOR -> "chain:pair"
            else -> "chain:tree"
        }
        // The copy shortcut keeps every paragraph, which never fits: the chain only asks when it does not.
        val copy = proposal(request.paragraphs.joinToString(request.separator))
        return Candidate(role, input, expected, record.split, "SummaryChain", rowClass, copy)
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
                    // Every class (tag `class:<name>`) of the gated rows, scored on its own, so a
                    // clerk cannot pass on the majority class (e.g. always writing, or never).
                    "min_class_score" to JsonPrimitive(0.8),
                    "min_class_rows" to JsonPrimitive(5),
                    // Shortcut baselines: copy the input (the row's `copy`), answer nothing, or always
                    // give the most common training answer. On the rows where a shortcut is wrong the
                    // clerk must still score this, and may give the shortcut's answer at most this often.
                    "shortcuts" to JsonArray(listOf("copy", "empty", "constant").map(::JsonPrimitive)),
                    "min_nontrivial_score" to JsonPrimitive(0.8),
                    "max_shortcut_rate" to JsonPrimitive(0.1),
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

    /**
     * Consolidates each block of sessions in order, in a fresh store, recording every packet a
     * generative clerk receives.
     */
    private data class ChainRecord(val request: MemorySummaryRequest, val summary: MemorySummary, val split: String)

    private fun record(blocks: List<List<SyntheticSession>>): Pair<List<Recorded>, List<ChainRecord>> = runBlocking {
        val recorded = mutableListOf<Recorded>()
        val summaries = mutableListOf<ChainRecord>()
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
        blocks.forEachIndexed { block, chunk ->
            val file = File.createTempFile("aive-memory-dataset", ".db").apply { deleteOnExit() }
            // The summary tree and pair summaries send the Summary Synthesizer clerk every request
            // the extractive engines answer here (verbatim ones never reach a model).
            val chain = MemorySummarizerChain().apply {
                observer = { request, summary ->
                    if (summary.engine != MemorySummarizerChain.ENGINE_VERBATIM) summaries += ChainRecord(request, summary, split)
                }
            }
            val layer = AgentMemoryLayer.createWithMicroAgents(desktopSqlMemoryStore(file), agents, POLICY, summarizer = chain)
            chunk.forEachIndexed { offset, session ->
                split = session.split
                layer.queue.enqueueSession(session.envelope)
                val now = (block * SESSIONS_PER_STORE + offset) * 1_000L
                while (layer.consolidateOne(now) != MemoryConsolidationResult.Idle) Unit
            }
            file.delete()
        }
        recorded to summaries
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

    /**
     * The runtime policy, except that clusters condense from three similar members instead of up to
     * nine. A condensation packet looks the same either way (at most `condensationBatchSize` members);
     * a lower threshold only produces more of them, which the Condensation Rewriter needs.
     */
    private val POLICY = MemoryConsolidationPolicy().let { default ->
        default.copy(maxSimilarPerKind = default.maxSimilarPerKind.mapValues { 2 })
    }

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
     * restates two recurring facts in a shared project, so similar memories pile up past the
     * condensation threshold; some restatements change a value, and those never condense.
     */
    private fun session(index: Int, random: Random): MemorySessionEnvelope {
        fun pick(list: List<String>) = list[random.nextInt(list.size)]
        if (index % 3 == 0) {
            val text = listOf(index / 3, index / 3 + RECURRING.size / 2).joinToString("\n\n") { turn ->
                val fact = RECURRING[turn % RECURRING.size]
                "${pick(RESTATEMENTS)} ${fact.replace("{n}", pick(listOf("30", "30", "30", "45")))}"
            }
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
        "The release build runs on the self-hosted runner.",
        "Maya owns the onboarding flow.",
        "The backup script runs every night at {n}:00.",
        "Commit messages stay under 72 characters.",
        "The invoice export uses the \"ledger\" template.",
        "The studio lighting plan uses warm bulbs only.",
        "Jordan reviews every database migration.",
        "The travel budget is {n} dollars per trip.",
        "The memory queue does not run during a deploy.",
    )
    private val RESTATEMENTS = listOf("Reminder:", "Again,", "For the record,", "Note:", "As before,")

    /**
     * Adversarial condensation material: facts restated three times with the same tricky values
     * (negation, quoted strings, several numbers), which a condensation must keep exactly. Restatements
     * that change a value are interleaved; those never reach the clerk and must stay apart.
     */
    private fun adversarialRestatements(firstIndex: Int): List<SyntheticSession> =
        ADVERSARIAL_RECURRING.flatMapIndexed { fact, (text, changed) ->
            (RESTATEMENTS.take(3).map { "$it $text" } + "Note: $changed").map { it to fact }
        }.mapIndexed { offset, (text, fact) ->
            val index = firstIndex + offset
            SyntheticSession(
                envelope("adversarial-restated-$offset", "Note this.", text, index).copy(projectId = "project-adversarial-$fact"),
                "adversarial",
            )
        }

    private val ADVERSARIAL_RECURRING = listOf(
        "Az does not want verbose logs." to "Az wants verbose logs.",
        "The banner must read \"Here lies Az\"." to "The banner must read \"Here lies AZ\".",
        "The retry limit is 3 attempts with a 250 ms backoff." to "The retry limit is 5 attempts with a 250 ms backoff.",
        "We never deploy on Fridays." to "We deploy on Fridays.",
    )

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
