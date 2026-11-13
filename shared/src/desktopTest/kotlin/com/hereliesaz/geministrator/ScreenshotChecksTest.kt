package com.hereliesaz.geministrator

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ScreenshotChecksTest {
    private fun drawn(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(250, 250, 250); fillRect(0, 0, width, height)
            color = Color(20, 20, 20); fillRect(40, 40, width / 2, height / 4)
            color = Color(200, 30, 30); fillOval(60, height / 2, width / 3, height / 4)
            for (i in 0 until 8) { color = Color(30 + i * 20, 90, 200 - i * 20); drawLine(0, i * 9, width, height) }
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun aDrawnShotOfTheRightSizePasses() = ScreenshotChecks.check("drawn", drawn(400, 300), 400, 300)

    @Test
    fun aBlankShotFailsEvenWhenItsFileIsBig() {
        assertFailsWith<AssertionError> { ScreenshotChecks.check("blank", ScreenshotChecks.solid(400, 300, 0xFAFAFA), 400, 300) }
    }

    @Test
    fun aShotOfTheWrongSizeFails() {
        assertFailsWith<AssertionError> { ScreenshotChecks.check("small", drawn(200, 150), 400, 300) }
    }

    @Test
    fun bytesThatAreNotAnImageFail() {
        assertFailsWith<IllegalArgumentException> { ScreenshotChecks.check("junk", ByteArray(20_000) { 1 }, 400, 300) }
    }
}
