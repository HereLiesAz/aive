package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.workflow.FILM_MAX_SHOTS
import com.hereliesaz.geministrator.workflow.FILM_MAX_SHOT_SECONDS
import com.hereliesaz.geministrator.workflow.FILM_MAX_TOTAL_SECONDS
import com.hereliesaz.geministrator.workflow.FILM_MIN_SHOT_SECONDS

/** One shot of the film as ffmpeg gets it: where its frame is, and how long it is on screen. */
internal data class FilmClip(val framePath: String, val seconds: Int)

/**
 * The ffmpeg command line for the short film (docs/Short-Film.md), as a pure function: the same frames and
 * durations always give the same argument list, and nothing here runs anything.
 *
 * The result is an argument list for a process runner, never a shell string: a frame path is one element
 * after `-i`, and the filter graph is built only from constants, clip numbers and integers (no path, no storyboard
 * text), so nothing a model wrote reaches a shell or a filter. Paths are also checked here (absolute, no `..`,
 * no control characters, not starting with `-`) as a second line behind [FileFilmFrameStore.verifiedFrame].
 *
 * Each shot is a looped still (`-loop 1 -t`), scaled to cover the frame, given a gentle zoom ([ZOOM_PERCENT]
 * over the shot), and joined to the next with a [CROSSFADE_MILLIS] crossfade. A clip runs a crossfade longer than
 * its shot (except the last), so the crossfade begins as the next shot starts and the film is exactly as long as the
 * shots add up to. H.264 in yuv420p with even dimensions, no audio track by default.
 */
internal object FfmpegFilmPlan {
    const val FPS: Int = 25
    const val DEFAULT_WIDTH: Int = 1280
    const val DEFAULT_HEIGHT: Int = 720

    /** Length of the crossfade between two shots; half of the shortest shot allowed, so it never swallows a shot. */
    const val CROSSFADE_MILLIS: Int = 500

    /** How far a shot zooms in over its length. */
    const val ZOOM_PERCENT: Int = 8

    /** libx264 quality (lower is better); 20 is visually clean for stills. */
    const val CRF: Int = 20

    /** Written into the file's metadata: the film is made of generated pictures. */
    const val PROVENANCE_COMMENT: String = "AI-generated still frames assembled by The Aive with ffmpeg"

    /** Even dimensions: yuv420p cannot have an odd width or height. */
    fun evenDimension(value: Int): Int = maxOf(2, value - value % 2)

    /** Total length of the film for [clips], in milliseconds. */
    fun totalMillis(clips: List<FilmClip>): Long = clips.sumOf { it.seconds.toLong() * 1000 }

    fun build(
        ffmpeg: String,
        clips: List<FilmClip>,
        outputPath: String,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        silent: Boolean = true,
    ): List<String> {
        require(clips.isNotEmpty()) { "A film needs at least one shot." }
        require(clips.size <= FILM_MAX_SHOTS) { "A film has at most $FILM_MAX_SHOTS shots." }
        clips.forEach {
            require(it.seconds in FILM_MIN_SHOT_SECONDS..FILM_MAX_SHOT_SECONDS) {
                "A shot lasts $FILM_MIN_SHOT_SECONDS to $FILM_MAX_SHOT_SECONDS seconds."
            }
        }
        require(totalMillis(clips) <= FILM_MAX_TOTAL_SECONDS * 1000L) { "A film is at most $FILM_MAX_TOTAL_SECONDS seconds." }
        require(isSafePath(ffmpeg)) { "The ffmpeg path is not usable." }
        clips.forEach { require(isSafePath(it.framePath)) { "A frame path is not usable." } }
        require(isSafePath(outputPath) && outputPath.endsWith(".mp4")) { "The output path must be an absolute .mp4 path." }

        val w = evenDimension(width)
        val h = evenDimension(height)
        val lastIndex = clips.lastIndex
        // A clip is extended by the crossfade (except the last), so a crossfade starts when the next shot does.
        val clipMillis = clips.mapIndexed { index, clip -> clip.seconds * 1000 + if (index < lastIndex) CROSSFADE_MILLIS else 0 }

        val command = mutableListOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-nostats", "-progress", "pipe:1")
        clips.forEachIndexed { index, clip ->
            // -f image2 first: without it ffmpeg does not yet know the input's format when it reads -pattern_type.
            command += listOf(
                "-f", "image2", "-pattern_type", "none",
                "-loop", "1", "-framerate", FPS.toString(), "-t", seconds(clipMillis[index]),
                "-i", clip.framePath,
            )
        }
        command += listOf("-filter_complex", filterGraph(clips, clipMillis, w, h))
        command += listOf(
            "-map", "[vout]",
            "-c:v", "libx264", "-preset", "medium", "-crf", CRF.toString(), "-pix_fmt", "yuv420p",
            "-r", FPS.toString(), "-movflags", "+faststart",
        )
        if (silent) command += "-an"
        command += listOf("-metadata", "comment=$PROVENANCE_COMMENT", "-y", outputPath)
        return command
    }

    /** The `-filter_complex` text: constants, clip numbers and integers only. */
    internal fun filterGraph(clips: List<FilmClip>, clipMillis: List<Int>, width: Int, height: Int): String {
        val parts = mutableListOf<String>()
        clips.indices.forEach { index ->
            val frames = (clipMillis[index].toLong() * FPS / 1000).coerceAtLeast(1)
            parts += "[$index:v]scale=${width * 2}:${height * 2}:force_original_aspect_ratio=increase," +
                "crop=${width * 2}:${height * 2},setsar=1," +
                "zoompan=z='1+$ZOOM_PERCENT/100*on/$frames':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=${width}x$height:fps=$FPS," +
                "setpts=PTS-STARTPTS,format=yuv420p" + if (clips.size == 1) "[vout]" else "[v$index]"
        }
        if (clips.size > 1) {
            var startMillis = 0
            var previous = "v0"
            for (index in 1 until clips.size) {
                startMillis += clips[index - 1].seconds * 1000
                val out = if (index == clips.lastIndex) "vout" else "x$index"
                parts += "[$previous][v$index]xfade=transition=fade:duration=${seconds(CROSSFADE_MILLIS)}:offset=${seconds(startMillis)}[$out]"
                previous = out
            }
        }
        return parts.joinToString(";")
    }

    /** Milliseconds as ffmpeg seconds with a point, whatever the computer's locale. */
    fun seconds(millis: Int): String = "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')}"

    /** Absolute (Unix or Windows), no `..` segment, no control character, not an option. */
    internal fun isSafePath(path: String): Boolean {
        if (path.isBlank() || path.any { it.code < 0x20 || it.code == 0x7f }) return false
        if (path.startsWith("-")) return false
        val absolute = path.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path) || path.startsWith("\\\\")
        if (!absolute) return false
        return path.split('/', '\\').none { it == ".." }
    }
}
