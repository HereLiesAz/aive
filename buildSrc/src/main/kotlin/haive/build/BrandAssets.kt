package haive.build

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.roundToInt
import org.w3c.dom.Node

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

    fun generateLoader(
        logoSource: File,
        animationSource: File,
        outputDir: File,
        maxDimension: Int = 320,
        maxFrames: Int = 48,
    ) {
        require(maxDimension > 0)
        require(maxFrames > 1)
        val logo = readSource(logoSource)
        val animation = readGif(animationSource)
        val largestDimension = maxOf(animation.width, animation.height)
        val scale = minOf(1.0, maxDimension.toDouble() / largestDimension.toDouble())
        val targetWidth = (animation.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (animation.height * scale).roundToInt().coerceAtLeast(1)

        val frameStep = ((animation.frames.size + maxFrames - 1) / maxFrames).coerceAtLeast(1)
        val sampledFrames = animation.frames.chunked(frameStep)
        val outputFrames = sampledFrames.mapIndexed { index, chunk ->
            val sourceFrame = chunk.first()
            val image = if (index == 0) {
                fitIntoCanvas(logo, targetWidth, targetHeight)
            } else {
                resize(sourceFrame.image, targetWidth, targetHeight)
            }
            GifFrame(
                image = image,
                delayHundredths = chunk.sumOf(GifFrame::delayHundredths).coerceAtLeast(2),
            )
        }

        outputDir.mkdirs()
        writePng(outputFrames.first().image, outputDir.resolve("haive_loader_frame0.png"))
        writeGif(outputFrames, outputDir.resolve("haive_loader.gif"))
    }

    private fun readSource(source: File): BufferedImage {
        require(source.isFile) { "Brand source does not exist: ${source.absolutePath}" }
        return requireNotNull(ImageIO.read(source)) { "Brand source is not a readable image: ${source.absolutePath}" }
    }

    private fun readGif(source: File): GifAnimation {
        require(source.isFile) { "GIF source does not exist: ${source.absolutePath}" }
        ImageIO.createImageInputStream(source).use { input ->
            requireNotNull(input) { "Unable to open GIF source: ${source.absolutePath}" }
            val readers = ImageIO.getImageReadersByFormatName("gif")
            check(readers.hasNext()) { "No GIF reader available" }
            val reader = readers.next()
            try {
                reader.input = input
                val frameCount = reader.getNumImages(true)
                require(frameCount > 0) { "GIF has no frames: ${source.absolutePath}" }

                val streamRoot = reader.streamMetadata
                    ?.getAsTree("javax_imageio_gif_stream_1.0")
                val logicalScreen = streamRoot?.findNode("LogicalScreenDescriptor")
                val fallbackFirst = reader.read(0)
                val width = logicalScreen?.attributeInt("logicalScreenWidth") ?: fallbackFirst.width
                val height = logicalScreen?.attributeInt("logicalScreenHeight") ?: fallbackFirst.height
                val backgroundColor = dominantBorderColor(fallbackFirst)

                var canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                fill(canvas, backgroundColor)
                var previousDisposal = "none"
                var previousRect: Rectangle? = null
                var previousSnapshot: BufferedImage? = null
                val frames = ArrayList<GifFrame>(frameCount)

                for (index in 0 until frameCount) {
                    when (previousDisposal) {
                        "restoreToBackgroundColor" -> previousRect?.let { fill(canvas, backgroundColor, it) }
                        "restoreToPrevious" -> previousSnapshot?.let { canvas = copyImage(it) }
                    }

                    val raw = if (index == 0) fallbackFirst else reader.read(index)
                    val metadata = readGifFrameMetadata(reader.getImageMetadata(index))
                    val snapshot = if (metadata.disposalMethod == "restoreToPrevious") copyImage(canvas) else null
                    val graphics = canvas.createGraphics()
                    try {
                        graphics.composite = AlphaComposite.SrcOver
                        graphics.drawImage(raw, metadata.left, metadata.top, null)
                    } finally {
                        graphics.dispose()
                    }

                    val composed = copyImage(canvas)
                    chromaKey(composed, backgroundColor, tolerance = 22)
                    frames += GifFrame(
                        image = composed,
                        delayHundredths = metadata.delayHundredths.coerceAtLeast(2),
                    )

                    previousDisposal = metadata.disposalMethod
                    previousRect = Rectangle(metadata.left, metadata.top, raw.width, raw.height)
                    previousSnapshot = snapshot
                }

                return GifAnimation(width = width, height = height, frames = frames)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun readGifFrameMetadata(metadata: javax.imageio.metadata.IIOMetadata): GifFrameMetadata {
        val root = metadata.getAsTree("javax_imageio_gif_image_1.0")
        val descriptor = root.findNode("ImageDescriptor")
        val control = root.findNode("GraphicControlExtension")
        return GifFrameMetadata(
            left = descriptor?.attributeInt("imageLeftPosition") ?: 0,
            top = descriptor?.attributeInt("imageTopPosition") ?: 0,
            delayHundredths = control?.attributeInt("delayTime") ?: 4,
            disposalMethod = control?.attributes?.getNamedItem("disposalMethod")?.nodeValue ?: "none",
        )
    }

    private fun dominantBorderColor(image: BufferedImage): Int {
        val counts = linkedMapOf<Int, Int>()
        val horizontalStep = (image.width / 96).coerceAtLeast(1)
        val verticalStep = (image.height / 96).coerceAtLeast(1)

        fun count(x: Int, y: Int) {
            val rgb = image.getRGB(x, y)
            if ((rgb ushr 24) == 0) return
            val opaque = 0xFF000000.toInt() or (rgb and 0x00FFFFFF)
            counts[opaque] = (counts[opaque] ?: 0) + 1
        }

        for (x in 0 until image.width step horizontalStep) {
            count(x, 0)
            count(x, image.height - 1)
        }
        for (y in 0 until image.height step verticalStep) {
            count(0, y)
            count(image.width - 1, y)
        }

        return counts.maxByOrNull { it.value }?.key ?: Color(0x0D, 0x10, 0x26).rgb
    }

    private fun chromaKey(image: BufferedImage, backgroundColor: Int, tolerance: Int) {
        val br = backgroundColor shr 16 and 0xFF
        val bg = backgroundColor shr 8 and 0xFF
        val bb = backgroundColor and 0xFF
        val threshold = tolerance * tolerance
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                val alpha = argb ushr 24 and 0xFF
                if (alpha == 0) continue
                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                val dr = r - br
                val dg = g - bg
                val db = b - bb
                if (dr * dr + dg * dg + db * db <= threshold) {
                    image.setRGB(x, y, argb and 0x00FFFFFF)
                }
            }
        }
    }

    private fun fitIntoCanvas(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val scale = minOf(width.toDouble() / source.width, height.toDouble() / source.height)
            val drawWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
            val drawHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
            val x = (width - drawWidth) / 2
            val y = (height - drawHeight) / 2
            graphics.drawImage(source, x, y, drawWidth, drawHeight, null)
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun resize(source: BufferedImage, size: Int): BufferedImage = fitIntoCanvas(source, size, size)

    private fun resize(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun copyImage(source: BufferedImage): BufferedImage {
        val output = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.drawImage(source, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun fill(image: BufferedImage, color: Int, rect: Rectangle = Rectangle(0, 0, image.width, image.height)) {
        val graphics = image.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.color = Color(color, true)
            graphics.fillRect(rect.x, rect.y, rect.width, rect.height)
        } finally {
            graphics.dispose()
        }
    }

    private fun writePng(image: BufferedImage, output: File) {
        output.parentFile?.mkdirs()
        check(ImageIO.write(image, "png", output)) { "No PNG writer available" }
    }

    private fun writeGif(frames: List<GifFrame>, output: File) {
        require(frames.isNotEmpty())
        val writers = ImageIO.getImageWritersByFormatName("gif")
        check(writers.hasNext()) { "No GIF writer available" }
        val writer = writers.next()
        val params = writer.defaultWriteParam
        output.parentFile?.mkdirs()
        ImageIO.createImageOutputStream(output).use { stream ->
            requireNotNull(stream) { "Unable to create GIF output stream: ${output.absolutePath}" }
            writer.output = stream
            writer.prepareWriteSequence(null)
            frames.forEachIndexed { index, frame ->
                val type = ImageTypeSpecifier.createFromRenderedImage(frame.image)
                val metadata = writer.getDefaultImageMetadata(type, params)
                val format = metadata.nativeMetadataFormatName
                val root = metadata.getAsTree(format) as IIOMetadataNode
                val control = root.getOrCreateNode("GraphicControlExtension")
                control.setAttribute("disposalMethod", "restoreToBackgroundColor")
                control.setAttribute("userInputFlag", "FALSE")
                control.setAttribute("delayTime", frame.delayHundredths.toString())

                if (index == 0) {
                    val extensions = root.getOrCreateNode("ApplicationExtensions")
                    val loop = IIOMetadataNode("ApplicationExtension").apply {
                        setAttribute("applicationID", "NETSCAPE")
                        setAttribute("authenticationCode", "2.0")
                        userObject = byteArrayOf(1, 0, 0)
                    }
                    extensions.appendChild(loop)
                }

                metadata.setFromTree(format, root)
                writer.writeToSequence(IIOImage(frame.image, null, metadata), params)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
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

    private fun Node.findNode(name: String): Node? {
        if (nodeName == name) return this
        val children = childNodes
        for (index in 0 until children.length) {
            children.item(index).findNode(name)?.let { return it }
        }
        return null
    }

    private fun Node.attributeInt(name: String): Int? = attributes?.getNamedItem(name)?.nodeValue?.toIntOrNull()

    private fun IIOMetadataNode.getOrCreateNode(name: String): IIOMetadataNode {
        for (index in 0 until length) {
            val child = item(index)
            if (child.nodeName == name) return child as IIOMetadataNode
        }
        return IIOMetadataNode(name).also(::appendChild)
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

    private data class GifAnimation(
        val width: Int,
        val height: Int,
        val frames: List<GifFrame>,
    )

    private data class GifFrame(
        val image: BufferedImage,
        val delayHundredths: Int,
    )

    private data class GifFrameMetadata(
        val left: Int,
        val top: Int,
        val delayHundredths: Int,
        val disposalMethod: String,
    )
}
