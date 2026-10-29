package com.hereliesaz.geministrator

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialToolTest {
    private val unix = !System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun aToolThatNeverFinishesIsKilledAtTheTimeout() {
        if (!unix) return
        // A locked keyring: the tool waits on an unlock prompt and never writes or exits.
        val started = System.nanoTime()
        val result = CredentialTool.run(listOf("sleep", "60"), timeoutSeconds = 2)
        val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)

        assertNull(result)
        assertTrue(seconds < 10, "took ${seconds}s")
    }

    @Test
    fun aLoudErrorStreamCannotBlockTheTool() {
        if (!unix) return
        val result = CredentialTool.run(
            listOf("sh", "-c", "head -c 400000 /dev/zero | tr '\\000' x >&2; echo secret"),
            timeoutSeconds = 15,
        )
        assertEquals(CredentialTool.Result(0, "secret\n"), result)
    }

    @Test
    fun stdinReachesTheToolAndStdoutComesBack() {
        if (!unix) return
        assertEquals(CredentialTool.Result(0, "api-key"), CredentialTool.run(listOf("cat"), stdin = "api-key"))
        assertEquals(1, CredentialTool.run(listOf("sh", "-c", "exit 1"))?.exitCode)
    }

    @Test
    fun accountIdsThatCouldCarryShellOrPowerShellSyntaxAreRefused() {
        listOf(
            "x'; Remove-Item -Recurse ~; '",
            "a b",
            "\$(calc)",
            "`calc`",
            "a\"b",
            "a;b",
            "a&b",
            "a|b",
            "-flag",
            "",
            "a/b",
            "a\nb",
        ).forEach { assertFalse(CredentialTool.isSafeAccountId(it), it) }
        assertFailsWith<IllegalArgumentException> {
            CredentialTool.windowsCredentialDeleteCommand("com.hereliesaz.haive", "x'; calc; '")
        }
    }

    @Test
    fun everyIdTheAppStoresIsAccepted() {
        (ProviderCatalog.entries.map { it.id } + ProviderCatalog.RETIRED_PROVIDER_IDS + RepositoryServiceCatalog.entries.map { it.id })
            .forEach { assertTrue(CredentialTool.isSafeAccountId(it), it) }
    }

    @Test
    fun theWindowsCredentialManagerCleanupRunsCmdkeyDirectly() {
        assertEquals(
            listOf("cmdkey", "/delete:com.hereliesaz.haive/openai"),
            CredentialTool.windowsCredentialDeleteCommand("com.hereliesaz.haive", "openai"),
        )
    }
}
