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
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Primary Azphalt button: an Ink pill with Yellow text (11.6:1), readable on every ground,
 * including Mustard where a Yellow container would vanish. At least 48dp tall.
 */
@Composable
internal fun AzphaltButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = AzphaltMinTouchTarget),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Azphalt.Ink,
            contentColor = Azphalt.Yellow,
            disabledContainerColor = Azphalt.Ink.copy(alpha = 0.45f),
            disabledContentColor = Azphalt.White.copy(alpha = 0.7f),
        ),
        contentPadding = PaddingValues(horizontal = AzphaltSpacing.xl, vertical = AzphaltSpacing.m),
    ) {
        Text(label.uppercase(), style = AzphaltType.capsule)
    }
}

/** Secondary Azphalt button: transparent with an outline in the ground's readable text colour. */
@Composable
internal fun AzphaltOutlinedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val onGround = Azphalt.currentGround.onPage
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = AzphaltMinTouchTarget),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(2.dp, onGround),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = onGround),
        contentPadding = PaddingValues(horizontal = AzphaltSpacing.xl, vertical = AzphaltSpacing.m),
    ) {
        Text(label.uppercase(), style = AzphaltType.capsule)
    }
}

/**
 * Outlined text field in the ground's colours. With [secret] the value is masked and a
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
    val onGround = Azphalt.currentGround.onPage
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
        supportingText = errorMessage?.let { message -> { Text(message) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = onGround,
            unfocusedTextColor = onGround,
            focusedBorderColor = onGround,
            unfocusedBorderColor = onGround.copy(alpha = 0.7f),
            focusedLabelColor = onGround,
            unfocusedLabelColor = onGround,
            cursorColor = onGround,
            focusedPlaceholderColor = onGround.copy(alpha = 0.7f),
            unfocusedPlaceholderColor = onGround.copy(alpha = 0.7f),
            errorTextColor = onGround,
            errorSupportingTextColor = onGround,
            errorLabelColor = onGround,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
