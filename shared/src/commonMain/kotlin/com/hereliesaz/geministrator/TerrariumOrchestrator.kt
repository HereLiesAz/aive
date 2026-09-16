package com.hereliesaz.geministrator

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.hereliesaz.geministrator.generated.resources.Res
import com.hereliesaz.geministrator.generated.resources.haive_orchestrator
import org.jetbrains.compose.resources.painterResource

/** The queen/orchestrator is always the canonical Haive logo character, never a procedural stand-in. */
@Composable
internal fun TerrariumOrchestratorCharacter(
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(Res.drawable.haive_orchestrator),
        contentDescription = "Haive orchestrator",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
