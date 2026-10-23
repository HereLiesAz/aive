package com.hereliesaz.geministrator

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The one alert dialog style for The Aive: Ink container, text in the exact opposite of Ink, a
 * Yellow confirm button with the opposite of Yellow on it, as the inspector sheet. Platform shells
 * (the Android update and crash-report prompts) render outside [App]'s theme, so they use this rather than a bare Material dialog with stock colours.
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
            titleContentColor = Azphalt.Ink.onOpposite(),
            textContentColor = Azphalt.Ink.onOpposite(),
            shape = RoundedCornerShape(AzphaltShapes.dialogCorner),
            title = { Text(title, fontWeight = FontWeight.Black) },
            text = { Text(text) },
            confirmButton = {
                // A Yellow pill with the exact opposite of Yellow as its label: the confirm stands
                // out from the Ink dialog and is never Yellow text on a Yellow-ish surface.
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Azphalt.Yellow,
                        contentColor = Azphalt.Yellow.onOpposite(),
                    ),
                ) { Text(confirmLabel) }
            },
            dismissButton = dismissLabel?.let { label ->
                {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = Azphalt.Ink.onOpposite()),
                    ) { Text(label) }
                }
            },
        )
    }
}
