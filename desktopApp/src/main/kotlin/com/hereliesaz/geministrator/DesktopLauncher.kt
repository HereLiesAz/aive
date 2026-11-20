package com.hereliesaz.geministrator

import java.awt.Frame
import java.awt.Window
import javax.swing.ImageIcon
import javax.swing.Timer

/**
 * Desktop bootstrap that gives the app's first window its icon. Started as
 * `entire-agent <subcommand>`, it is Entire's `entire-agent-aive` plugin instead, with no window.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == ENTIRE_AGENT_ARGUMENT) {
        kotlin.system.exitProcess(runEntireAgentPlugin(args.drop(1)))
    }
    val icon = loadImage("aive-icon-color.png")
    val windowWatcher = Timer(40, null)
    windowWatcher.addActionListener {
        val appWindow = Window.getWindows().firstOrNull { window ->
            window.isShowing
        }
        if (appWindow != null) {
            if (appWindow is Frame && icon != null) {
                appWindow.iconImage = icon.image
            }
            windowWatcher.stop()
        }
    }
    windowWatcher.start()

    try {
        Class.forName("com.hereliesaz.geministrator.MainKt")
            .getDeclaredMethod("main")
            .invoke(null)
    } finally {
        windowWatcher.stop()
    }
}

private fun loadImage(resourceName: String): ImageIcon? {
    val resource = Thread.currentThread().contextClassLoader.getResource(resourceName) ?: return null
    return ImageIcon(resource)
}
