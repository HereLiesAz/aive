package haive.build

import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

object BrandAssets {
    private val icoSizes = listOf(16, 24, 32, 48, 64, 128, 256)
    private val icnsSizes = listOf(
        "icp4" to 16,
        "icp5" to 32,
        "icp6" to 64,
        "ic07" to 128,
        "ic08" to 256,
        "ic09" to 512,
        "ic10" to 1024,
    )

    fun generateDesktop(source: File, outputDir: File) {
        val image = readSource(source)
        outputDir.mkdirs()
        writePng(resize(image, 512), outputDir.resolve("haive-icon-color.png"))
        writeIco(image, outputDir.resolve("haive-icon.ico"))
        writeIcns(image, outputDir.resolve("haive-icon.icns"))
    }

    fun generateWeb(source: File, outputDir: File) {
        val image = readSource(source)
        outputDir.mkdirs()
        listOf(64, 192, 512).forEach { size ->
            writePng(resize(image, size), outputDir.resolve("haive-icon-$size.png"))
        }
    }

    private fun readSource(source: File): BufferedImage {
        require(source.isFile) { "Brand source does not exist: ${source.absolutePath}" }
        return requireNotNull(ImageIO.read(source)) { "Brand source is not a readable image: ${source.absolutePath}" }
    }

    private fun resize(source: BufferedImage, size: Int): BufferedImage {
        val output = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val scale = minOf(size.toDouble() / source.width, size.toDouble() / source.height)
            val width = (source.width * scale).roundToInt().coerceAtLeast(1)
            val height = (source.height * scale).roundToInt().coerceAtLeast(1)
            val x = (size - width) / 2
            val y = (size - height) / 2
            graphics.drawImage(source, x, y, width, height, null)
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun writePng(image: BufferedImage, output: File) {
        output.parentFile?.mkdirs()
        check(ImageIO.write(image, "png", output)) { "No PNG writer available" }
    }

    private fun pngBytes(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { bytes ->
        check(ImageIO.write(image, "png", bytes)) { "No PNG writer available" }
        bytes.toByteArray()
    }

    private fun writeIco(source: BufferedImage, output: File) {
        val images = icoSizes.map { size -> size to pngBytes(resize(source, size)) }
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { stream ->
            stream.writeLe16(0)
            stream.writeLe16(1)
            stream.writeLe16(images.size)

            var offset = 6 + images.size * 16
            images.forEach { (size, bytes) ->
                stream.write(if (size >= 256) 0 else size)
                stream.write(if (size >= 256) 0 else size)
                stream.write(0)
                stream.write(0)
                stream.writeLe16(1)
                stream.writeLe16(32)
                stream.writeLe32(bytes.size)
                stream.writeLe32(offset)
                offset += bytes.size
            }
            images.forEach { (_, bytes) -> stream.write(bytes) }
        }
    }

    private fun writeIcns(source: BufferedImage, output: File) {
        val entries = icnsSizes.map { (type, size) -> type to pngBytes(resize(source, size)) }
        val totalLength = 8 + entries.sumOf { (_, bytes) -> 8 + bytes.size }
        output.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(output)).use { stream ->
            stream.writeBytes("icns")
            stream.writeInt(totalLength)
            entries.forEach { (type, bytes) ->
                stream.writeBytes(type)
                stream.writeInt(8 + bytes.size)
                stream.write(bytes)
            }
        }
    }

    private fun OutputStream.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun OutputStream.writeLe32(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}
