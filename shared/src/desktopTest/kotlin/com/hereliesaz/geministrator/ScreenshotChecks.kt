package com.hereliesaz.geministrator

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * What a screenshot test must prove before it saves a PNG. A byte-size floor alone passes for a
 * page of one wrong colour or an error screen; this decodes the PNG and checks the pixels.
 */
object ScreenshotChecks {
    /** Smallest share of pixels that must differ from the page background for the shot to count as drawn. */
    const val MIN_CONTENT_SHARE = 0.002

    /** Fewest distinct colours the shot must hold (text and shapes anti-alias into several). */
    const val MIN_DISTINCT_COLOURS = 6

    class Verdict(val width: Int, val height: Int, val contentShare: Double, val distinctColours: Int)

    fun measure(bytes: ByteArray): Verdict {
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "the screenshot bytes are not a decodable image" }
        val background = image.getRGB(0, 0) // the 12 dp padding keeps the corner on the page ground
        var differing = 0L
        val colours = HashSet<Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                if (argb != background) differing++
                if (colours.size < 4096) colours += argb
            }
        }
        return Verdict(image.width, image.height, differing.toDouble() / (image.width.toLong() * image.height), colours.size)
    }

    /** [width] and [height] are the scene's pixel size (what `ImageComposeScene` was given). Throws (an [AssertionError]) naming the shot when it is the wrong size, or is blank or a single wash of colour. */
    fun check(name: String, bytes: ByteArray, width: Int, height: Int) {
        val verdict = measure(bytes)
        if (verdict.width != width || verdict.height != height) {
            throw AssertionError("$name is ${verdict.width}x${verdict.height}, expected ${width}x$height")
        }
        if (verdict.contentShare < MIN_CONTENT_SHARE) {
            throw AssertionError("$name looks blank: ${"%.4f".format(verdict.contentShare)} of its pixels differ from the page background")
        }
        if (verdict.distinctColours < MIN_DISTINCT_COLOURS) {
            throw AssertionError("$name is flat: only ${verdict.distinctColours} distinct colours")
        }
    }

    fun solid(width: Int, height: Int, rgb: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, rgb)
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
