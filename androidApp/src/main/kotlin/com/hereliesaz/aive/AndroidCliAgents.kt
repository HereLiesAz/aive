package com.hereliesaz.aive

import android.content.Context
import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.providers.cli.CliAgentHost
import com.hereliesaz.geministrator.providers.cli.CliAgentSpec
import com.hereliesaz.geministrator.providers.cli.CliAgentSpecs
import com.hereliesaz.geministrator.providers.cli.CliAgentStatus
import com.hereliesaz.geministrator.providers.cli.CliProbe
import com.hereliesaz.geministrator.providers.cli.CliRunBuild
import com.hereliesaz.geministrator.providers.cli.CliRunMode
import com.hereliesaz.geministrator.providers.cli.CliRunRequest
import com.hereliesaz.geministrator.providers.cli.CliRunResult
import com.hereliesaz.geministrator.providers.cli.CliRunResultParser
import com.hereliesaz.geministrator.providers.cli.CliRunScript
import com.hereliesaz.geministrator.providers.cli.CliRunStatus
import com.hereliesaz.geministrator.providers.cli.CliTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which vendor CLIs the keeper turned on, on this phone. Plain switches; no key or token is ever stored here.
 * "Passing keys" also lets the clone use the linked repository's token (one-shot, see [CliRunRequest.useRepositoryToken]).
 */
internal class AndroidCliAgentSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cli-agents", Context.MODE_PRIVATE)
    fun enabled(id: String): Boolean = prefs.getBoolean("$id.enabled", false)
    fun setEnabled(id: String, value: Boolean) = prefs.edit().putBoolean("$id.enabled", value).apply()
    fun passKeys(id: String): Boolean = prefs.getBoolean("$id.pass-keys", false)
    fun setPassKeys(id: String, value: Boolean) = prefs.edit().putBoolean("$id.pass-keys", value).apply()
    fun allowProjectConfig(id: String): Boolean = prefs.getBoolean("$id.allow-project-config", false)
    fun setAllowProjectConfig(id: String, value: Boolean) = prefs.edit().putBoolean("$id.allow-project-config", value).apply()
}

/**
 * The phone's CLI agents as the Keeper's room sees them (docs/Local-CLI-Agents.md, "On a phone"): which are installed in the hidden
 * Linux runtime, which the keeper turned on, Install (the one `apt-get install` / `npm install -g` for that CLI, run only when pressed)
 * and Test. Everything it says is in plain words; there is no terminal.
 */
