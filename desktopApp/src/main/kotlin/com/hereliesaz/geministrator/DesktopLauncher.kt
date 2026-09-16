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
 * Desktop bootstrap that keeps the animated brand splash visible only while Compose is starting.
 * The application runtime itself remains in MainKt.
 */
fun main() {
    val splash = createDesktopSplash()
    val icon = loadImage("haive_logo.png")
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
    val splashIcon = loadImage("haive_splash.gif") ?: return null
    var result: JWindow? = null
    SwingUtilities.invokeAndWait {
        result = JWindow().apply {
            contentPane.add(JLabel(splashIcon))
            pack()
            setLocationRelativeTo(null)
            isAlwaysOnTop = true
            isVisible = true
        }
    }
    return result
}

private fun loadImage(resourceName: String): ImageIcon? {
    val resource = Thread.currentThread().contextClassLoader.getResource(resourceName) ?: return null
    return ImageIcon(resource)
}
