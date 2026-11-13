package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.error_bad_request
import com.hereliesaz.geministrator.resources.error_details_hide
import com.hereliesaz.geministrator.resources.error_details_label
import com.hereliesaz.geministrator.resources.error_details_show
import com.hereliesaz.geministrator.resources.error_key_rejected
import com.hereliesaz.geministrator.resources.error_no_agent
import com.hereliesaz.geministrator.resources.error_offline
import com.hereliesaz.geministrator.resources.error_other
import com.hereliesaz.geministrator.resources.error_output_too_long
import com.hereliesaz.geministrator.resources.error_out_of_credit
import com.hereliesaz.geministrator.resources.error_rate_limited
import com.hereliesaz.geministrator.resources.error_server_error
import com.hereliesaz.geministrator.resources.error_timed_out
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Coarse cause of a raw failure message, enough to tell the user what to do next. */
enum class ErrorKind { KeyRejected, OutOfCredit, RateLimited, Offline, TimedOut, ServerError, NoAgent, OutputTooLong, BadRequest, Other }

private val unauthorizedCode = Regex("""(^|[^0-9])40[13]([^0-9]|$)""")
private val paymentRequiredCode = Regex("""(^|[^0-9])402([^0-9]|$)""")
private val rateLimitCode = Regex("""(^|[^0-9])429([^0-9]|$)""")
private val badRequestCode = Regex("""(^|[^0-9])400([^0-9]|$)""")
private val serverErrorCode = Regex("""(^|[^0-9])(500|502|503|504|529)([^0-9]|$)""")

/** Pure classifier from a raw exception or HTTP error message to an [ErrorKind]. */
fun classifyError(raw: String?): ErrorKind {
    val text = raw?.lowercase().orEmpty()
    if (text.isBlank()) return ErrorKind.Other
    return when {
        // Before the codes: "no agent provider satisfies…" must not read as a rejected key.
        "no agent provider satisfies" in text || "only inside a linked repository" in text ||
            "runs against a linked" in text || "cannot resume without its linked" in text ||
            "is not registered" in text -> ErrorKind.NoAgent

        // A reply cut off at the provider's output limit: not a key, quota or outage problem.
        "truncated" in text && "token" in text -> ErrorKind.OutputTooLong

        unauthorizedCode.containsMatchIn(text) ||
            "unauthorized" in text || "forbidden" in text || "invalid api key" in text ||
            "invalid_api_key" in text || "incorrect api key" in text || "authentication" in text ||
            "permission denied" in text -> ErrorKind.KeyRejected

        // Before the rate limit: a spent balance often names its "quota", but waiting will not fix it.
        paymentRequiredCode.containsMatchIn(text) ||
            "payment required" in text || "payment_required" in text || "insufficient_quota" in text ||
            "insufficient balance" in text || "insufficient credit" in text || "credit balance" in text ||
            "out of credit" in text || "requires credits" in text || "add credits" in text || "need credits" in text || "billing" in text -> ErrorKind.OutOfCredit

        rateLimitCode.containsMatchIn(text) ||
            "rate limit" in text || "rate-limit" in text || "ratelimit" in text ||
            "too many requests" in text || "quota" in text || "resource_exhausted" in text -> ErrorKind.RateLimited

        // A provider that is up but failing: failing over to another one is the right answer (ProviderFailover.kt).
        serverErrorCode.containsMatchIn(text) ||
            "internal server error" in text || "bad gateway" in text || "service unavailable" in text ||
            "overloaded" in text || "server error" in text -> ErrorKind.ServerError

        // A request the provider refused as malformed: its wording says which field, the keeper cannot fix that.
        badRequestCode.containsMatchIn(text) || "invalid_request" in text || "missing required parameter" in text ||
            "bad request" in text -> ErrorKind.BadRequest

        "timeout" in text || "timed out" in text || "deadline exceeded" in text -> ErrorKind.TimedOut

        "unable to resolve host" in text || "unknownhost" in text || "unknown host" in text ||
            "connection refused" in text || "failed to connect" in text || "network is unreachable" in text ||
            "no address associated" in text || "offline" in text || "connectexception" in text ||
            "connection reset" in text || "no route to host" in text || "unreachable" in text -> ErrorKind.Offline

        else -> ErrorKind.Other
    }
}

private val statusCodeInText = Regex("""(^|[^0-9])[45][0-9][0-9]([^0-9]|$)""")