internal class AndroidCliAgents(
    private val runtime: AndroidCliRuntime,
    private val settings: AndroidCliAgentSettings,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit = {},
) : CliAgentHost {
    private val tests = mutableMapOf<String, CliTestResult>()
    private val probeLock = Mutex()
    private var probe: CliProbe? = null
    @Volatile private var installing: String? = null
    private val installFailures = mutableMapOf<String, String>()

    private val probeNames: List<String> by lazy {
        CliAgentSpecs.all.filter { it.androidInstall != null }.flatMap { it.binaries }.distinct()
    }

    /** The CLI's program path inside the runtime, or null when it is not installed (or the runtime is not ready). Asks the runtime once, then remembers. */
    suspend fun locate(spec: CliAgentSpec): String? {
        val found = currentProbe()?.found ?: return null
        return spec.binaries.firstNotNullOfOrNull { found[it] }
    }

    private suspend fun currentProbe(): CliProbe? = probeLock.withLock {
        probe ?: runtime.probe(probeNames).also { probe = it }
    }

    private suspend fun forgetProbe() = probeLock.withLock { probe = null }

    override suspend fun statuses(): List<CliAgentStatus> {
        val state = runtime.state.value
        return CliAgentSpecs.all.map { spec ->
            val recipe = spec.androidInstall
            val path = if (recipe != null && state == CliRuntimeState.Ready) locate(spec) else null
            val working = installing == spec.id
            val note = when {
                recipe == null -> "${spec.displayName} has no known way to run on a phone yet: its maker installs it another way."
                state is CliRuntimeState.NotAvailable -> state.reason
                working -> "Installing ${spec.displayName} in the Linux runtime. This can take several minutes."
                state is CliRuntimeState.Working -> state.stage + (state.percent?.let { " ($it%)" } ?: "") + "..."
                state is CliRuntimeState.Failed -> state.reason
                path == null && state == CliRuntimeState.NotPrepared ->
                    "Press Install. The first time, it downloads a private Linux runtime for this app (about 30 MB, then packages: several hundred MB in all). " + recipe.note
                path == null -> (installFailures[spec.id]?.let { "The last install failed: $it " }.orEmpty()) + "Not installed yet. " + recipe.note
                else -> recipe.note
            }.trim().ifBlank { null }
            CliAgentStatus(
                spec = spec,
                path = path,
                version = null,
                enabled = settings.enabled(spec.id),
                passKeys = settings.passKeys(spec.id),
                lastTest = tests[spec.id],
                allowProjectConfig = settings.allowProjectConfig(spec.id),
                note = note,
                busy = working || state is CliRuntimeState.Working,
                canInstall = recipe != null && state !is CliRuntimeState.NotAvailable,
            )
        }
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        settings.setEnabled(id, enabled)
        onChanged()
    }

    override fun setPassKeys(id: String, passKeys: Boolean) {
        settings.setPassKeys(id, passKeys)
        onChanged()
    }

    override fun setAllowProjectConfig(id: String, allow: Boolean) {
        settings.setAllowProjectConfig(id, allow)
        onChanged()
    }

    override val removeRuntimeLabel: String?
        get() = if (runtime.state.value is CliRuntimeState.NotAvailable) null else "Remove the Linux runtime and free its storage"

    override suspend fun removeRuntime() {
        scope.async { runtime.remove(); forgetProbe() }.await()
        onChanged()
    }

    /** Prepares the runtime if needed, then runs the CLI's one install command. The work belongs to this host, so leaving the screen does not stop it. */
    override suspend fun install(id: String): CliTestResult = scope.async { installNow(id) }.await()

    private suspend fun installNow(id: String): CliTestResult {
        val spec = CliAgentSpecs.byId(id) ?: return CliTestResult(false, "Unknown CLI.", now(), 0)
        val recipe = spec.androidInstall
            ?: return CliTestResult(false, "${spec.displayName} has no known way to run on a phone yet.", now(), 0)
        val started = now()
        installing = id
        return try {
            val state = runtime.ensureReady()
            if (state != CliRuntimeState.Ready) {
                val why = (state as? CliRuntimeState.Failed)?.reason ?: (state as? CliRuntimeState.NotAvailable)?.reason ?: "The Linux runtime is not ready."
                return CliTestResult(false, why, now(), now() - started)
            }
            val nonce = runtime.newNonce()
            val script = CliRunScript.installScript(nonce, recipe.aptPackages, recipe.npmSpec, update = false)
                ?: return CliTestResult(false, "That install is not a plain package name.", now(), 0)
            val result = runtime.runScript(script, INSTALL_TIMEOUT_MILLIS)
            val outcome = CliRunResultParser.parseInstall(result.output, nonce)
            forgetProbe()
            when {
                result.startFailure != null -> CliTestResult(false, "Could not start the runtime: ${result.startFailure}", now(), now() - started)
                result.timedOut -> CliTestResult(false, "Installing took too long and was stopped.", now(), now() - started)
                !outcome.ok -> CliTestResult(false, clean(outcome.summary), now(), now() - started)
                locate(spec) == null -> CliTestResult(false, "The install finished but ${spec.displayName}'s program is not in the runtime.", now(), now() - started)
                else -> CliTestResult(true, "Installed ${recipe.packageName}.", now(), now() - started)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            CliTestResult(false, failure.message ?: "The install failed.", now(), now() - started)
        } finally {
            installing = null
        }.also { result ->
            tests[id] = result
            if (result.ok) installFailures.remove(id) else installFailures[id] = result.summary
            onChanged()
        }
    }

    /** Runs the installed CLI read-only on a tiny prompt with no repository; uses the keeper's own quota. */
    override suspend fun test(id: String): CliTestResult {
        val spec = CliAgentSpecs.byId(id) ?: return CliTestResult(false, "Unknown CLI.", now(), 0)
        if (spec.androidInstall == null) return CliTestResult(false, "${spec.displayName} has no known way to run on a phone yet.", now(), 0)
        val started = now()
        val result = try {
            val nonce = runtime.newNonce()
            val request = CliRunRequest(
                spec = spec, mode = CliRunMode.ReadOnly, repositoryUrl = null, baseRef = null, branch = "aive/cli-test-${nonce.take(8)}",
                runId = "test-${nonce.take(8)}", prompt = "Reply with the single word OK. Do not use any tools.", nonce = nonce,
                passKeys = settings.passKeys(id), timeoutSeconds = TEST_TIMEOUT_SECONDS, commitSubject = "Test",
            )
            val ran = runCliRequest(runtime, request, repositoryToken = null)
            val millis = now() - started
            when {
                ran.status == CliRunStatus.Ok && ran.answer.isNotBlank() -> CliTestResult(true, "Answered in ${millis / 1000.0} s: " + clean(ran.answer), now(), millis)
                else -> CliTestResult(false, clean(ran.reason.ifBlank { ran.answer.ifBlank { ran.stderrTail } }.ifBlank { "no output" }), now(), millis)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            CliTestResult(false, failure.message ?: "The test failed.", now(), now() - started)
        }
        tests[id] = result
        return result
    }

    private fun clean(text: String) = ControlRedaction.scrub(text.trim().replace(Regex("\\s+"), " ")).take(240)

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val INSTALL_TIMEOUT_MILLIS = 40 * 60_000L
        const val TEST_TIMEOUT_SECONDS = 120
    }
}

/**
 * Builds and runs one [CliRunRequest] in [runtime] and reads the result; never throws except for cancellation. [repositoryToken] is
 * handed to the clone as the one-shot variable only when the request allows it and the address is https.
 */
internal suspend fun runCliRequest(runtime: AndroidCliRuntime, request: CliRunRequest, repositoryToken: String?): CliRunResult {
    if (runtime.state.value != CliRuntimeState.Ready) {
        return failedResult("The Linux runtime is not ready: open Keeper's room, Local agents and press Install.")
    }
    val script = when (val built = CliRunScript.build(request)) {
        is CliRunBuild.Refused -> return failedResult(built.reason, refused = true)
        is CliRunBuild.Ready -> built.script
    }
    val token = repositoryToken?.takeIf { request.useRepositoryToken && it.isNotBlank() }
    val environment = if (token != null) mapOf(CliRunScript.TOKEN_VARIABLE to token) else emptyMap()
    val ran = try {
        runtime.runScript(script, (request.timeoutSeconds + 180) * 1000L, environment)
    } finally {
        runtime.removeRun(request.runId)
    }
    if (ran.startFailure != null) return failedResult("Could not start the Linux runtime: ${ran.startFailure}")
    if (ran.timedOut) return failedResult("${request.spec.displayName} did not finish in time and was stopped.")
    return CliRunResultParser.parse(ran.output, request.nonce, request.spec.outputFormat)
}

private fun failedResult(reason: String, refused: Boolean = false) = CliRunResult(
    status = if (refused) CliRunStatus.Refused else CliRunStatus.Failed, exitCode = -1, reason = reason, branch = "", commit = "", answer = "",
    answerIsError = true, stderrTail = "", patch = "", patchTruncated = false,
)
