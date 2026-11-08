package com.hereliesaz.geministrator.memory.procedural

/** A formed habit chosen for a task, with why it matched. Nothing here runs anything. */
data class HabitOffer(
    val habit: FormedHabit,
    /** The words of the habit's operation that the task also uses. */
    val matchedCues: List<String>,
)

/**
 * Picks the few formed habits worth putting in front of a model, and says what memory knows about
 * each as plain facts and counts. It does not score a habit as good or bad, and the text it writes
 * never tells the reader what to do: a habit is something the model may use or not.
 */
object HabitOffers {
    const val MAX_OFFERS = 3
    const val MAX_CHARS = 1500

    /** Words every operation of a kind shares; matching on them would match everything. */
    private val genericWords = setOf(
        "github", "action", "deployment", "repository", "service", "nested", "workflow",
        "yml", "yaml", "script", "test", "runner",
    )

    private val splitter = Regex("[^A-Za-z0-9]+")

    private fun words(text: String): List<String> =
        text.lowercase().split(splitter).filter { it.length >= 2 }

    /** A habit's cue: the plain words of its operation, which are what a task would name. */
    fun cuesOf(habit: FormedHabit): List<String> = cuesOf(habit.operation)

    fun cuesOf(operation: String): List<String> =
        words(operation)
            .filter { it !in genericWords && !it.all(Char::isDigit) }
            .distinct()

    /**
     * The offered habits whose cue appears in [objective], most matched first, then most used. At
     * most [MAX_OFFERS]. A habit still forming is never offered.
     */
    fun select(habits: List<FormedHabit>, objective: String): List<HabitOffer> {
        val taskWords = words(objective).toSet()
        // An active override is shown wherever its cue appears, even before the habit is offered.
        return habits.filter { it.offered || it.activeOverride != null }
            .mapNotNull { habit ->
                val matched = cuesOf(habit).filter { it in taskWords }
                if (matched.isEmpty()) null else HabitOffer(habit, matched)
            }
            .sortedWith(
                compareByDescending<HabitOffer> { it.matchedCues.size }
                    .thenByDescending { it.habit.uses }
                    .thenBy { it.habit.operation },
            )
            .take(MAX_OFFERS)
    }

    /** The offer as text for a prompt, or null when there is nothing to offer. */
    fun render(offers: List<HabitOffer>, nowEpochMillis: Long): String? {
        if (offers.isEmpty()) return null
        val header = "Habits formed from earlier runs. This is information only: nothing here runs, " +
            "and you may use one of them or not."
        val entries = ArrayList<String>()
        var length = header.length
        for (offer in offers) {
            val entry = entry(offer.habit, nowEpochMillis)
            if (length + entry.length + 1 > MAX_CHARS) break
            entries += entry
            length += entry.length + 1
        }
        if (entries.isEmpty()) return null
        return (listOf(header) + entries).joinToString("\n")
    }

    /** The sequences whose first step a task names, at most two. */
    fun selectSequences(sequences: List<FormedSequence>, objective: String): List<FormedSequence> {
        val taskWords = words(objective).toSet()
        return sequences.filter { sequence -> cuesOf(sequence.operations.first()).any { it in taskWords } }.take(2)
    }

    /** Sequences as text for a prompt, or null. The order is observed, not a dependency. */
    fun renderSequences(sequences: List<FormedSequence>, nowEpochMillis: Long): String? {
        if (sequences.isEmpty()) return null
        return (
            listOf("Done together, in this order, in earlier runs (the order is observed, not a dependency):") +
                sequences.map { sequence ->
                    "- " + sequence.operations.joinToString(", then ") + ": in " + sequence.runs + " runs, " +
                        sequence.cleanRuns + " of them with every step clean. Last seen " +
                        ago(sequence.lastSeenAtEpochMillis, nowEpochMillis) + "."
                }
            ).joinToString("\n").take(MAX_CHARS)
    }

    /** One habit as the facts the offer states, for a screen that lists habits. */
    fun describe(habit: FormedHabit, nowEpochMillis: Long): String = entry(habit, nowEpochMillis)

