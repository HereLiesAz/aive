package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.platformDebugLog
import kotlinx.coroutines.CancellationException

/** The phases of loading the Store screen, so a failure can say which one it was. */
enum class AzphaltStoreStep(val label: String) {
    Index("reading the catalogue index"),
    Search("searching the catalogue"),
    Installed("reading installed packages"),
    Updates("checking for updates"),
    Revocations("checking for withdrawn packages"),
}

/**
 * A Store failure that names the phase it happened in. The original exception is always kept as
 * [cause] and its class is always in the message, so a failure that has no message of its own
 * (a bare `NullPointerException`, a TLS error with no text) still says what it was and where.
 */
class AzphaltStoreStepException(val step: AzphaltStoreStep, cause: Throwable) :
    RuntimeException("The Store failed while ${step.label}: ${describeStoreFailure(cause)}", cause)

private fun failureClassName(failure: Throwable): String = failure::class.simpleName ?: "Exception"

/** `ClassName: message`, with "no message" spelled out rather than left blank. */
fun describeStoreFailure(failure: Throwable): String =
    failureClassName(failure) + ": " + (failure.message?.takeIf(String::isNotBlank) ?: "no message")

/**
 * Technical text for a Details toggle: the failing step (when known), the exception class and
 * message, and the chain of causes. Never blank.
 */
fun storeFailureDetails(failure: Throwable): String = buildString {
    val stepFailure = failure as? AzphaltStoreStepException
    if (stepFailure != null) appendLine("Step: ${stepFailure.step.label}")
    val root = stepFailure?.cause ?: failure
    appendLine("Exception: ${failureClassName(root)}")
    append("Message: ").append(root.message?.takeIf(String::isNotBlank) ?: "(none)")
    var cause = root.cause
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        append('\n').append("Caused by: ").append(describeStoreFailure(cause))
        cause = cause.cause
        depth++
    }
}

private const val MAX_CAUSE_DEPTH = 6

/**
 * Runs one phase of a Store operation. A failure is logged with its class, message and stack trace
 * and rethrown as an [AzphaltStoreStepException] naming [step]. Cancellation is not a failure and
 * passes through untouched, as does a failure already attributed to a step.
 *
 * Everything else is wrapped, including `Error`s such as a class stripped by shrinking, because a
 * phase that dies silently is exactly what this exists to expose.
 */
suspend fun <T> storeStep(step: AzphaltStoreStep, block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (attributed: AzphaltStoreStepException) {
    throw attributed
} catch (failure: Throwable) {
    // Logging must never mask the failure it reports (android.util.Log is absent in unit tests).
    runCatching {
        platformDebugLog(
            "AzphaltStore",
            "Store step '${step.name}' failed: ${describeStoreFailure(failure)}\n${failure.stackTraceToString()}",
        )
    }
    throw AzphaltStoreStepException(step, failure)
}
