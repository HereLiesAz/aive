package com.hereliesaz.geministrator

import java.util.concurrent.TimeUnit

/**
 * Runs the OS credential tools (`security`, `secret-tool`, `cmdkey`, PowerShell) with a bound on
 * time. A locked keyring can make such a tool wait for an unlock prompt indefinitely, and one that
 * writes to a stream nobody reads blocks once the pipe fills, so: stderr is discarded, stdin and
 * stdout are handled on a dedicated thread, and the process tree is killed at the timeout. Commands
 * are fixed argument lists, never a shell string.
 */
internal object CredentialTool {
    const val TIMEOUT_SECONDS = 20L

    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /** The finished command, or null when it could not start or did not finish within [timeoutSeconds]. */
    fun run(
        command: List<String>,
        stdin: String? = null,
        timeoutSeconds: Long = TIMEOUT_SECONDS,
    ): Result? = run(ProcessBuilder(command), stdin, timeoutSeconds)

    /** [run] for a prepared [builder] (environment, directory); its stderr is discarded. */
    fun run(
        builder: ProcessBuilder,
        stdin: String? = null,
        timeoutSeconds: Long = TIMEOUT_SECONDS,
    ): Result? {
        val process = try {
            builder.redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: Exception) {
            return null
        }
        val output = StringBuffer()
        val io = Thread({
            runCatching {
                process.outputStream.bufferedWriter().use { writer -> if (stdin != null) writer.write(stdin) }
            }
            runCatching { output.append(process.inputStream.bufferedReader().use { it.readText() }) }
        }, "credential-tool-io").apply {
            isDaemon = true
            start()
        }
        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            runCatching { process.toHandle().descendants().forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            return null
        }
        io.join(TimeUnit.SECONDS.toMillis(5))
        return Result(process.exitValue(), output.toString())
    }

    /**
     * Whether [id] may name a keychain account. Ids come from the app's catalogs; this is the check
     * that keeps anything else (quotes, spaces, shell or PowerShell syntax) away from the tools.
     */
    fun isSafeAccountId(id: String): Boolean = SAFE_ACCOUNT_ID.matches(id)

    /** Deletes a legacy Windows Credential Manager entry: `cmdkey` itself, with the target as one argument. */
    fun windowsCredentialDeleteCommand(service: String, account: String): List<String> {
        require(isSafeAccountId(account)) { "Unsupported credential account id" }
        return listOf("cmdkey", "/delete:$service/$account")
    }

    private val SAFE_ACCOUNT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
}
