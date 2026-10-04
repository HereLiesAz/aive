package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.memory.MemoryLanguageResources
import com.hereliesaz.geministrator.memory.MemorySalienceFeatures
import com.hereliesaz.geministrator.memory.MemoryTechnicalLexicon
import com.hereliesaz.geministrator.memory.WordNetLexicon

/*
 * The computations behind the deterministic baselines that the specialist model inputs also expose,
 * so a model is handed the answer's ingredients instead of asked to compute them. None of it judges
 * whether anything is true; it ranks, counts, orders and fits.
 */

// ---- Memory Query Composer -------------------------------------------------------------------

/**
 * Lowercase words the way the memory index splits them: letters, digits, `_` and `-`, two or more
 * characters (so "sync-worker" stays one word, as it is stored).
 */
internal fun queryWords(text: String): List<String> {
    val out = mutableListOf<String>()
    val token = StringBuilder()
    fun flush() {
        if (token.length > 1) out += token.toString()
        token.clear()
    }
    text.lowercase().forEach { char -> if (char.isLetterOrDigit() || char == '_' || char == '-') token.append(char) else flush() }
    flush()
    return out
}

/**
 * [items] rarest first by [df] (stored memories per word; an item counts as its rarest known word).
 * Items none of whose words any memory holds go last, since they can match nothing. Stable.
 */
internal fun rarestFirst(items: List<String>, df: Map<String, Int>): List<String> {
    if (df.isEmpty()) return items
    fun count(item: String): Int? = queryWords(item).mapNotNull { df[it] }.filter { it > 0 }.minOrNull()
    return items.withIndex()
        .sortedWith(
            compareBy<IndexedValue<String>> { if (count(it.value) == null) 1 else 0 }
                .thenBy { count(it.value) ?: 0 }
                .thenBy { it.index },
        )
        .map { it.value }
}

/** Up to three first-pass result words the input does not already query, rarest first; null when none. */
internal fun feedbackQueryText(input: MemoryQueryInput): String? {
    if (input.feedbackTerms.isEmpty()) return null
    val taken = (listOf(input.objective) + input.knownEntities + input.knownActions + input.codeSymbols)
        .flatMap(::queryWords)
        .toSet()
    val terms = input.feedbackTerms.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it !in taken }.distinct()
    return rarestFirst(terms, input.termDocumentFrequency).take(MAX_FEEDBACK_TERMS).joinToString(" ").ifEmpty { null }
}

/**
 * Synonyms for an action query: the technical overlay's group first; otherwise, when WordNet is
 * loaded, the single-word lemmas of the verb's most frequent sense (so results for a verb the overlay
 * lacks depend on whether the clerks have loaded WordNet yet). Verbs only: noun synonyms widen
 * recall into unrelated things far more often than verb synonyms do.
 */
internal fun actionSynonyms(action: String): List<String> {
    val lemma = action.trim().lowercase()
    if (lemma.isEmpty() || ' ' in lemma) return emptyList()
    val out = LinkedHashSet<String>()
    // Inflected forms reach the overlay through plain suffix rules, so the lookup needs no WordNet.
    val bases = listOf(lemma, lemma.removeSuffix("ed"), lemma.removeSuffix("d"), lemma.removeSuffix("ing"), lemma.removeSuffix("ing") + "e", lemma.removeSuffix("es"), lemma.removeSuffix("s"))
    bases.firstNotNullOfOrNull { MemoryTechnicalLexicon.lookup(it, WordNetLexicon.Pos.Verb) }?.let { term ->
        out += term.canonical
        out += term.synonyms
    }
    if (out.isEmpty()) {
        MemoryLanguageResources.loadedOrNull()?.wordNet?.let { wordNet ->
            val base = wordNet.baseForms(lemma, WordNetLexicon.Pos.Verb).firstOrNull() ?: lemma
            wordNet.senses(base, WordNetLexicon.Pos.Verb).firstOrNull()?.let { sense ->
                out += wordNet.lemmas(sense).filter { ' ' !in it }
            }
        }
    }
    out.removeAll(bases.toSet())
    return out.take(MAX_ACTION_SYNONYMS)
}

private const val MAX_FEEDBACK_TERMS = 3
private const val MAX_ACTION_SYNONYMS = 4

// ---- Context Packer --------------------------------------------------------------------------

internal data class PackedGroup(
    val evidenceIds: List<String>,
    val tokens: Int,
    val required: Boolean,
    val priority: Int,
    /** Id of the higher-ranked item this (single) item nearly repeats; it is skipped. */
    val repeatOf: String?,
    val selected: Boolean,
    /** Running token total, in rank order, after this group is considered. */
    val tokensUsedAfter: Int,
)

