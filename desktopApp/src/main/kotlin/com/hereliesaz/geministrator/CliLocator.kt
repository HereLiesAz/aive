package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.providers.cli.CliAgentSpec
import java.io.File

/**
 * Finds an installed CLI: absolute `PATH` entries first, then where the usual installers put things.
 * Never resolves against a repository or the current directory, so a checkout cannot supply the
 * program that runs.
 */
internal object CliLocator {
    fun locate(spec: CliAgentSpec, environment: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home").orEmpty()): File? =
        locateBinary(spec.binaries, environment, home)

    /** The first of [binaries] that is installed, by the same rules as [locate]; for a tool that is not a CLI agent (ffmpeg). */
    fun locateBinary(binaries: List<String>, environment: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home").orEmpty()): File? {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val names = binaries.flatMap { if (windows) listOf("$it.exe", "$it.cmd", "$it.bat", it) else listOf(it) }
        val path = environment["PATH"].orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() && File(it).isAbsolute }
        val wellKnown = listOfNotNull(
            "/opt/homebrew/bin", "/usr/local/bin", "/home/linuxbrew/.linuxbrew/bin",
            "$home/.local/bin", "$home/.claude/local", "$home/.npm-global/bin", "$home/.bun/bin", "$home/.volta/bin",
            "$home/.cargo/bin", "$home/go/bin",
            environment["APPDATA"]?.let { "$it\\npm" },
            environment["LOCALAPPDATA"]?.let { "$it\\Programs" },
        )
        return (path + wellKnown).asSequence()
            .flatMap { dir -> names.asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    /** True for a Windows command script, which the OS runs through `cmd.exe`. */
    fun isWindowsShim(program: File): Boolean = program.name.lowercase().let { it.endsWith(".cmd") || it.endsWith(".bat") }

    /**
     * The command line to start [program] with [arguments]. A `.cmd`/`.bat` shim (how npm installs most CLIs on Windows) is
     * run by `cmd.exe`, which re-reads the line and obeys `&`, `|`, `%` and the like inside an argument, so an argument that
     * contains one is **refused** rather than quoted: there is no quoting that every `cmd.exe` agrees on. A real executable
     * takes its arguments as they are.
     *
     * @throws IllegalArgumentException when [program] is a shim and the line has such a character.
     */
    fun commandFor(program: File, arguments: List<String>): List<String> {
        if (isWindowsShim(program)) {
            // A folder like "Program Files (x86)" is ordinary, so the program's own path is checked against the characters that chain commands only.
            val unsafe = program.path.takeIf { path -> path.any { it in PATH_METACHARACTERS || it.isISOControl() } }
                ?: arguments.firstOrNull { argument -> argument.any { it in SHELL_METACHARACTERS || it.isISOControl() } }
            require(unsafe == null) {
                "${program.name} is a Windows command script, which would pass this to cmd.exe: \"${unsafe?.take(40)}\". Install the program's .exe, or use a plainer folder name."
            }
        }
        return listOf(program.absolutePath) + arguments
    }

    private const val PATH_METACHARACTERS = "&|<>^%!\""
    private const val SHELL_METACHARACTERS = "&|<>^%!\"'`()=;,"
}
