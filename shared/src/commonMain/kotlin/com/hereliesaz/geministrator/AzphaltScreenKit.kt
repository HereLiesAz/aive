package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/*
 * Public wrappers over the internal Azphalt components, for platform shells (the Android Gemini
 * setup and consent screens) that live outside the shared module but must look like the rest of
 * the app: themed ground, Azphalt type and buttons, scrollable at any font size.
 */

/** A full-screen Azphalt ground that scrolls, so no control is pushed off-screen. */
@Composable
fun AzphaltScreen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    MaterialTheme(colorScheme = GeministratorColors) {
        Surface(modifier = modifier.fillMaxSize(), color = Azphalt.currentGround.page) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AzphaltSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.l),
                content = content,
            )
        }
    }
}

/** Screen title, announced as a heading by screen readers. */
@Composable
fun AzphaltHeading(text: String) {
    Text(
        text,
        style = AzphaltType.section,
        color = Azphalt.currentGround.onPage,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
fun AzphaltParagraph(text: String) {
    Text(text, style = AzphaltType.body, color = Azphalt.currentGround.onPage)
}

@Composable
fun AzphaltPrimaryButton(label: String, onClick: () -> Unit) {
    AzphaltButton(label = label, onClick = onClick, modifier = Modifier.fillMaxWidth())
}

@Composable
fun AzphaltSecondaryButton(label: String, onClick: () -> Unit) {
    AzphaltOutlinedButton(label = label, onClick = onClick, modifier = Modifier.fillMaxWidth())
}