internal data class ContextPacking(
    /** Groups in rank order: required first, then priority tiers, input order within a tier. */
    val groups: List<PackedGroup>,
    /** Selected evidence ids in prompt order. */
    val promptOrder: List<String>,
    val tokensUsed: Int,
    /** Skipped evidence id -> the id it repeats. */
    val repeatOf: Map<String, String>,
)

/**
 * Packs evidence into [ContextPackingInput.tokenBudget].
 *
 * - Required groups go in first; one that does not fit is an error, as before.
 * - The rest is decided one priority tier at a time, highest first. Within a tier the subset of
 *   groups that uses the most of the remaining budget is chosen exactly (0/1 knapsack; earlier groups
 *   win ties); a tier too large to solve exactly is filled greedily in input order.
 * - An ungrouped, optional item that repeats a higher-ranked item nearly word for word (shingle
 *   Jaccard >= 0.8, or declared by [ContextEvidence.repeatOf]) is skipped before the budget is
 *   filled. Conflict groups are never thinned: both sides of a disagreement stay or go together.
 * - Prompt order puts the most important group first, the next most important last, and the rest
 *   between, since models read the middle of a long context least reliably.
 */
internal fun contextPacking(input: ContextPackingInput): ContextPacking {
    require(input.tokenBudget >= 0) { "tokenBudget must be non-negative" }
    require(input.evidence.all { it.id.isNotBlank() && it.estimatedTokens >= 0 }) {
        "evidence ids must be non-blank and token estimates non-negative"
    }
    require(input.evidence.map { it.id }.distinct().size == input.evidence.size) {
        "evidence ids must be unique"
    }

    val ranked = input.evidence
        .groupBy { it.conflictGroup ?: "__single__:${it.id}" }
        .values
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<List<ContextEvidence>>> { group -> group.value.any { it.required } }
                .thenByDescending { group -> group.value.maxOf { it.priority } }
                .thenBy { it.index },
        )
        .map { it.value }

    val kept = mutableListOf<Pair<String, Set<String>?>>()
    val keptIds = HashSet<String>()
    fun keep(group: List<ContextEvidence>) = group.forEach { evidence ->
        keptIds += evidence.id
        kept += evidence.id to evidence.text?.let(::repeatShingles)
    }
    val repeatOf = linkedMapOf<String, String>()
    fun repeated(group: List<ContextEvidence>): String? {
        val evidence = group.singleOrNull()?.takeIf { it.conflictGroup == null && !it.required } ?: return null
        evidence.repeatOf?.takeIf(keptIds::contains)?.let { return it }
        val shingles = evidence.text?.let(::repeatShingles)?.takeIf { it.isNotEmpty() } ?: return null
        return kept.firstOrNull { (_, other) -> other != null && MemorySalienceFeatures.jaccard(shingles, other) >= REPEAT_JACCARD }?.first
    }

    var used = 0
    val selected = HashSet<List<ContextEvidence>>()
    val usedAfter = HashMap<List<ContextEvidence>, Int>()
    ranked.filter { group -> group.any { it.required } }.forEach { group ->
        val cost = group.sumOf { it.estimatedTokens }
        if (used + cost > input.tokenBudget) {
            error("Required context group ${group.first().conflictGroup ?: group.first().id} does not fit token budget")
        }
        used += cost
        selected += group
        usedAfter[group] = used
        keep(group)
    }
    val tiers = ranked.filterNot { group -> group.any { it.required } }.groupBy { group -> group.maxOf { it.priority } }
    tiers.values.forEach { tier ->
        val candidates = mutableListOf<List<ContextEvidence>>()
        tier.forEach { group ->
            val original = repeated(group)
            if (original != null) {
                repeatOf[group.single().id] = original
                usedAfter[group] = used
            } else {
                candidates += group
                keep(group)
            }
        }
        val chosen = knapsack(candidates.map { group -> group.sumOf { it.estimatedTokens } }, input.tokenBudget - used)
        candidates.forEachIndexed { index, group ->
            if (index in chosen) {
                selected += group
                used += group.sumOf { it.estimatedTokens }
            }
            usedAfter[group] = used
        }
    }

    val inRank = ranked.filter { it in selected }
    val front = inRank.filterIndexed { index, _ -> index % 2 == 0 }
    val back = inRank.filterIndexed { index, _ -> index % 2 == 1 }
    return ContextPacking(
        groups = ranked.map { group ->
            PackedGroup(
                evidenceIds = group.map { it.id },
                tokens = group.sumOf { it.estimatedTokens },
                required = group.any { it.required },
                priority = group.maxOf { it.priority },
                repeatOf = group.singleOrNull()?.id?.let(repeatOf::get),
                selected = group in selected,
                tokensUsedAfter = usedAfter[group] ?: used,
            )
        },
        promptOrder = (front + back.asReversed()).flatMap { group -> group.map { it.id } },
        tokensUsed = used,
        repeatOf = repeatOf,
    )
}