/**
 * Whether [raw] reads as a sentence the app itself wrote ("Tests did not pass"), to be shown as it is, rather
 * than text a provider or the runtime produced (an HTTP body, an exception), which is said plainly instead
 * ([friendlyErrorMessage]) with the original behind Technical details.
 */
fun isPlainReason(raw: String?): Boolean {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.length > PLAIN_REASON_LIMIT || classifyError(text) != ErrorKind.Other) return false
    val lower = text.lowercase()
    return '{' !in text && '}' !in text && "http" !in lower && "exception" !in lower && "error:" !in lower &&
        "\n" !in text && !statusCodeInText.containsMatchIn(text)
}

private const val PLAIN_REASON_LIMIT = 160

internal fun ErrorKind.messageResource(): StringResource = when (this) {
    ErrorKind.KeyRejected -> Res.string.error_key_rejected
    ErrorKind.OutOfCredit -> Res.string.error_out_of_credit
    ErrorKind.RateLimited -> Res.string.error_rate_limited
    ErrorKind.Offline -> Res.string.error_offline
    ErrorKind.TimedOut -> Res.string.error_timed_out
    ErrorKind.ServerError -> Res.string.error_server_error
    ErrorKind.NoAgent -> Res.string.error_no_agent
    ErrorKind.OutputTooLong -> Res.string.error_output_too_long
    ErrorKind.BadRequest -> Res.string.error_bad_request
    ErrorKind.Other -> Res.string.error_other
}

/** Short plain-language message with a next step for [raw], for use inside composition. */
@Composable
internal fun friendlyErrorMessage(raw: String?): String = stringResource(classifyError(raw).messageResource())

/** A failure's one plain line: the app's own sentence as it is, anything technical said plainly. */
@Composable
internal fun plainFailureLine(raw: String?): String =
    if (isPlainReason(raw)) raw.orEmpty().trim() else friendlyErrorMessage(raw)

/** [plainFailureLine] as a suspending call, for code outside composition. */
internal suspend fun plainFailureLineOutsideComposition(raw: String?): String =
    if (isPlainReason(raw)) raw.orEmpty().trim() else friendlyErrorMessageOutsideComposition(raw)

/** Same as [friendlyErrorMessage] for code that runs outside composition. */
internal suspend fun friendlyErrorMessageOutsideComposition(raw: String?): String =
    getString(classifyError(raw).messageResource())

/**
 * A failure said in one plain line, with the raw text behind Technical details when it is technical. The text
 * takes the surface it sits on.
 */
@Composable
internal fun FailureText(raw: String?, seed: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = AzphaltType.body) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.s)) {
        Text(plainFailureLine(raw), style = style, color = azphaltOnSurface())
        if (!isPlainReason(raw)) ErrorDetailsToggle(raw = raw, seed = seed)
    }
}

private const val RAW_DETAIL_LIMIT = 300

/** A plain [summary] and its [raw] text as one string: the summary is the first line, the raw text the rest. */
fun packFailureText(summary: String, raw: String?): String {
    val detail = raw?.trim().orEmpty().take(RAW_DETAIL_LIMIT)
    return if (detail.isEmpty()) summary else summary + "\n" + detail
}

/** The inverse of [packFailureText]: the summary, and the raw text when there is any. */
fun unpackFailureText(packed: String): Pair<String, String?> {
    val summary = packed.substringBefore('\n')
    val detail = packed.substringAfter('\n', "").trim()
    return summary to detail.ifEmpty { null }
}

/** A [packFailureText] string shown as its summary, with the raw text behind Technical details. */
@Composable
internal fun PackedFailureText(
    packed: String,
    seed: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = AzphaltType.body,
) {
    val (summary, raw) = unpackFailureText(packed)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.s)) {
        Text(summary, style = style, color = azphaltOnSurface())
        ErrorDetailsToggle(raw = raw, seed = seed)
    }
}

/** A "Show details" toggle that reveals the raw technical text. Renders nothing when [raw] is blank. */
@Composable
internal fun ErrorDetailsToggle(raw: String?, seed: String, modifier: Modifier = Modifier) {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return
    var open by remember(text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.s)) {
        AzphaltPill(
            label = stringResource(if (open) Res.string.error_details_hide else Res.string.error_details_show),
            seed = "error-details-$seed",
            selected = open,
            onClick = { open = !open },
        )
        if (open) {
            Text(
                stringResource(Res.string.error_details_label) + ": " + text,
                style = AzphaltType.body,
                color = azphaltOnSurface(),
            )
        }
    }
}
