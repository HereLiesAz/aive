package com.hereliesaz.geministrator

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
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
 * is an opaque, softened Ink with a label still at 3:1 or better against it. The shared compact pill has a 48dp touch target.
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
    AzphaltPill(
        label = label, seed = "primary-$label", onClick = onClick, modifier = modifier,
        enabled = enabled, fillOverride = if (enabled) container else disabledContainer,
        textOverride = if (enabled) container.onOpposite() else disabledContainer.mutedOnOpposite(),
        dimDisabled = false,
    )
}

/**
 * Secondary Azphalt button: a capsule washed 14% with the surface's own content colour (HG2Gui has no borders, so
 * "secondary" is a quieter fill, not an outline), the label the exact opposite of the surface. Disabled, the label
 * softens but stays at 3:1 or better.
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
    AzphaltPill(
        label = label, seed = "secondary-$label", onClick = onClick, modifier = modifier,
        enabled = enabled, fillOverride = onSurface.copy(alpha = FIELD_WASH),
        textOverride = if (enabled) onSurface else muted, dimDisabled = false,
    )
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
    // HG2Gui has no borders: a field is a 14% wash of the content colour (the "well"), its label floats inside it,
    // and focus is the cursor, so no outline and no indicator line.
    AzphaltWellField(
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
        modifier = modifier.fillMaxWidth(),
    )
}

/** The wash [AzphaltTextField] is filled with (HG2Gui: a 14% well), and its corner (the 20dp note radius, one of the four radii). */
private const val FIELD_WASH = 0.14f
private val AZPHALT_FIELD_CORNER = 20.dp

/**
 * Text field colours for the surface the field sits on: a [FIELD_WASH] container of the content colour (HG2Gui has no
 * borders: the field is a "well"), no outline and no indicator line; text, label, cursor and icons are the exact opposite
 * of the surface; placeholder and disabled states are softened but never drop under 3:1 against it. Error states keep
 * the same colours (the error is carried by the message, not by a red that can vanish on the ground).
 */
@Composable
internal fun azphaltTextFieldColors(): TextFieldColors {
    val onGround = azphaltOnSurface()
    val muted = azphaltMutedOnSurface()
    val wash = onGround.copy(alpha = FIELD_WASH)
    return TextFieldDefaults.colors(
        focusedTextColor = onGround,
        unfocusedTextColor = onGround,
        disabledTextColor = muted,
        errorTextColor = onGround,
        focusedContainerColor = wash,
        unfocusedContainerColor = wash,
        disabledContainerColor = wash,
        errorContainerColor = wash,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
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
 * The one text field: [TextField] in the well colours, the 20dp note radius, its label floating inside it. Every field in
 * the app is this (the call sites used to be outlined `OutlinedTextField`s).
 */
@Composable
internal fun AzphaltWellField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(AZPHALT_FIELD_CORNER),
    colors: TextFieldColors = azphaltTextFieldColors(),
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        shape = shape,
        colors = colors,
    )
}

/** [AzphaltWellField] for a field that keeps its selection and composition ([TextFieldValue]). */
@Composable
internal fun AzphaltWellField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(AZPHALT_FIELD_CORNER),
    colors: TextFieldColors = azphaltTextFieldColors(),
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        shape = shape,
        colors = colors,
    )
}

/**
 * The container of [AzphaltButton] on [surface]: Ink where it stands out from the surface at 3:1
 * or better, White where Ink would melt into it (navy, an Ink well).
 */
internal fun primaryButtonContainerOn(surface: Color): Color =
    if (contrastRatio(Azphalt.Ink, surface) >= AZPHALT_MIN_LARGE_CONTRAST) Azphalt.Ink else Azphalt.White
