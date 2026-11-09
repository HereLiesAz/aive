package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.cli.CliAgentHost
import com.hereliesaz.geministrator.providers.cli.CliAgentSpec
import com.hereliesaz.geministrator.providers.cli.CliAgentSpecs
import com.hereliesaz.geministrator.providers.cli.CliAgentStatus
import com.hereliesaz.geministrator.providers.cli.CliEvent
import com.hereliesaz.geministrator.providers.cli.CliOutputReader
import com.hereliesaz.geministrator.providers.cli.CliPromptChannel
import com.hereliesaz.geministrator.providers.cli.CliRunMode
import com.hereliesaz.geministrator.providers.cli.CliTestResult
import java.io.File
import java.nio.file.Files

/**
 * The desktop's CLI agents (docs/Local-CLI-Agents.md): which are installed, which the keeper turned on, and a
 * Test that runs the installed CLI read-only on a tiny prompt so a wrong flag for its version shows up
 * as a message instead of a failed task. Turning one on or off rebuilds the provider list through [onChanged].
 */
internal class DesktopCliAgents(
    private val settings: CliAgentSettings = CliAgentSettings(),
    private val runner: CliProcessRunner = ProcessCliRunner,
    private val locate: (CliAgentSpec) -> File? = { CliLocator.locate(it) },
    private val environment: () -> Map<String, String> = { System.getenv() },
    private val onChanged: () -> Unit = {},
) : CliAgentHost {
    private val versions = mutableMapOf<String, String?>()
    private val tests = mutableMapOf<String, CliTestResult>()

    override suspend fun statuses(): List<CliAgentStatus> = CliAgentSpecs.all.map { spec ->
        val program = locate(spec)
        CliAgentStatus(
            spec = spec,
            path = program?.absolutePath,
            version = program?.let { versions.getOrPut(it.absolutePath) { version(spec, it) } },
            enabled = settings.enabled(spec.id),
            passKeys = settings.passKeys(spec.id),
            lastTest = tests[spec.id],
        )
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        settings.setEnabled(id, enabled)
        onChanged()
    }

    override fun setPassKeys(id: String, passKeys: Boolean) {
        settings.setPassKeys(id, passKeys)
        onChanged()
    }

    override suspend fun test(id: String): CliTestResult {
        val spec = CliAgentSpecs.byId(id) ?: return CliTestResult(false, "Unknown CLI.", now(), 0)
        val program = locate(spec) ?: return CliTestResult(false, "${spec.displayName} is not installed (looked for ${spec.binaries.joinToString()}).", now(), 0)
        val folder = Files.createTempDirectory("aive-cli-test-").toFile()
        val started = now()
        val result = try {
            val prompt = "Reply with the single word OK. Do not use any tools."
            val reader = CliOutputReader(spec.outputFormat)
            val process = runner.run(
                listOf(program.absolutePath) + spec.arguments(CliRunMode.ReadOnly, folder.absolutePath, prompt),
                folder, cliEnvironmentFor(spec, settings, environment()),
                if (spec.promptChannel == CliPromptChannel.Stdin) prompt else null, TEST_TIMEOUT_MILLIS,
            ) { line -> reader.read(line).filterIsInstance<CliEvent.Progress>() }
            val final = reader.finish(process.exitCode)
            val millis = now() - started
            when {
                process.startFailure != null -> CliTestResult(false, "Could not start it: ${process.startFailure}", now(), millis)
                process.timedOut -> CliTestResult(false, "It did not answer within ${TEST_TIMEOUT_MILLIS / 1000} seconds. Is it logged in?", now(), millis)
                process.exitCode != 0 || final.isError || final.text.isBlank() ->
                    CliTestResult(false, "Exit ${process.exitCode}: " + clean((final.text.ifBlank { process.stderrTail }).ifBlank { "no output" }), now(), millis)
                else -> CliTestResult(true, "Answered in ${millis / 1000.0} s: " + clean(final.text), now(), millis)
            }
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            CliTestResult(false, failure.message ?: "The test failed.", now(), now() - started)
        } finally {
            folder.deleteRecursively()
        }
        tests[id] = result
        return result
    }

    private suspend fun version(spec: CliAgentSpec, program: File): String? = runCatching {
        val dir = File(System.getProperty("java.io.tmpdir").orEmpty())
        val lines = mutableListOf<String>()
        runner.run(listOf(program.absolutePath) + spec.versionArgs, dir, cliEnvironmentFor(spec, settings, environment()), null, 15_000) { lines += it }
        lines.firstOrNull { it.isNotBlank() }?.trim()?.take(80)
    }.getOrNull()

    private fun clean(text: String) = ControlRedaction.scrub(text.trim().replace(Regex("\\s+"), " ")).take(240)

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 90_000L
    }
}

/** The CLI providers that are on and installed, ready to join the provider list. */
internal fun configuredCliProviders(settings: CliAgentSettings = CliAgentSettings()): List<AgentProvider> =
    CliAgentSpecs.all.filter { settings.enabled(it.id) && CliLocator.locate(it) != null }.map { CliAgentProvider(it, settings) }