/**
 * Indexes of [costs] whose sum is the largest that fits [capacity] (exact 0/1 knapsack where value
 * equals cost). Free items are always taken; ties keep earlier items. Past [MAX_KNAPSACK_CELLS] the
 * items are taken greedily in order instead.
 */
internal fun knapsack(costs: List<Int>, capacity: Int): Set<Int> {
    val chosen = costs.indices.filter { costs[it] == 0 }.toMutableSet()
    if (capacity <= 0) return chosen
    val items = costs.indices.filter { costs[it] in 1..capacity }
    if (items.isEmpty()) return chosen
    if (items.size.toLong() * (capacity + 1) > MAX_KNAPSACK_CELLS) {
        var left = capacity
        items.forEach { if (costs[it] <= left) { chosen += it; left -= costs[it] } }
        return chosen
    }
    val reach = Array(items.size + 1) { BooleanArray(capacity + 1) }
    reach[0][0] = true
    items.forEachIndexed { k, item ->
        val cost = costs[item]
        val previous = reach[k]
        val next = reach[k + 1]
        for (sum in 0..capacity) next[sum] = previous[sum] || (sum >= cost && previous[sum - cost])
    }
    var sum = (capacity downTo 0).first { reach[items.size][it] }
    // Walk back, leaving out the later item whenever the same sum is reachable without it.
    for (k in items.indices.reversed()) {
        if (!reach[k][sum]) {
            chosen += items[k]
            sum -= costs[items[k]]
        }
    }
    return chosen
}

/** Shingles over letters and digits only, so punctuation and spacing differences do not count. */
private fun repeatShingles(text: String): Set<String> =
    MemorySalienceFeatures.shingles(text.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim())
private const val REPEAT_JACCARD = 0.8
private const val MAX_KNAPSACK_CELLS = 4_000_000L

// ---- Agent Router ----------------------------------------------------------------------------

internal data class AgentRouting(
    /** Agents that may be chosen, in routing order. */
    val eligible: List<AgentRouteCandidate>,
    /** Capable agents whose circuit is open. */
    val open: List<AgentRouteCandidate>,
    /** Every capable agent is open; the least-failed one gets a trial run. */
    val halfOpen: Boolean,
)

/** Routing order: preference rank, then cost per unit of reliability, then id. */
internal val agentRoutingOrder: Comparator<AgentRouteCandidate> =
    compareBy<AgentRouteCandidate> { it.preferenceRank }.thenBy(::costPerReliability).thenBy { it.id }

/** Estimated cost divided by success rate (unknown counts as 1; floored at 0.05). */
internal fun costPerReliability(candidate: AgentRouteCandidate): Double =
    candidate.estimatedCost / maxOf(candidate.successRate ?: 1.0, MIN_RELIABILITY)

internal fun capableAgent(candidate: AgentRouteCandidate, input: AgentRoutingInput): Boolean =
    candidate.available &&
        candidate.contextLimitTokens >= input.requiredContextTokens &&
        candidate.capabilities.containsAll(input.requiredCapabilities)

/**
 * Circuit breaking: an agent with [CIRCUIT_OPEN_FAILURES] failures in a row is passed over while any
 * other capable agent remains. When every capable agent is open, the one with the fewest failures
 * gets a single trial (half-open), so routing never stalls on history alone.
 */
internal fun agentRouting(input: AgentRoutingInput): AgentRouting {
    val capable = input.candidates.filter { capableAgent(it, input) }.sortedWith(agentRoutingOrder)
    val (open, closed) = capable.partition { it.consecutiveFailures >= CIRCUIT_OPEN_FAILURES }
    return when {
        closed.isNotEmpty() -> AgentRouting(closed, open, halfOpen = false)
        open.isNotEmpty() -> AgentRouting(
            listOf(open.minWith(compareBy<AgentRouteCandidate> { it.consecutiveFailures }.then(agentRoutingOrder))),
            open,
            halfOpen = true,
        )
        else -> AgentRouting(emptyList(), emptyList(), halfOpen = false)
    }
}

