package haive.build

import java.io.File
import javax.imageio.ImageIO

object BrandLoaderVerifier {
    fun verify(outputDir: File) {
        val frame0File = outputDir.resolve("haive_loader_frame0.png")
        val gifFile = outputDir.resolve("haive_loader.gif")
        require(frame0File.isFile) { "Missing generated loader frame 0: ${frame0File.absolutePath}" }
        require(gifFile.isFile) { "Missing generated loader GIF: ${gifFile.absolutePath}" }

        val expectedFrame0 = requireNotNull(ImageIO.read(frame0File)) {
            "Generated loader frame 0 is unreadable: ${frame0File.absolutePath}"
        }

        ImageIO.createImageInputStream(gifFile).use { input ->
            requireNotNull(input) { "Unable to open generated loader GIF" }
            val readers = ImageIO.getImageReadersByFormatName("gif")
            check(readers.hasNext()) { "No GIF reader available for loader verification" }
            val reader = readers.next()
            try {
                reader.input = input
                val frameCount = reader.getNumImages(true)
                require(frameCount > 1) { "Generated loader must contain multiple animation frames" }

                val first = reader.read(0)
                require(first.width == expectedFrame0.width && first.height == expectedFrame0.height) {
                    "Generated GIF frame 0 dimensions do not match the generated logo frame"
                }

                var transparentPixelFound = false
                outer@ for (frameIndex in 1 until frameCount) {
                    val frame = reader.read(frameIndex)
                    for (y in 0 until frame.height) {
                        for (x in 0 until frame.width) {
                            if ((frame.getRGB(x, y) ushr 24) < 0xFF) {
                                transparentPixelFound = true
                                break@outer
                            }
                        }
                    }
                }
                require(transparentPixelFound) {
                    "Generated loader lost transparency; background removal did not survive GIF encoding"
                }
            } finally {
                reader.dispose()
            }
        }
    }
}
