package com.hereliesaz.geministrator.workflow

import com.russhwolf.settings.Settings

/**
 * What this app's own steps took, by kind of work, kept on this device so the queen's Auto time allowance learns
 * (docs/Keepers-Room.md, Time allowed). Each sample is the active milliseconds of one ended attempt: a step that
 * finished, or one that was stopped at its stop point (a censored sample, so a slow Source raises its own figure
 * instead of being stopped at the same point forever). Only the newest [CAP] per kind are kept, so it stays small
 * and the allowance follows how the keeper's Sources behave now. [None] keeps nothing, which is what tests use.
 */
class DeadlineHistory(private val settings: Settings?) {
    /** The kept samples for [kind], oldest first; empty when none or when nothing is kept. */
    fun samples(kind: WorkKind): List<Long> =
        settings?.getStringOrNull(key(kind))?.let(::parse).orEmpty()

    /** Adds one ended attempt; zero, negative and absurd figures are ignored. */
    fun record(kind: WorkKind, activeMillis: Long) {
        val store = settings ?: return
        if (activeMillis <= 0L || activeMillis > MAX_SAMPLE_MILLIS) return
        store.putString(key(kind), format(add(samples(kind), activeMillis)))
    }

    companion object {
        /** How many samples are kept per kind. */
        const val CAP = 40

        /** A sample longer than this (a day) is a clock fault, not a duration. */
        const val MAX_SAMPLE_MILLIS = 24L * 60L * 60L * 1000L

        /** A history that keeps nothing. */
        val None: DeadlineHistory = DeadlineHistory(null)

        internal fun key(kind: WorkKind): String = "aive.deadline.history.v1.${kind.name}"

        internal fun add(list: List<Long>, value: Long): List<Long> = (list + value).takeLast(CAP)

        internal fun parse(text: String): List<Long> =
            text.split(',')
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it in 1..MAX_SAMPLE_MILLIS }
                .takeLast(CAP)

        internal fun format(list: List<Long>): String = list.joinToString(",")
    }
}
