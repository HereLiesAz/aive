package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_dismiss
import org.jetbrains.compose.resources.stringResource

/**
 * A button press (Approve, Stop, Pause...) that did not go through. It is a notice about that press,
 * not a change to the hive: the hive keeps whatever state it had.
 */
internal data class ActionFailure(val what: String, val raw: String)

internal fun actionFailureOf(what: String, failure: Throwable): ActionFailure = ActionFailure(
    what = what,
    raw = failure.message?.trim().takeUnless { it.isNullOrEmpty() } ?: failure::class.simpleName.orEmpty(),
)

/**
 * A failure said plainly: what was being done, one sentence of cause and next step, the raw text behind a
 * "Technical details" toggle, then an optional fix button and Dismiss.
 */
@Composable
internal fun FailureNotice(
    what: String,
    raw: String?,
    seed: String,
    modifier: Modifier = Modifier,
    fixLabel: String? = null,
    onFix: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    AzphaltRecord(
        seed = "failure-$seed",
        eyebrow = what,
        title = friendlyErrorMessage(raw),
        modifier = modifier,
        well = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AzphaltSpacing.s),
            ) {
                if (fixLabel != null && onFix != null) {
                    AzphaltPill(label = fixLabel, seed = "failure-fix-$seed", onClick = onFix)
                }
                ErrorDetailsToggle(raw = raw, seed = seed)
                if (onDismiss != null) {
                    AzphaltPill(label = stringResource(Res.string.common_dismiss), seed = "failure-dismiss-$seed", onClick = onDismiss)
                }
            }
        },
    )
}