    private fun entry(habit: FormedHabit, nowEpochMillis: Long): String = buildString {
        append("- ").append(habit.operation).append(": used ").append(habit.uses)
            .append(if (habit.uses == 1) " time" else " times")
            .append(" (").append(habit.completedUses).append(" completed, ").append(habit.failedUses).append(" failed). ")
        append("Reviewed: ").append(habit.verifiedUses).append(" approved, ")
            .append(habit.rejectedUses).append(" turned back, ")
            .append(habit.unreviewedUses).append(" not reviewed. ")
        if (habit.recentUses > 0) {
            append("Newest ").append(habit.recentUses).append(if (habit.recentUses == 1) " use: " else " uses: ")
                .append(habit.recentFailedUses).append(" failed. ")
        }
        append("Last used ").append(ago(habit.lastUsedAtEpochMillis, nowEpochMillis)).append(". Strength: ")
            .append(habit.strength.name.lowercase()).append(".")
        if (habit.environments.isNotEmpty()) {
            append("\n  Done in ").append(habit.environments.joinToString(", ") { "${it.name} (${it.uses})" }).append(".")
        }
        for (slot in habit.slots) {
            append("\n  ").append(slot.name).append(" has been ")
                .append(slot.values.joinToString(", ") { "${it.value} (${it.count})" }).append(".")
        }
        if (habit.version > 1) {
            append("\n  Version ").append(habit.version).append(": ").append(habit.earlierUses)
                .append(if (habit.earlierUses == 1) " earlier use is" else " earlier uses are")
                .append(" history and not counted above.")
        }
        habit.openContrasts.forEach { contrast ->
            append("\n  Open contrast: with ").append(contrast.signature).append(", it has completed ")
                .append(contrast.completed).append(if (contrast.completed == 1) " time" else " times")
                .append(" and been failed or turned back ").append(contrast.turnedBack)
                .append(if (contrast.turnedBack == 1) " time." else " times.")
        }
        habit.habitOverride?.let { o ->
            if (o.retired) {
                append("\n  Earlier override by ").append(o.author).append(" (retired: its alternative ")
                    .append(o.alternativeOperation).append(" has been used ").append(o.alternativeUses)
                    .append(" times and is a habit of its own): \"").append(quoted(o.text)).append("\"")
            } else {
                append("\n  Override by ").append(o.author).append(": \"").append(quoted(o.text))
                    .append("\" Instead: \"").append(quoted(o.alternative)).append("\".")
                append(" Since it was written this habit has been used ").append(o.usesSince)
                    .append(if (o.usesSince == 1) " time" else " times")
                o.alternativeUses?.let { passes ->
                    append(" and ").append(o.alternativeOperation).append(" ")
                        .append(passes).append(if (passes == 1) " time" else " times")
                        .append(" (it becomes a habit of its own at ")
                        .append(HabitSettings().controlledPassesToConsolidate).append(" uses)")
                }
                append(".")
            }
        }
        habit.lastDeliberation?.takeIf { it.outcome != ProcedureDeliberationOutcome.Overrides }?.let { decision ->
            append("\n  Latest decision (").append(decision.outcome.name.lowercase()).append(", ")
                .append(decision.author).append("): \"").append(quoted(decision.text)).append("\"")
        }
        if (habit.openContrasts.isNotEmpty() && habit.lastDeliberation == null) {
            append("\n  No decision about it has been written.")
        }
        val flags = habit.flags
        if (flags.total == 0) {
            append("\n  Flags: none yet.")
        } else {
            append("\n  Flags: ").append(flags.used).append(" by models that used it, ")
                .append(flags.handled).append(" by models that handled its results, ")
                .append(flags.keeper).append(" by the keeper. Newest, quoted as written:")
            flags.newest.forEach { flag ->
                append("\n    \"").append(quoted(flag.text)).append("\" (")
                    .append(flag.source.name.lowercase()).append(", ").append(flag.author)
                    .append(if (flag.tainted) ", written with untrusted content in view)" else ")")
            }
        }
    }

    /** A flag is data: one line, with no quote mark that could end the quotation early. */
    private fun quoted(text: String): String = text.replace('"', '\'').replace(Regex("\\s+"), " ").trim()

    private fun ago(thenEpochMillis: Long, nowEpochMillis: Long): String {
        val days = ((nowEpochMillis - thenEpochMillis) / MILLIS_PER_DAY).coerceAtLeast(0)
        return when (days) {
            0L -> "today"
            1L -> "yesterday"
            else -> "$days days ago"
        }
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
