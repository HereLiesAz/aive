package com.hereliesaz.conveyance.demo

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Gallery",
        state = rememberWindowState(size = DpSize(460.dp, 720.dp)),
    ) {
        DemoApp()
    }
}
