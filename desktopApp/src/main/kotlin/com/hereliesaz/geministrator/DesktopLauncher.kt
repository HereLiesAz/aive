package com.hereliesaz.geministrator

import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Window
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Desktop bootstrap that paints the exact logo frame immediately, then swaps in the
 * transparent animated loader as soon as the GIF is decoded.
 */
fun main() {
    val splash = createDesktopSplash()
    val icon = loadImage("haive-icon-color.png")
    val windowWatcher = Timer(40, null)
    windowWatcher.addActionListener {
        val appWindow = Window.getWindows().firstOrNull { window ->
            window !== splash && window.isShowing
        }
        if (appWindow != null) {
            if (appWindow is Frame && icon != null) {
                appWindow.iconImage = icon.image
            }
            splash?.dispose()
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
        splash?.dispose()
    }
}

private fun createDesktopSplash(): JWindow? {
    if (GraphicsEnvironment.isHeadless()) return null
    val firstFrame = loadImage("haive_loader_frame0.png") ?: return null
    val label = JLabel(firstFrame)
    var result: JWindow? = null
    SwingUtilities.invokeAndWait {
        result = JWindow().apply {
            background = java.awt.Color(0x0D, 0x10, 0x26)
            contentPane.background = background
            contentPane.add(label)
            pack()
            setLocationRelativeTo(null)
            isAlwaysOnTop = true
            isVisible = true
        }
    }

    Thread({
        val animated = loadImage("haive_loader.gif") ?: return@Thread
        SwingUtilities.invokeLater {
            if (result?.isDisplayable == true) {
                label.icon = animated
                result?.pack()
                result?.setLocationRelativeTo(null)
            }
        }
    }, "haive-splash-loader").apply {
        isDaemon = true
        start()
    }

    return result
}

private fun loadImage(resourceName: String): ImageIcon? {
    val resource = Thread.currentThread().contextClassLoader.getResource(resourceName) ?: return null
    return ImageIcon(resource)
}
