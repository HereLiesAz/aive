package com.hereliesaz.geministrator

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.hereliesaz.geministrator.queen.QueenLevel
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.queen_level_description
import com.hereliesaz.geministrator.resources.queen_level_helpful
import com.hereliesaz.geministrator.resources.queen_level_helpful_line
import com.hereliesaz.geministrator.resources.queen_level_quiet
import com.hereliesaz.geministrator.resources.queen_level_quiet_line
import com.hereliesaz.geministrator.resources.queen_level_title
import com.russhwolf.settings.Settings
import org.jetbrains.compose.resources.stringResource

/** How much the queen tells the keeper, kept on this device. Read fresh each time, so a change applies at the next publish. */
internal class QueenLevelStore(private val settings: Settings) {
    val level: QueenLevel get() = QueenLevel.parse(settings.getStringOrNull(LEVEL_KEY))

    fun set(level: QueenLevel) {
        if (level == QueenLevel.Quiet) settings.remove(LEVEL_KEY) else settings.putString(LEVEL_KEY, level.name)
    }

    companion object {
        const val LEVEL_KEY: String = "aive.queen.level.v1"
    }
}

/** The Keeper's room, This device (Limits when wide): one chooser for how much the queen says in Messages. */
@Composable
internal fun QueenLevelSettings(store: QueenLevelStore = remember { QueenLevelStore(Settings()) }) {
    var level by remember { mutableStateOf(store.level) }
    ProviderSectionLabel(stringResource(Res.string.queen_level_title))
    Text(stringResource(Res.string.queen_level_description), style = AzphaltType.body, color = azphaltOnSurface())
    for ((option, label, line) in listOf(
        Triple(QueenLevel.Quiet, Res.string.queen_level_quiet, Res.string.queen_level_quiet_line),
        Triple(QueenLevel.Helpful, Res.string.queen_level_helpful, Res.string.queen_level_helpful_line),
    )) {
        AzphaltPill(
            stringResource(label),
            "queen-level-${option.name}",
            selected = level == option,
            onClick = {
                level = option
                store.set(option)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(line), style = AzphaltType.body, color = azphaltOnSurface())
    }
}
