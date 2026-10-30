package com.hereliesaz.geministrator

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Entire (entire.com) on the desktop, through its `entire` command (docs/Entire.md). The binary is
 * found by absolute path, never resolved against a repository, and always run with a fixed
 * argument list, never through a shell.
 */
internal class EntireCli(val binary: File) {
    data class Result(val exitCode: Int, val output: String, val error: String) {
        val ok: Boolean get() = exitCode == 0
        /** What to show a person when it failed: the error stream, else the output. */
        val message: String get() = error.ifBlank { output }.trim()
    }

    suspend fun run(
        args: List<String>,
        directory: File? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = PROCESS_TIMEOUT_SECONDS,
    ): Result {
        val process = try {
            withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(listOf(binary.absolutePath) + args)
                directory?.let(builder::directory)
                sanitizeGitEnvironment(builder)
                builder.environment().putAll(environment)
                builder.start().also { runCatching { it.outputStream.close() } }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            return Result(-1, "", failure.message ?: "Unable to run entire")
        }
        // Each stream gets its own thread: a shared pool that is busy elsewhere would leave a pipe
        // undrained, and the child would block on a full pipe until the timeout.
        val out = drain(process.inputStream, "entire-stdout")
        val err = drain(process.errorStream, "entire-stderr")
        val finished = try {
            runInterruptible(Dispatchers.IO) { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }
        } catch (failure: CancellationException) {
            destroyTree(process)
            throw failure
        }
        if (!finished) destroyTree(process)
        return Result(
            exitCode = if (finished) process.exitValue() else -1,
            output = out.await(),
            error = err.await().let { if (finished) it else "entire timed out. $it" },
        )
    }

    private class Drain(private val thread: Thread, private val text: () -> String) {
        /** What the stream produced; waits briefly for the reader to reach end of stream. */
        fun await(): String {
            thread.join(TimeUnit.SECONDS.toMillis(10))
            return text()
        }
    }

    private fun drain(stream: InputStream, name: String): Drain {
        val text = StringBuffer()
        val thread = Thread({
            runCatching {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(8_192)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        text.append(buffer, 0, read)
                    }
                }
            }
        }, name).apply {
            isDaemon = true
            start()
        }
        return Drain(thread) { text.toString() }
    }

    private fun destroyTree(process: Process) {
        runCatching { process.toHandle().descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    suspend fun version(): String? = run(listOf("--version"), timeoutSeconds = 20)
        .takeIf { it.ok }?.output?.lineSequence()?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)

    /** Whether Entire is set up in the repository at [root]. */
    suspend fun enabled(root: File): Boolean = run(listOf("status", "--json"), root, timeoutSeconds = 30)
        .output.parseObject()?.get("enabled")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true

    /** The signed-in account's handle, or null when nobody is signed in. */
    suspend fun account(): String? {
        val status = run(listOf("auth", "status", "--json"), timeoutSeconds = 30).output.parseObject() ?: return null
        if ((status["logged_in"] as? JsonPrimitive)?.booleanOrNull != true) return null
        return status.string("display_name") ?: status.string("user") ?: "signed in"
    }

    companion object {
        const val PROCESS_TIMEOUT_SECONDS = 180L

        /** `entire` on PATH (absolute entries only), else where its installers put it. */
        fun locate(): EntireCli? {
            val windows = System.getProperty("os.name").lowercase().contains("win")
            val names = if (windows) listOf("entire.exe", "entire.cmd", "entire") else listOf("entire")
            val home = System.getProperty("user.home")
            val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
                .filter { it.isNotBlank() && File(it).isAbsolute }
            val wellKnown = listOfNotNull(
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "/home/linuxbrew/.linuxbrew/bin",
                "$home/.local/bin",
                "$home/.entire/bin",
                "$home/go/bin",
                System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\entire" },
                System.getenv("LOCALAPPDATA")?.let { "$it\\entire\\bin" },
            )
            return (path + wellKnown).asSequence()
                .flatMap { dir -> names.asSequence().map { File(dir, it) } }
                .firstOrNull { it.isFile && it.canExecute() }
                ?.let(::EntireCli)
        }

        private val json = Json { ignoreUnknownKeys = true }

        internal fun String.parseObject(): JsonObject? =
            runCatching { json.parseToJsonElement(trim()).jsonObject }.getOrNull()

        internal fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
}

/**
 * Installs `entire-agent-aive` in `~/.aive/bin`: a small launcher that starts this same app with
 * `entire-agent <subcommand>` (see DesktopLauncher), so Entire can read The Aive's transcripts.
 * Checked on every call, so it always points at the running installation. Returns its folder,
 * which Aive puts first on PATH when it runs `entire`.
 */
internal fun installEntireAgentLauncher(): File? = runCatching {
    val dir = File(System.getProperty("user.home"), ".aive/bin").apply { mkdirs() }
    val windows = System.getProperty("os.name").lowercase().contains("win")
    val command = entireAgentCommand() ?: return null
    writeEntireAgentLauncher(dir, command, windows)
    dir
}.getOrNull()

/**
 * Writes the launcher into [dir] for [command]. An unchanged launcher is left alone; a changed one is
 * written beside it and moved into place, so a concurrent `entire` never runs a half-written script.
 */
internal fun writeEntireAgentLauncher(dir: File, command: List<String>, windows: Boolean): File {
    val (launcher, content) = if (windows) {
        File(dir, "entire-agent-aive.cmd") to
            "@echo off\r\n" + command.joinToString(" ") { "\"$it\"" } + " %*\r\n"
    } else {
        File(dir, "entire-agent-aive") to
            "#!/bin/sh\nexec " + command.joinToString(" ") { it.shellQuoted() } + " \"\$@\"\n"
    }
    val current = runCatching { launcher.takeIf(File::isFile)?.readText() }.getOrNull()
    if (current != content) {
        val staged = Files.createTempFile(dir.toPath(), ".${launcher.name}-", ".tmp")
        try {
            Files.writeString(staged, content)
            if (!windows) staged.toFile().setExecutable(true, true)
            try {
                Files.move(staged, launcher.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, launcher.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }
    if (!windows && !launcher.canExecute()) launcher.setExecutable(true, true)
    return launcher
}

/** How to start this app as the plugin: its native launcher, or the JVM with this classpath. */
private fun entireAgentCommand(): List<String>? {
    val executable = ProcessHandle.current().info().command().orElse(null) ?: return null
    val name = File(executable).name.lowercase()
    return if (name == "java" || name == "java.exe" || name == "javaw.exe") {
        listOf(
            executable,
            "-Djava.awt.headless=true",
            "-cp",
            System.getProperty("java.class.path"),
            "com.hereliesaz.geministrator.DesktopLauncherKt",
            ENTIRE_AGENT_ARGUMENT,
        )
    } else {
        listOf(executable, ENTIRE_AGENT_ARGUMENT)
    }
}

internal const val ENTIRE_AGENT_ARGUMENT = "entire-agent"

private fun String.shellQuoted(): String = "'" + replace("'", "'\\''") + "'"
