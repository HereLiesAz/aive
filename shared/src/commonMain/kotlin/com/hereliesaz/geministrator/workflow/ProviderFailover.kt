package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.ErrorKind
import com.hereliesaz.geministrator.classifyError
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/*
 * Provider failover (docs/Orchestration_layer.md, Agent Router; docs/Keepers-Room.md).
 *
 * The rule: when more than one provider is connected and the keeper has left the choice to the app,
 * one provider failing must not stop the work. This file is the pure part of that rule: which
 * failures count, how long a failed provider is benched, which provider comes next, and what the
 * keeper is told. It changes only WHICH provider answers. It never approves anything, never widens
 * what a provider may see (only providers the keeper has connected are ever candidates) and never
 * touches HumanApproval or repository operations (docs/THREAT_MODEL.md).
 */

/** One provider's failure, kept so the keeper can be told exactly who failed and why. */
data class ProviderFailure(
    val providerId: String,
    val providerName: String,
    val kind: ErrorKind,
    val raw: String?,
)

/** Thrown when every connected provider failed; [message] names each one and what to do about it. */
class AllProvidersFailedException(val failures: List<ProviderFailure>) :
    RuntimeException(ProviderFailover.allFailedMessage(failures))

object ProviderFailover {
    /**
     * Whether a failure of [kind] is the provider's own, so another provider could do the same work.
     * Out of credit, a rejected key, rate limiting, being offline or timing out, and a provider-side
     * 5xx all are, and so is a reply cut off at the provider's output limit
     * ([ErrorKind.OutputTooLong]): another model may have a larger window. [ErrorKind.Other] is NOT: a malformed request, a refusal of this prompt's content
     * or any error we cannot place would fail the same way on every provider, and a retry elsewhere
     * would only spend the same content on a second service. [ErrorKind.NoAgent] is not a failure of
     * a provider at all (nothing connected can do the job).
     */
    fun shouldFailOver(kind: ErrorKind): Boolean = when (kind) {
        ErrorKind.KeyRejected,
        ErrorKind.OutOfCredit,
        ErrorKind.RateLimited,
        ErrorKind.Offline,
        ErrorKind.TimedOut,
        ErrorKind.ServerError,
        ErrorKind.OutputTooLong,
        -> true
        // A malformed request fails the same way on every provider, exactly as Other did before it was told apart.
        ErrorKind.NoAgent, ErrorKind.BadRequest, ErrorKind.Other -> false
    }

    fun shouldFailOver(raw: String?): Boolean = shouldFailOver(classifyError(raw))

    /**
     * How long a provider that failed this way is passed over while another one can do the work.
     * A spent balance or a rejected key stays that way until the keeper acts, so those wait longest;
     * a rate limit or a provider-side hiccup clears on its own.
     */
    fun cooldownMillis(kind: ErrorKind): Long = when (kind) {
        ErrorKind.OutOfCredit, ErrorKind.KeyRejected -> 30 * MINUTE
        ErrorKind.ServerError -> 2 * MINUTE
        ErrorKind.RateLimited, ErrorKind.TimedOut, ErrorKind.Offline -> MINUTE
        // The provider is healthy; this prompt was just too big for its window. No benching.
        ErrorKind.NoAgent, ErrorKind.OutputTooLong, ErrorKind.BadRequest, ErrorKind.Other -> 0L
    }

    private const val MINUTE = 60_000L

    fun failureOf(providerId: String, providerName: String, raw: String?): ProviderFailure =
        ProviderFailure(providerId, providerName, classifyError(raw), raw)

    /** "is out of credit", "rejected its key"... for a sentence starting with the provider's name. */
    fun reasonPhrase(kind: ErrorKind): String = when (kind) {
        ErrorKind.OutOfCredit -> "is out of credit"
        ErrorKind.KeyRejected -> "rejected its key"
        ErrorKind.RateLimited -> "is rate limited"
        ErrorKind.Offline -> "could not be reached"
        ErrorKind.TimedOut -> "took too long to answer"
        ErrorKind.ServerError -> "is having trouble"
        ErrorKind.NoAgent -> "cannot do this work"
        ErrorKind.OutputTooLong -> "ran out of room for its answer"
        ErrorKind.BadRequest -> "did not accept the request"
        ErrorKind.Other -> "failed"
    }