private const val MIN_RELIABILITY = 0.05

// ---- Handoff Composer ------------------------------------------------------------------------

/** Cut order when a handoff is over its size limit: least important section first. */
private val HANDOFF_CUT_ORDER = listOf("provenance", "state", "artifacts", "completed", "acceptance criteria", "unresolved", "failures")

/**
 * Fits [packet]'s rendered content to [maxChars] by dropping items from the end of the least important sections
 * ([HANDOFF_CUT_ORDER]); the objective and next action are never cut. Each cut is recorded as
 * "section: n dropped"; a packet still over the limit carries the OVER_SIZE_LIMIT warning.
 * Item sources are kept only for items that remain.
 */
internal fun fitHandoff(packet: HandoffPacket, maxChars: Int?): HandoffPacket {
    fun pruned(p: HandoffPacket): HandoffPacket {
        val items = (p.completed + p.artifacts + p.unresolved + p.failures + p.acceptanceCriteria + p.provenance + p.state.keys).toSet()
        return p.copy(sources = p.sources.filterKeys(items::contains))
    }
    var current = pruned(packet)
    fun size(p: HandoffPacket) = renderHandoff(p, includeCuts = false).length
    if (maxChars == null || size(current) <= maxChars) return current
    val dropped = linkedMapOf<String, Int>()
    fun withCuts(p: HandoffPacket) = pruned(p.copy(cuts = dropped.map { (section, count) -> "$section: $count dropped" }))
    for (section in HANDOFF_CUT_ORDER) {
        while (size(current) > maxChars) {
            val next = when (section) {
                "provenance" -> current.provenance.takeIf { it.isNotEmpty() }?.let { current.copy(provenance = it.dropLast(1)) }
                "state" -> current.state.takeIf { it.isNotEmpty() }?.let { current.copy(state = it.entries.toList().dropLast(1).associate { e -> e.key to e.value }) }
                "artifacts" -> current.artifacts.takeIf { it.isNotEmpty() }?.let { current.copy(artifacts = it.dropLast(1)) }
                "completed" -> current.completed.takeIf { it.isNotEmpty() }?.let { current.copy(completed = it.dropLast(1)) }
                "acceptance criteria" -> current.acceptanceCriteria.takeIf { it.isNotEmpty() }?.let { current.copy(acceptanceCriteria = it.dropLast(1)) }
                "unresolved" -> current.unresolved.takeIf { it.isNotEmpty() }?.let { current.copy(unresolved = it.dropLast(1)) }
                else -> current.failures.takeIf { it.isNotEmpty() }?.let { current.copy(failures = it.dropLast(1)) }
            } ?: break
            dropped[section] = (dropped[section] ?: 0) + 1
            current = withCuts(next)
        }
    }
    return if (size(current) > maxChars) current.copy(warnings = current.warnings + "OVER_SIZE_LIMIT") else current
}

/**
 * The packet as fixed sections, most important first; empty sections are left out. The size limit
 * covers this content; the one-line note of what was cut is appended outside it.
 */
internal fun renderHandoff(packet: HandoffPacket, includeCuts: Boolean = true): String = buildString {
    fun line(text: String) {
        if (isNotEmpty()) append('\n')
        append(text)
    }
    fun item(text: String) = packet.sources[text]?.let { "$text ($it)" } ?: text
    fun section(title: String, items: List<String>) {
        if (items.isEmpty()) return
        line("$title:")
        items.forEach { line("- ${item(it)}") }
    }
    if (packet.objective.isNotEmpty()) line("Objective: ${packet.objective}")
    packet.nextAction?.let { line("Next action: $it") }
    section("Failures", packet.failures)
    section("Unresolved", packet.unresolved)
    section("Acceptance criteria", packet.acceptanceCriteria)
    section("Completed", packet.completed)
    section("Artifacts", packet.artifacts)
    if (packet.state.isNotEmpty()) {
        line("State:")
        packet.state.forEach { (key, value) -> line("- ${item(key)}: $value") }
    }
    section("Provenance", packet.provenance)
    if (includeCuts && packet.cuts.isNotEmpty()) line("Cut to fit: ${packet.cuts.joinToString("; ")}")
}

// ---- Execution State Summarizer --------------------------------------------------------------

/**
 * The longest chain of unfinished tasks (not Completed or Cancelled) still to run, by task count,
 * in dependency order; ties go to the task earlier in execution order.
 */
