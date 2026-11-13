package com.hereliesaz.geministrator.workflow

import com.russhwolf.settings.Settings

/**
 * What this app's own steps took, kept on this device so the queen's Auto time allowance learns
 * (docs/Keepers-Room.md, Time allowed). Each sample is the active milliseconds of one ended attempt: a step that
 * finished, or one stopped at its stop point (a censored sample, so a slow Source raises its own figure instead of
 * being stopped at the same point forever).
 *
 * A step's duration depends on which Source ran it and on which task it was, so samples are kept for each of
 * those, most specific first: the same task on the same Source, then that Source for this kind of work, then that
 * task for this kind of work, then the kind of work alone. [samples] answers with the most specific list that has
 * enough evidence ([ENOUGH]) and falls back down the list; [record] adds one attempt to all of them. Only the
 * newest [CAP] per list are kept. [None] keeps nothing, which is what tests use.
 */
class DeadlineHistory(private val settings: Settings?) {
    /**
     * The samples to judge a step by, oldest first: the most specific of (source and task), (source), (task) and
     * (kind alone) that has at least [ENOUGH], else the kind's own list (which may be short or empty).
     */
    fun samples(kind: WorkKind, source: String? = null, task: String? = null): List<Long> {
        val store = settings ?: return emptyList()
        for (key in specificKeys(kind, source, task)) {
            val kept = read(store, key)
            if (kept.size >= ENOUGH) return kept
        }
        return read(store, key(kind))
    }

    /** Adds one ended attempt to the kind's list and to each list for the [source] and [task] it was given; nonsense figures are ignored. */
    fun record(kind: WorkKind, activeMillis: Long, source: String? = null, task: String? = null) {
        val store = settings ?: return
        if (activeMillis <= 0L || activeMillis > MAX_SAMPLE_MILLIS) return
        for (key in specificKeys(kind, source, task) + key(kind)) {
            store.putString(key, format(add(read(store, key), activeMillis)))
        }
    }

    private fun read(store: Settings, key: String): List<Long> = store.getStringOrNull(key)?.let(::parse).orEmpty()

    companion object {
        /** How many samples are kept per list. */
        const val CAP = 40

        /** The fewest samples a more specific list needs before it is used instead of a broader one. */
        const val ENOUGH = 5

        /** A sample longer than this (a day) is a clock fault, not a duration. */
        const val MAX_SAMPLE_MILLIS = 24L * 60L * 60L * 1000L

        /** A history that keeps nothing. */
        val None: DeadlineHistory = DeadlineHistory(null)

        /** The kind-alone list (what [samples] falls back to). */
        internal fun key(kind: WorkKind): String = "aive.deadline.history.v1.${kind.name}"

        /** Source and task ids can be long, and a desktop preference key may not be: they are folded to eight hex digits. */
        internal fun fold(text: String): String {
            var h = 0x811c9dc5L
            for (c in text) {
                h = h xor c.code.toLong()
                h = (h * 0x01000193L) and 0xffffffffL
            }
            return h.toString(16).padStart(8, '0')
        }

        /** The more specific lists for a step, most specific first (the kind-alone list is not among them). */
        internal fun specificKeys(kind: WorkKind, source: String?, task: String?): List<String> {
            val base = "aive.deadline.history.v2.${kind.name}"
            val s = source?.takeIf { it.isNotBlank() }?.let(::fold)
            val t = task?.takeIf { it.isNotBlank() }?.let(::fold)
            return buildList {
                if (s != null && t != null) add("$base.s$s.t$t")
                if (s != null) add("$base.s$s")
                if (t != null) add("$base.t$t")
            }
        }

        internal fun add(list: List<Long>, value: Long): List<Long> = (list + value).takeLast(CAP)

        internal fun parse(text: String): List<Long> =
            text.split(',')
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it in 1..MAX_SAMPLE_MILLIS }
                .takeLast(CAP)

        internal fun format(list: List<Long>): String = list.joinToString(",")
    }
}
