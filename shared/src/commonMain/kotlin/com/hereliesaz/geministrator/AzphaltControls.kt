package com.hereliesaz.geministrator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Primary Azphalt button: an Ink pill (white where Ink would vanish into the surface) with the exact
 * opposite of its container for its label, readable on every ground, including Mustard where a
 * Yellow container would vanish. Disabled, it
 * is an opaque, softened Ink with a label still at 3:1 or better against it. At least 48dp tall.
 */
@Composable
internal fun AzphaltButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val surface = azphaltSurface()
    // Ink where the pill stands out from the surface (3:1), White where Ink would melt into it
    // (navy, an Ink well); the label is always the exact opposite of whichever container it is.
    val container = primaryButtonContainerOn(surface)
    val disabledContainer = container.copy(alpha = 0.45f).compositeOver(surface)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = AzphaltMinTouchTarget),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = container.onOpposite(),
            disabledContainerColor = disabledContainer,
            disabledContentColor = disabledContainer.mutedOnOpposite(),
        ),
        contentPadding = PaddingValues(horizontal = AzphaltSpacing.xl, vertical = AzphaltSpacing.m),
    ) {
        Text(label.uppercase(), style = AzphaltType.capsule)
    }
}

/**
 * Secondary Azphalt button: transparent, so its outline and label are the exact opposite of the
 * surface it sits on. Disabled, both soften but stay at 3:1 or better.
 */
@Composable
internal fun AzphaltOutlinedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val onSurface = azphaltOnSurface()
    val muted = azphaltMutedOnSurface()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = AzphaltMinTouchTarget),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(2.dp, if (enabled) onSurface else muted),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = onSurface,
            disabledContentColor = muted,
        ),
        contentPadding = PaddingValues(horizontal = AzphaltSpacing.xl, vertical = AzphaltSpacing.m),
    ) {
        Text(label.uppercase(), style = AzphaltType.capsule)
    }
}

/**
 * Outlined text field in the colours of the surface it sits on (the exact opposite of it for text,
 * label, outline and cursor; softened, never under 3:1, for placeholder and disabled). With [secret] the value is masked and a
 * [showLabel]/[hideLabel] text button next to it reveals it, so a bad paste can be checked.
 */
@Composable
internal fun AzphaltTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    showLabel: String = "",
    hideLabel: String = "",
    placeholder: String? = null,
    errorMessage: String? = null,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
) {
    var revealed by remember { mutableStateOf(false) }
    val onGround = azphaltOnSurface()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { hint -> { Text(hint) } },
        singleLine = singleLine,
        readOnly = readOnly,
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret && showLabel.isNotEmpty()) {
            {
                TextButton(
                    onClick = { revealed = !revealed },
                    modifier = Modifier.defaultMinSize(minWidth = AzphaltMinTouchTarget, minHeight = AzphaltMinTouchTarget),
                ) {
                    Text(if (revealed) hideLabel else showLabel, color = onGround)
                }
            }
        } else {
            null
        },
        isError = errorMessage != null,
        supportingText = errorMessage?.let { message -> { Text(message, modifier = Modifier.azphaltLiveRegion()) } },
        colors = azphaltTextFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Outlined text field colours for the surface the field sits on: text, label, outline, cursor and
 * icons are the exact opposite of it; placeholder and disabled states are softened but never drop
 * under 3:1 against it. Error states keep the same colours (the error is carried by the message,
 * not by a red that can vanish on the ground).
 */
@Composable
internal fun azphaltTextFieldColors(): TextFieldColors {
    val onGround = azphaltOnSurface()
    val muted = azphaltMutedOnSurface()
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = onGround,
        unfocusedTextColor = onGround,
        disabledTextColor = muted,
        errorTextColor = onGround,
        focusedBorderColor = onGround,
        unfocusedBorderColor = onGround,
        disabledBorderColor = muted,
        errorBorderColor = onGround,
        focusedLabelColor = onGround,
        unfocusedLabelColor = onGround,
        disabledLabelColor = muted,
        errorLabelColor = onGround,
        cursorColor = onGround,
        errorCursorColor = onGround,
        focusedPlaceholderColor = muted,
        unfocusedPlaceholderColor = muted,
        disabledPlaceholderColor = muted,
        errorPlaceholderColor = muted,
        focusedSupportingTextColor = onGround,
        unfocusedSupportingTextColor = onGround,
        disabledSupportingTextColor = muted,
        errorSupportingTextColor = onGround,
        focusedTrailingIconColor = onGround,
        unfocusedTrailingIconColor = onGround,
        disabledTrailingIconColor = muted,
        errorTrailingIconColor = onGround,
    )
}

/**
 * The container of [AzphaltButton] on [surface]: Ink where it stands out from the surface at 3:1
 * or better, White where Ink would melt into it (navy, an Ink well).
 */
internal fun primaryButtonContainerOn(surface: Color): Color =
    if (contrastRatio(Azphalt.Ink, surface) >= AZPHALT_MIN_LARGE_CONTRAST) Azphalt.Ink else Azphalt.White