internal fun criticalPath(definition: WorkflowDefinition, run: WorkflowRun): List<String> {
    val order = executionOrder(definition)
    val position = order.withIndex().associate { (i, id) -> id to i }
    val unfinished = order.filter { id ->
        val status = run.taskRuns[id]?.status
        status != TaskRunStatus.Completed && status != TaskRunStatus.Cancelled
    }.toSet()
    if (unfinished.isEmpty()) return emptyList()
    val dependencies = definition.tasks.associate { it.id to it.dependsOn }
    val length = HashMap<TaskDefinitionId, Int>()
    val previous = HashMap<TaskDefinitionId, TaskDefinitionId>()
    val longestFirst = compareBy<TaskDefinitionId> { length[it] ?: 0 }.thenByDescending { position.getValue(it) }
    order.filter(unfinished::contains).forEach { id ->
        val best = dependencies[id].orEmpty()
            .filter { it in unfinished && position.getValue(it) < position.getValue(id) }
            .maxWithOrNull(longestFirst)
        length[id] = 1 + (best?.let { length.getValue(it) } ?: 0)
        best?.let { previous[id] = it }
    }
    val names = definition.tasks.associate { it.id to it.name }
    var cursor: TaskDefinitionId? = unfinished.maxWith(longestFirst)
    val path = mutableListOf<String>()
    while (cursor != null) {
        path += names[cursor] ?: cursor.value
        cursor = previous[cursor]
    }
    return path.asReversed()
}

/**
 * Stopped tasks (Failed, Blocked, Escalated, Cancelled) with a blocking reason, grouped by
 * "code: message pattern", where the pattern masks numbers, hex, paths, timestamps and quoted values.
 */
internal fun failureGroups(definition: WorkflowDefinition, run: WorkflowRun): Map<String, List<String>> {
    val stopped = setOf(TaskRunStatus.Failed, TaskRunStatus.Blocked, TaskRunStatus.Escalated, TaskRunStatus.Cancelled)
    val names = definition.tasks.associate { it.id to it.name }
    val ordered = executionOrder(definition) + run.taskRuns.keys.filter { id -> definition.tasks.none { it.id == id } }
    val groups = linkedMapOf<String, MutableList<String>>()
    ordered.forEach { id ->
        val taskRun = run.taskRuns[id] ?: return@forEach
        val reason = taskRun.blockingReason ?: return@forEach
        if (taskRun.status !in stopped) return@forEach
        val pattern = MemorySalienceFeatures.lineTemplate(reason.message).ifBlank { "-" }
        groups.getOrPut("${reason.code}: $pattern") { mutableListOf() } += names[id] ?: id.value
    }
    return groups
}

// ---- Verification Planner --------------------------------------------------------------------

internal fun verificationStepKey(operation: String, criterion: String?, platform: String?, targets: List<String>): String =
    listOf(operation, criterion.orEmpty(), platform.orEmpty(), targets.joinToString(",")).joinToString("|")

/**
 * Tests to run for [changedFiles], by each ecosystem's naming convention: a changed test file runs
 * itself; a source file runs its conventional test (Kotlin/Java/Swift/C# `FooTest`, Python
 * `test_foo`, Go `foo_test`, JS/TS `foo.test`). Non-code files pick nothing. Sorted, repeats dropped.
 */
internal fun testTargetsFor(changedFiles: List<String>): List<String> =
    changedFiles.map(String::trim).filter(String::isNotEmpty).mapNotNull { path ->
        val name = path.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in CODE_EXTENSIONS) return@mapNotNull null
        val base = name.substringBefore('.')
        val isTest = TEST_PATH.containsMatchIn(path) || TEST_NAME.containsMatchIn(name.substringBeforeLast('.'))
        when {
            isTest -> name.substringBeforeLast('.')
            extension == "py" -> "test_$base"
            extension == "go" -> "${base}_test"
            extension in JS_EXTENSIONS -> "$base.test"
            else -> "${base}Test"
        }
    }.distinct().sorted()

private val CODE_EXTENSIONS = setOf("kt", "kts", "java", "scala", "groovy", "swift", "cs", "py", "go", "js", "jsx", "ts", "tsx", "mjs", "cjs", "rb", "rs", "php")
private val JS_EXTENSIONS = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs")
private val TEST_PATH = Regex("(^|/)(src/(test|commonTest|androidTest|desktopTest|jvmTest|iosTest|jsTest)|tests?|__tests__|spec)/")
private val TEST_NAME = Regex("(Test|Tests|Spec|IT)$|^test_|_test$|\\.(test|spec)$")
