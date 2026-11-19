package com.hereliesaz.geministrator.providers.cli

/** How a run ended, as the script reported it. [Malformed] is the parser's own verdict on output it could not read. */
enum class CliRunStatus { Ok, Failed, Refused, Malformed }

/** What one run printed, read from its fenced result block. Every text is bounded; none is trusted. */
data class CliRunResult(
    val status: CliRunStatus,
    val exitCode: Int,
    val reason: String,
    val branch: String,
    val commit: String,
    /** The CLI's final message, read from its stdout with the reader for its output format. */
    val answer: String,
    val answerIsError: Boolean,
    val stderrTail: String,
    /** `git diff` of the commit against the base; empty when nothing changed. */
    val patch: String,
    val patchTruncated: Boolean,
)

/** What a probe found for each program name (`null`: not found) and whether the script ran at all. */
data class CliProbe(val ready: Boolean, val found: Map<String, String?>)

/** What an install script reported. */
data class CliInstallOutcome(val ok: Boolean, val summary: String)

/**
 * Reads the output of [CliRunScript]'s scripts. Pure and total: any input, including none, a block cut short, a block with
 * another run's nonce or text a CLI printed that looks like a marker, gives a value and never throws. Only lines that carry
 * this run's nonce count as markers, and the nonce is unknown to the CLI (it is not in its environment, arguments or prompt).
 */
object CliRunResultParser {
    private const val MAX_ANSWER_CHARS = 60_000
    private const val MAX_STDERR_CHARS = 4_000
    private const val MAX_PATCH_CHARS = 100_000
    private const val MAX_FIELD_CHARS = 300
    private const val MAX_INPUT_CHARS = 400_000

    fun parse(output: String?, nonce: String, format: CliOutputFormat): CliRunResult {
        return try {
            parseUnchecked(output.orEmpty().take(MAX_INPUT_CHARS), nonce, format)
        } catch (failure: Exception) {
            malformed("The runtime's result could not be read.")
        }
    }

    private fun parseUnchecked(text: String, nonce: String, format: CliOutputFormat): CliRunResult {
        if (nonce.isBlank()) return malformed("No nonce to read the result with.")
        val lines = text.replace("\r\n", "\n").split('\n')
        val begin = "AIVE-RESULT-BEGIN $nonce"
        val end = "AIVE-RESULT-END $nonce"
        val start = lines.indexOfLast { it == begin }
        if (start < 0) return malformed("The runtime printed no result (its output may have been cut short).")
        val stop = lines.withIndex().firstOrNull { it.index > start && it.value == end }?.index
            ?: return malformed("The runtime's result was cut short.")
        val block = lines.subList(start + 1, stop)

        val fields = mutableMapOf<String, String>()
        var index = 0
        while (index < block.size && !block[index].startsWith("AIVE-")) {
            val line = block[index]
            val eq = line.indexOf('=')
            if (eq > 0) fields[line.substring(0, eq)] = line.substring(eq + 1).take(MAX_FIELD_CHARS)
            index += 1
        }
        fun section(name: String): String {
            val open = "AIVE-$name-BEGIN $nonce"
            val close = "AIVE-$name-END $nonce"
            val from = block.indexOf(open)
            if (from < 0) return ""
            val to = block.withIndex().firstOrNull { it.index > from && it.value == close }?.index ?: return ""
            // The script adds one newline before the end marker.
            return block.subList(from + 1, to).joinToString("\n").removeSuffix("\n")
        }

        val status = when (fields["status"]) {
            "ok" -> CliRunStatus.Ok
            "refused" -> CliRunStatus.Refused
            "failed" -> CliRunStatus.Failed
            else -> return malformed("The runtime's result has no status.")
        }
        val exit = fields["exit"]?.trim()?.toIntOrNull() ?: -1
        val stdout = section("ANSWER").takeLast(MAX_ANSWER_CHARS)
        val reader = CliOutputReader(format)
        stdout.split('\n').forEach { reader.read(it) }
        val final = reader.finish(if (status == CliRunStatus.Ok) 0 else exit)
        return CliRunResult(
            status = status,
            exitCode = exit,
            reason = oneLine(fields["reason"].orEmpty()),
            branch = fields["branch"].orEmpty().filter { it.isLetterOrDigit() || it in "/._-" }.take(120),
            commit = fields["commit"].orEmpty().filter { it in "0123456789abcdef" }.take(64),
            answer = final.text,
            answerIsError = final.isError,
            stderrTail = section("STDERR").takeLast(MAX_STDERR_CHARS),
            patch = section("PATCH").take(MAX_PATCH_CHARS),
            patchTruncated = fields["patch-truncated"] == "1",
        )
    }

    /** Which programs a probe script found. A probe that did not print its ready line (bash did not run it) is not ready. */
    fun parseProbe(output: String?, nonce: String): CliProbe {
        return try {
            val lines = output.orEmpty().take(MAX_INPUT_CHARS).split('\n').map { it.trimEnd('\r') }
            val found = linkedMapOf<String, String?>()
            lines.forEach { line ->
                val parts = line.split(' ', limit = 4)
                if (parts.size >= 3 && parts[1] == nonce) {
                    when (parts[0]) {
                        "AIVE-FOUND" -> if (parts.size == 4 && parts[3].startsWith("/")) found[parts[2]] = parts[3].take(MAX_FIELD_CHARS)
                        "AIVE-MISSING" -> found.getOrPut(parts[2]) { null }
                    }
                }
            }
            CliProbe(ready = lines.any { it == "AIVE-READY $nonce" }, found = found)
        } catch (failure: Exception) {
            CliProbe(ready = false, found = emptyMap())
        }
    }

    /** What an install script said: exit 0 is installed; no npm is a plain message. */
    fun parseInstall(output: String?, nonce: String): CliInstallOutcome {
        return try {
            val lines = output.orEmpty().take(MAX_INPUT_CHARS).split('\n').map { it.trimEnd('\r') }
            if (lines.any { it == "AIVE-NONPM $nonce" }) return CliInstallOutcome(false, "npm is not in the Linux runtime yet.")
            val at = lines.indexOfLast { it.startsWith("AIVE-INSTALL $nonce ") }
            if (at < 0) return CliInstallOutcome(false, "The install printed no result.")
            val code = lines[at].substringAfterLast(' ').toIntOrNull() ?: -1
            val tail = oneLine(lines.drop(at + 1).takeWhile { it != "AIVE-INSTALL-END $nonce" }.joinToString(" "))
            if (code == 0) CliInstallOutcome(true, "Installed.") else CliInstallOutcome(false, "npm exit $code: ${tail.ifBlank { "no output" }}")
        } catch (failure: Exception) {
            CliInstallOutcome(false, "The install result could not be read.")
        }
    }

    private fun oneLine(text: String): String = text.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(MAX_FIELD_CHARS)

    private fun malformed(reason: String) = CliRunResult(
        status = CliRunStatus.Malformed, exitCode = -1, reason = reason, branch = "", commit = "", answer = "", answerIsError = true,
        stderrTail = "", patch = "", patchTruncated = false,
    )
}
