package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.providers.cli.CliAgentSpec
import java.io.File

/**
 * Finds an installed CLI: absolute `PATH` entries first, then where the usual installers put things.
 * Never resolves against a repository or the current directory, so a checkout cannot supply the
 * program that runs.
 */
internal object CliLocator {
    fun locate(spec: CliAgentSpec, environment: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home").orEmpty()): File? {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val names = spec.binaries.flatMap { if (windows) listOf("$it.exe", "$it.cmd", "$it.bat", it) else listOf(it) }
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
}
