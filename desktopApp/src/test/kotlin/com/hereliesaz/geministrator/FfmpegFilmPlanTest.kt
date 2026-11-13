package com.hereliesaz.geministrator

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class FfmpegFilmPlanTest {
    private val plain = listOf(FilmClip("/data/film/f1/s1.png", 4), FilmClip("/data/film/f1/s2.png", 6), FilmClip("/data/film/f1/s3.png", 3))

    private fun build(clips: List<FilmClip> = plain, width: Int = 1280, height: Int = 720, silent: Boolean = true) =
        FfmpegFilmPlan.build("/usr/bin/ffmpeg", clips, "/out/film.mp4", width, height, silent)

    /**
     * The real-binary test is SKIPPED (never green) on a machine without ffmpeg, so a missing
     * tool cannot pass as coverage. CI installs ffmpeg, so there a missing binary fails the run.
     */
    private fun requireOrSkip(name: String): File {
        val found = CliLocator.locateBinary(listOf(name))
        if (found == null) {
            check(System.getenv("CI") != "true") { "$name is not installed on this CI runner; the workflow must install ffmpeg" }
            assumeTrue("$name is not installed; skipping the real-binary check", false)
        }
        return found!!
    }

    private fun values(command: List<String>, flag: String) =
        command.indices.filter { command[it] == flag }.map { command[it + 1] }

    @Test
    fun eachShotIsALoopedStillWithItsOwnDurationAndFramePathAsOneArgument() {
        val command = build()
        assertEquals("/usr/bin/ffmpeg", command.first(), "the program, then arguments: no shell in between")
        assertEquals(listOf("1", "1", "1"), values(command, "-loop"))
        assertEquals(plain.map { it.framePath }, values(command, "-i"))
        assertEquals(listOf("4.500", "6.500", "3.000"), values(command, "-t"), "each shot runs a crossfade longer than itself, the last one does not")
        assertEquals("/out/film.mp4", command.last())
        assertTrue("-nostdin" in command && "-y" in command)
        assertEquals(listOf("none", "none", "none"), values(command, "-pattern_type"), "a frame path is a file name, never a pattern")
        assertEquals(listOf("image2", "image2", "image2"), values(command, "-f"), "the input format is stated, so -pattern_type is understood")
    }

    @Test
    fun noShellMetacharacterInAFramePathReachesAShellOrTheFilterGraph() {
        val nasty = "/data/film/f1/it's \"q\" \$(rm -rf ~); echo `id` && cat /etc/passwd | sh > x <y.png"
        val withNasty = build(plain.mapIndexed { i, c -> if (i == 1) c.copy(framePath = nasty) else c })
        assertEquals(1, withNasty.count { it == nasty }, "the path is one argument, untouched")
        assertEquals("-i", withNasty[withNasty.indexOf(nasty) - 1])

        val graphWithNasty = values(withNasty, "-filter_complex").single()
        assertEquals(values(build(), "-filter_complex").single(), graphWithNasty, "no path is ever part of the graph")
        assertTrue(Regex("^[A-Za-z0-9_\\-.:=,;\\[\\]'/*+()]+$").matches(graphWithNasty), "the graph is only constants and numbers: $graphWithNasty")
        assertTrue(withNasty.none { it == "sh" || it == "bash" || it == "cmd" || it == "-c" })
    }

    @Test
    fun aPathThatCouldBeReadAsAnOptionOrEscapeIsRefused() {
        listOf("relative/s1.png", "s1.png", "-frame.png", "", "   ", "/data/../etc/passwd", "/data/a/../b.png", "/data/a\nb.png", "/data/a\u0000b.png", "C:rel\\x.png").forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) { build(listOf(FilmClip(path, 3))) }
        }
        assertFailsWith<IllegalArgumentException> { FfmpegFilmPlan.build("/usr/bin/ffmpeg", plain, "/out/film.mov") }
        assertFailsWith<IllegalArgumentException> { FfmpegFilmPlan.build("/usr/bin/ffmpeg", plain, "../film.mp4") }
        assertFailsWith<IllegalArgumentException> { FfmpegFilmPlan.build("ffmpeg", plain, "/out/film.mp4") }
        assertTrue(FfmpegFilmPlan.isSafePath("C:\\Users\\a b\\film\\s1.png") && FfmpegFilmPlan.isSafePath("\\\\server\\share\\s1.png"))
        assertFalse(FfmpegFilmPlan.isSafePath("/data/..\\x"))
    }

    @Test
    fun aFilmOutsideTheLimitsIsRefused() {
        assertFailsWith<IllegalArgumentException> { build(emptyList()) }
        assertFailsWith<IllegalArgumentException> { build(listOf(FilmClip("/a.png", 0))) }
        assertFailsWith<IllegalArgumentException> { build(listOf(FilmClip("/a.png", 16))) }
        assertFailsWith<IllegalArgumentException> { build(List(61) { FilmClip("/a$it.png", 1) }) }
        assertFailsWith<IllegalArgumentException> { build(List(21) { FilmClip("/a$it.png", 15) }) }
        assertEquals(300_000L, FfmpegFilmPlan.totalMillis(List(20) { FilmClip("/a$it.png", 15) }))
        build(List(20) { FilmClip("/a$it.png", 15) })
    }

    @Test
    fun dimensionsAreEvenWhateverIsAsked() {
        val odd = build(width = 1281, height = 721)
        val graph = values(odd, "-filter_complex").single()
        assertTrue("scale=2560:1440" in graph && "s=1280x720" in graph, graph)
        assertEquals(2, FfmpegFilmPlan.evenDimension(1))
        assertEquals(2, FfmpegFilmPlan.evenDimension(-5))
        assertEquals(1920, FfmpegFilmPlan.evenDimension(1921))
        assertEquals(1080, FfmpegFilmPlan.evenDimension(1080))
        assertTrue("s=1280x720" in values(build(), "-filter_complex").single())
    }

    @Test
    fun theCrossfadesAreTimedSoTheFilmRunsExactlyAsLongAsTheShotsAddUp() {
        val command = build()
        val clipLengths = values(command, "-t").map { it.toDouble() }
        val graph = values(command, "-filter_complex").single()
        val offsets = Regex("offset=([0-9.]+)").findAll(graph).map { it.groupValues[1].toDouble() }.toList()
        val total = plain.sumOf { it.seconds }.toDouble()
        assertEquals(listOf(4.0, 10.0), offsets, "a crossfade starts when the next shot's time begins")
        assertEquals(total, offsets.last() + clipLengths.last(), 1e-9, "the film's length is the sum of the shot durations")
        assertEquals(total, clipLengths.sum() - (plain.size - 1) * 0.5, 1e-9)
        assertEquals(2, Regex("xfade=transition=fade:duration=0.500").findAll(graph).count())
        assertTrue(graph.endsWith("[x1][v2]xfade=transition=fade:duration=0.500:offset=10.000[vout]"), graph)
    }

    @Test
    fun theVideoIsH264YuvAndSilentUnlessAskedOtherwiseAndSaysItIsGenerated() {
        val command = build()
        assertEquals("libx264", values(command, "-c:v").single())
        assertEquals("yuv420p", values(command, "-pix_fmt").single())
        assertEquals("[vout]", values(command, "-map").single())
        assertTrue("-an" in command)
        assertFalse("-an" in build(silent = false))
        assertEquals("comment=${FfmpegFilmPlan.PROVENANCE_COMMENT}", values(command, "-metadata").single())
        assertTrue("zoompan=z='1+8/100*on/112'" in values(command, "-filter_complex").single(), "4.5 s at 25 fps is 112 frames")
    }

    @Test
    fun oneShotNeedsNoCrossfade() {
        val command = build(listOf(FilmClip("/data/a.png", 5)))
        val graph = values(command, "-filter_complex").single()
        assertFalse("xfade" in graph)
        assertTrue(graph.endsWith("format=yuv420p[vout]"), graph)
        assertEquals(listOf("5.000"), values(command, "-t"))
    }

    @Test
    fun secondsAreWrittenWithAPointAndThreeDecimals() {
        assertEquals("4.500", FfmpegFilmPlan.seconds(4500))
        assertEquals("0.500", FfmpegFilmPlan.seconds(500))
        assertEquals("15.000", FfmpegFilmPlan.seconds(15000))
        assertEquals("0.007", FfmpegFilmPlan.seconds(7))
    }

    /** Runs the real ffmpeg when this computer has it; the flags are written from its documentation and this is how they are held to it. */
    @Test
    fun theCommandMakesAFilmOfTheRightLengthAndFormatWithARealFfmpeg(): Unit = runBlocking {
        val ffmpeg = requireOrSkip("ffmpeg")
        val folder = Files.createTempDirectory("aive-film-real-").toFile()
        try {
            val colours = listOf(Color.RED, Color.GREEN, Color.BLUE)
            val clips = colours.mapIndexed { index, colour ->
                val file = File(folder, "s${index + 1}.png")
                val image = BufferedImage(641, 361, BufferedImage.TYPE_INT_RGB) // odd on purpose: the film is scaled to even
                image.createGraphics().apply { color = colour; fillRect(0, 0, 641, 361); dispose() }
                ImageIO.write(image, "png", file)
                FilmClip(file.absolutePath, listOf(2, 3, 2)[index])
            }
            val output = File(folder, "film.mp4")
            val command = FfmpegFilmPlan.build(ffmpeg.absolutePath, clips, output.absolutePath, width = 640, height = 360)
            val lines = mutableListOf<String>()
            val result = ProcessCliRunner.run(command, folder, FilmAssembleExecutorIntegration.ffmpegEnvironment(System.getenv()), null, 120_000) { lines += it }
            assertEquals(0, result.exitCode, result.stderrTail)
            assertTrue(output.length() > 0)
            assertTrue(lines.any { FilmAssembleExecutorIntegration.progressMillis(it) != null }, "the progress lines are readable")

            val ffprobe = requireOrSkip("ffprobe")
            val probe = mutableListOf<String>()
            ProcessCliRunner.run(
                listOf(ffprobe.absolutePath, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name,pix_fmt,width,height:format=duration", "-of", "default=nw=1", output.absolutePath),
                folder, FilmAssembleExecutorIntegration.ffmpegEnvironment(System.getenv()), null, 60_000,
            ) { probe += it }
            val facts = probe.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals("h264", facts["codec_name"])
            assertEquals("yuv420p", facts["pix_fmt"])
            assertEquals("640", facts["width"])
            assertEquals("360", facts["height"])
            assertTrue(abs(facts.getValue("duration").toDouble() - 7.0) < 0.2, "7 s of shots, not 7 s plus the crossfades: ${facts["duration"]}")
            val audio = mutableListOf<String>()
            ProcessCliRunner.run(
                listOf(ffprobe.absolutePath, "-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_type", "-of", "default=nw=1", output.absolutePath),
                folder, FilmAssembleExecutorIntegration.ffmpegEnvironment(System.getenv()), null, 60_000,
            ) { audio += it }
            assertEquals(emptyList(), audio.filter { it.isNotBlank() }, "no audio track")
        } finally {
            folder.deleteRecursively()
        }
    }
}