    /** What the keeper can do about [kind]. */
    fun fixFor(kind: ErrorKind): String = when (kind) {
        ErrorKind.OutOfCredit -> "Add credits in its billing settings."
        ErrorKind.KeyRejected -> "Reconnect it under Connections in the Keeper's room with a valid key."
        ErrorKind.RateLimited -> "Wait a minute."
        ErrorKind.Offline -> "Check your internet connection."
        ErrorKind.TimedOut, ErrorKind.ServerError -> "Try again in a moment."
        ErrorKind.OutputTooLong -> "Try a shorter description, or a provider with a larger limit."
        ErrorKind.NoAgent, ErrorKind.BadRequest, ErrorKind.Other -> "Check it under Connections in the Keeper's room."
    }

    /** The plain note shown when work moved on: not an error. */
    fun note(failure: ProviderFailure, continuedWith: String): String =
        "${failure.providerName} ${reasonPhrase(failure.kind)}; continued with $continuedWith."

    /** Every provider that failed, and the fixing action for each. */
    fun allFailedMessage(failures: List<ProviderFailure>): String {
        val distinct = failures.distinctBy { it.providerId }
        if (distinct.isNotEmpty() && distinct.all { it.kind == ErrorKind.OutputTooLong }) {
            return "The plan was too long for the provider's output limit (tried " +
                distinct.joinToString(", ") { it.providerName } +
                "). Try a shorter description, or a provider with a larger limit."
        }
        val lines = distinct.joinToString(" ") { "${it.providerName} ${reasonPhrase(it.kind)}. ${fixFor(it.kind)}" }
        return "Every connected provider failed, so this stopped. $lines"
    }

    /** A creature the keeper pinned to a provider stays there; it says so and offers the switch. */
    fun pinnedMessage(failure: ProviderFailure): String =
        "${failure.providerName} ${reasonPhrase(failure.kind)}. This creature is set to use it, so it did not " +
            "switch to another provider. ${fixFor(failure.kind)} Or choose another provider for this creature, " +
            "or set it to choose automatically."

    /**
     * The provider to try next: the first of [candidates] (already in the router's order, already
     * limited to providers connected and able to staff the role) that has not been tried in this
     * piece of work, healthy ones before benched ones. A benched provider is still a last resort, so
     * a keeper who has just added credit is not locked out for the whole cooldown, and a keeper with
     * one provider connected sees no change at all.
     */
    fun <T> nextCandidate(
        candidates: List<T>,
        idOf: (T) -> String,
        tried: Set<String>,
        isBenched: (String) -> Boolean,
    ): T? {
        val remaining = candidates.filter { idOf(it) !in tried }
        return remaining.firstOrNull { !isBenched(idOf(it)) } ?: remaining.firstOrNull()
    }
}

/**
 * Which providers are passed over for now, and the last failure each one had. Not thread-safe:
 * callers hold their own lock (the registry does).
 */
class ProviderBench @OptIn(ExperimentalTime::class) constructor(
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val until = HashMap<String, Long>()
    private val last = HashMap<String, ProviderFailure>()

    /** Passes [failure]'s provider over for the cooldown its kind earns. A non-failover failure is only remembered. */
    fun bench(failure: ProviderFailure) {
        last[failure.providerId] = failure
        val cooldown = ProviderFailover.cooldownMillis(failure.kind)
        if (cooldown > 0) until[failure.providerId] = now() + cooldown
    }

    fun isBenched(providerId: String): Boolean {
        val deadline = until[providerId] ?: return false
        if (now() >= deadline) {
            until.remove(providerId)
            return false
        }
        return true
    }

    fun lastFailure(providerId: String): ProviderFailure? = last[providerId]

    /** A provider that just worked is healthy again. */
    fun clear(providerId: String) {
        until.remove(providerId)
        last.remove(providerId)
    }
}

/** A line saying work moved to another provider, with when it happened. */
data class FailoverNote(val text: String, val atEpochMillis: Long)

/**
 * Carries failover notes from the code that switched providers (the planner, the swarm draft) up to
 * the screen showing that work's status, as [com.hereliesaz.geministrator.orchestration.LaunchProgress]
 * does for launch progress. With none installed, reporting is a no-op.
 */
class FailoverNotes(private val sink: (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    fun report(note: String) = sink(note)

    companion object Key : CoroutineContext.Key<FailoverNotes>
}

/** Reports [note] to the enclosing [FailoverNotes], if any. */
suspend fun reportFailoverNote(note: String) {
    coroutineContext[FailoverNotes]?.report(note)
}
