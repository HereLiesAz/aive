package com.hereliesaz.geministrator

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The one alert dialog style for The Aive: Ink container, white text, yellow confirm, as the
 * inspector sheet. Platform shells (the Android update and crash-report prompts) render outside
 * [App]'s theme, so they use this rather than a bare Material dialog with stock colours.
 */
@Composable
fun AzphaltAlertDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    dismissLabel: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
) {
    MaterialTheme(colorScheme = GeministratorColors) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            containerColor = Azphalt.Ink,
            titleContentColor = Azphalt.White,
            textContentColor = Azphalt.White.copy(alpha = 0.86f),
            shape = RoundedCornerShape(DIALOG_CORNER.dp),
            title = { Text(title, fontWeight = FontWeight.Black) },
            text = { Text(text) },
            confirmButton = {
                TextButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColors(contentColor = Azphalt.Yellow),
                ) { Text(confirmLabel) }
            },
            dismissButton = dismissLabel?.let { label ->
                {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = Azphalt.White),
                    ) { Text(label) }
                }
            },
        )
    }
}

private const val DIALOG_CORNER = 28
