package com.hereliesaz.geministrator.providers.cli

import com.hereliesaz.geministrator.domain.AgentCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliRunScriptTest {
    private val nonce = "0123456789abcdef0123456789abcdef"

    private fun request(
        spec: CliAgentSpec = CliAgentSpecs.Claude,
        mode: CliRunMode = CliRunMode.EditFiles,
        url: String? = "https://github.com/acme/app.git",
        branch: String = "aive/cli-task-1-abcd1234",
        prompt: String = "Fix the failing test.",
        useToken: Boolean = false,
        passKeys: Boolean = false,
    ) = CliRunRequest(spec, mode, url, null, branch, "task-1", prompt, nonce, passKeys = passKeys, useRepositoryToken = useToken)

    private fun ready(request: CliRunRequest): String = assertIs<CliRunBuild.Ready>(CliRunScript.build(request)).script

    /** Reads one POSIX single-quoted word back the way `sh` does: `'...'` literal, `\'` a quote. */
    private fun unquote(quoted: String): String {
        val out = StringBuilder()
        var i = 0
        var inside = false
        while (i < quoted.length) {
            val c = quoted[i]
            when {
                inside && c == '\'' -> inside = false
                inside -> out.append(c)
                c == '\'' -> inside = true
                c == '\\' && i + 1 < quoted.length -> { i += 1; out.append(quoted[i]) }
                else -> error("unquoted character $c in $quoted")
            }
            i += 1
        }
        check(!inside) { "unterminated quote in $quoted" }
        return out.toString()
    }

    @Test
    fun quotingRoundTripsEveryAwkwardString() {
        listOf(
            "", "plain", "it's", "'", "''", "a b\tc", "line1\nline2", "\$(rm -rf /)", "`id`", "; echo x", "\"double\"", "back\\slash", "\\'", "*?[a]", "~", "-n", "emoji 😀",
        ).forEach { assertEquals(it, unquote(PosixShell.quote(it)), "round trip of <$it>") }
        assertEquals("ab", unquote(PosixShell.quote("a\u0000b")), "a NUL cannot be in a shell word and is dropped")
    }

    @Test
    fun everySpecBuildsInEveryModeAndNeverCarriesAForbiddenFlag() {
        CliAgentSpecs.all.forEach { spec ->
            CliRunMode.entries.forEach { mode ->
                val script = ready(request(spec = spec, mode = mode))
                CliAgentSpecs.FORBIDDEN_FLAGS.forEach { flag -> assertFalse(script.contains(flag, ignoreCase = true), "${spec.id} $mode carries $flag") }
                assertTrue(script.contains("BINS=(" + spec.binaries.joinToString(" ") { PosixShell.quote(it) } + ")"))
            }
        }
    }

    @Test
    fun aSpecWhoseCommandLineSkipsAPermissionCheckIsRefused() {
        val bad = CliAgentSpec(
            id = "cli-bad", displayName = "Bad", binaries = listOf("bad"), promptChannel = CliPromptChannel.Stdin, outputFormat = CliOutputFormat.PlainText,
            capabilities = setOf(AgentCapability.RepositoryRead), sandboxNote = "none",
            build = { _, _, _ -> listOf("--dangerously-skip-permissions") },
        )
        assertIs<CliRunBuild.Refused>(CliRunScript.build(request(spec = bad)))
        val split = CliAgentSpec(
            id = "cli-bad2", displayName = "Bad", binaries = listOf("bad"), promptChannel = CliPromptChannel.Stdin, outputFormat = CliOutputFormat.PlainText,
            capabilities = setOf(AgentCapability.RepositoryRead), sandboxNote = "none",
            build = { _, _, _ -> listOf("--approval-mode", "yolo") },
        )
        assertIs<CliRunBuild.Refused>(CliRunScript.build(request(spec = split)))
    }

    @Test
    fun theBranchIsAPlainAiveBranchName() {
        listOf("main", "aive/cli-x; rm -rf /", "aive/cli-\$(id)", "aive/cli-a b", "aive/cli-..", "aive/cli-a'b", "-aive/cli-x", "aive/cli-x.lock", "aive/cli-\nx").forEach {
            assertIs<CliRunBuild.Refused>(CliRunScript.build(request(branch = it)), "branch <$it>")
        }
        ready(request(branch = "aive/cli-abc_DEF-1.2"))
    }

    @Test
    fun theRepositoryAddressIsNeutralisedBeforeItIsQuoted() {
        listOf(
            "ext::sh -c 'touch /tmp/x'", "https://x.test/a b", "https://x.test/a;id", "https://x.test/\$(id)", "https://user:pass@x.test/r", "-oProxyCommand=id", "file:///etc",
            "https://x.test/../../etc", "https://x.test/`id`", "git://x.test/r", "https://x.test/r\n--upload-pack=id", "ssh://-oProxyCommand=x@h/r", "javascript:alert(1)", "",
        ).forEach { assertIs<CliRunBuild.Refused>(CliRunScript.build(request(url = it)), "url <$it>") }
        listOf("https://github.com/acme/app.git", "https://gitlab.com:443/group/sub/app", "ssh://git@host.test/acme/app.git", "git@github.com:acme/app.git").forEach {
            assertNull(CliRunScript.repositoryUrlProblem(it), it)
            ready(request(url = it))
        }
    }

    @Test
    fun aPromptWithShellSyntaxStaysInsideItsQuotedHereDocument() {
        val hostile = "'; touch /tmp/pwned; echo '\n\$(id) `id` \${HOME}\nEOF\nAIVE_PROMPT_END_x\n\"\\"
        val script = ready(request(prompt = hostile))
        val marker = "IFS= read -r -d '' PROMPT <<'AIVE_PROMPT_END_$nonce' || true\n"
        val at = script.indexOf(marker)
        assertTrue(at > 0)
        val before = script.substring(0, at)
        val after = script.substring(at + marker.length)
        assertFalse(before.contains("touch /tmp/pwned"), "the prompt is not in a variable assignment")
        assertTrue(after.startsWith(hostile + "\nAIVE_PROMPT_END_$nonce\n"), "the prompt is the here-document's text, verbatim")
        // The only line that ends the here-document is the one with the run's nonce.
        assertEquals(1, script.lines().count { it == "AIVE_PROMPT_END_$nonce" })
    }

    @Test
    fun aPromptThatNamesTheRunsPrivateMarkerIsRefused() {
        assertIs<CliRunBuild.Refused>(CliRunScript.build(request(prompt = "hi\nAIVE_PROMPT_END_$nonce\ntouch x")))
    }

    @Test
    fun aBadNonceTimeoutOrEditWithoutARepositoryIsRefused() {
        assertIs<CliRunBuild.Refused>(CliRunScript.build(request().copy(nonce = "short")))
        assertIs<CliRunBuild.Refused>(CliRunScript.build(request().copy(nonce = "0123456789abcdef'; id; '")))
        assertIs<CliRunBuild.Refused>(CliRunScript.build(request().copy(timeoutSeconds = 5)))
        assertIs<CliRunBuild.Refused>(CliRunScript.build(request(url = null, mode = CliRunMode.EditFiles)))
        assertIs<CliRunBuild.Refused>(CliRunScript.build(request().copy(baseRef = "main; id")))
        assertIs<CliRunBuild.Ready>(CliRunScript.build(request(url = null, mode = CliRunMode.ReadOnly)))
    }

    @Test
    fun theScriptIsBoundedAndSaysWhenThePromptWasCut() {
        val huge = "x".repeat(500_000)
        val built = assertIs<CliRunBuild.Ready>(CliRunScript.build(request(prompt = huge)))
        assertTrue(built.promptTruncated)
        assertTrue(built.script.length < 100_000)
        val argumentChannel = assertIs<CliRunBuild.Ready>(CliRunScript.build(request(spec = CliAgentSpecs.Amp, mode = CliRunMode.ReadOnly, prompt = huge)))
        assertTrue(argumentChannel.script.length < CliAgentSpec.MAX_ARGUMENT_PROMPT_CHARS + 20_000)
    }

    @Test
    fun theProjectSettingsRefusalAndTheBareEnvironmentAreInTheScript() {
        val script = ready(request(spec = CliAgentSpecs.Claude))
        assertTrue(script.contains("CFGPATS=('.claude/settings' '.mcp.json')"))
        assertTrue(script.contains("env -i \"\${E[@]}\" timeout -k 10"))
        assertTrue(script.contains("core.hooksPath=/dev/null"))
        assertTrue(script.contains("--no-verify"))
        assertTrue(script.contains("user.email=aive@localhost"))
        assertFalse(script.contains("ANTHROPIC_API_KEY"), "a vendor key variable is named only when the keeper chose to pass it")
        assertTrue(ready(request(spec = CliAgentSpecs.Claude, passKeys = true)).contains("'ANTHROPIC_API_KEY'"))
    }

    @Test
    fun theRepositoryTokenIsOnlyEverAHostNameInTheScriptAndOnlyForHttps() {
        assertTrue(ready(request(useToken = true)).contains("TOKENHOST='github.com'\n"))
        assertTrue(ready(request(useToken = false)).contains("TOKENHOST=''\n"))
        assertTrue(ready(request(url = "git@github.com:acme/app.git", useToken = true)).contains("TOKENHOST=''\n"))
        val script = ready(request(useToken = true))
        assertTrue(script.contains("AIVE_GIT_TOKEN"), "it is read from the one-shot variable")
        assertFalse(script.contains("--header") || script.contains("clone https://x-access-token"), "never in a command line")
        assertTrue(script.contains("unset AIVE_GIT_TOKEN"))
    }

    @Test
    fun theRunFolderNameCannotEscapeTheRunsRoot() {
        val root = "${CliRunScript.RUNTIME_HOME}/.aive-cli"
        // Only letters, digits and dashes survive: no dot, no slash, so it can never leave the root.
        assertEquals("$root/etc", CliRunScript.runFolder("../../etc"))
        listOf("../../etc", "a/b", "..", "x\u0000y").forEach { name ->
            val tail = CliRunScript.runFolder(name).removePrefix("$root/")
            assertTrue(tail.isNotEmpty() && '/' !in tail && '.' !in tail, name)
        }
        assertEquals("$root/task-1", CliRunScript.runFolder("task-1"))
        assertEquals("$root/run", CliRunScript.runFolder(""))
    }

    @Test
    fun probeAndInstallScriptsRefuseAnythingButPlainNames() {
        assertNull(CliRunScript.probeScript(nonce, listOf("claude; id")))
        assertNull(CliRunScript.probeScript(nonce, listOf("-x")))
        assertNull(CliRunScript.probeScript("bad", listOf("claude")))
        assertTrue(CliRunScript.probeScript(nonce, listOf("claude", "git"))!!.contains("NAMES=('claude' 'git')"))
        assertNull(CliRunScript.installScript(nonce, listOf("git; id"), null, false))
        assertNull(CliRunScript.installScript(nonce, listOf("-o"), null, false))
        assertNull(CliRunScript.installScript(nonce, emptyList(), "pkg; id", false))
        assertNull(CliRunScript.installScript(nonce, emptyList(), "--registry=http://evil", false))
        assertNull(CliRunScript.installScript(nonce, emptyList(), "pkg@latest", false))
        assertNull(CliRunScript.installScript(nonce, emptyList(), null, false), "nothing to do")
        assertNull(CliRunScript.installScript("bad", listOf("git"), null, false))
        assertTrue(CliRunScript.installScript(nonce, listOf("nodejs-lts"), "@anthropic-ai/claude-code@2.1.112", false)!!.contains("NPMSPEC='@anthropic-ai/claude-code@2.1.112'"))
        assertTrue(CliRunScript.installScript(nonce, CliRunScript.BASE_PACKAGES, null, true)!!.contains("UPDATE=1"))
    }

    @Test
    fun aptPreparesGuestCacheAndListsBeforeUpdateOrInstall() {
        val update = CliRunScript.installScript(nonce, listOf("git"), null, true)!!
        val retry = CliRunScript.installScript(nonce, listOf("codex"), null, false)!!
        val listsOnly = CliRunScript.installScript(nonce, emptyList(), null, true)!!
        for (script in listOf(update, retry, listsOnly)) {
            assertTrue(script.contains("APT_ARCHIVES='/data/data/com.termux/cache/apt/archives'"))
            assertTrue(script.contains("APT_LISTS='/data/data/com.termux/files/usr/var/lib/apt/lists'"))
            assertTrue(script.contains("mkdir -p -- \"\$APT_ARCHIVES/partial\" \"\$APT_LISTS/partial\""))
            assertTrue(script.contains("Dir::Cache::archives=\$APT_ARCHIVES/"))
            assertTrue(script.contains("Dir::State::lists=\$APT_LISTS/"))
            assertTrue(script.indexOf("mkdir -p --") < script.indexOf("apt-get"))
            assertTrue(script.contains("apt-get \"\${APT_DIR_OPTIONS[@]}\" update"))
            assertTrue(script.contains("apt-get \"\${APT_DIR_OPTIONS[@]}\" install"))
        }
    }

    @Test
    fun everyAndroidInstallRecipeIsAPlainPackageNameTheScriptAccepts() {
        val recipes = CliAgentSpecs.all.mapNotNull { it.androidInstall }
        assertTrue(recipes.isNotEmpty())
        recipes.forEach { recipe ->
            assertTrue(CliRunScript.installScript(nonce, recipe.aptPackages, recipe.npmSpec, false) != null, recipe.packageName)
            assertEquals(recipe.source == AndroidCliInstall.Source.Npm, recipe.npmSpec != null)
        }
        assertEquals("@anthropic-ai/claude-code@2.1.112", CliAgentSpecs.Claude.androidInstall?.npmSpec)
        assertEquals(listOf("codex"), CliAgentSpecs.Codex.androidInstall?.aptPackages)
        assertNull(CliAgentSpecs.Grok.androidInstall, "no known route to Android: it is not offered on a phone")
    }
}
