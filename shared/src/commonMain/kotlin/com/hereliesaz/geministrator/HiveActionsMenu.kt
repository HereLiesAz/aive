package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.hive_delete
import com.hereliesaz.geministrator.resources.hive_menu_close
import com.hereliesaz.geministrator.resources.hive_menu_open
import org.jetbrains.compose.resources.stringResource

/**
 * What can be done to a hive, from a long press on it in the district (docs/Keepers-Room.md, Hive options): it is
 * named, then Open, Delete hive (when [onDelete] is given; it asks first, in [HiveDeleteRequest]) and Close.
 * Choosing one closes the menu; nothing here changes anything by itself.
 */
@Composable
internal fun HiveActionsMenu(
    name: String,
    /** Null on a hive that is already open: there is nothing to open. */
    onOpen: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(AzphaltShapes.dialogCorner), color = Azphalt.Ink) {
            AzphaltThemedSurface(Azphalt.Ink) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(name, style = AzphaltType.section, color = azphaltOnSurface(), maxLines = 3)
                    if (onOpen != null) {
                        AzphaltPill(
                            label = stringResource(Res.string.hive_menu_open),
                            seed = "hive-menu-open",
                            tone = PillTone.Primary,
                            onClick = { onDismiss(); onOpen() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (onDelete != null) {
                        AzphaltPill(
                            label = stringResource(Res.string.hive_delete),
                            seed = "hive-menu-delete",
                            tone = PillTone.Danger,
                            onClick = { onDismiss(); onDelete() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AzphaltPill(
                        label = stringResource(Res.string.hive_menu_close),
                        seed = "hive-menu-close",
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
