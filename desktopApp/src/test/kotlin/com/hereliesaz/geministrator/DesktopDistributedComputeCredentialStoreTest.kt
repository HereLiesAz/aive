package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDistributedComputeCredentialStoreTest {
    @Test
    fun windowsScriptsNeverCarryTheTokenOrPathOnTheCommandLine() {
        val tokenFile = File("C:/Users/someone/AppData/Local/Aive/token.dpapi")
        for (script in listOf(
            DesktopDistributedComputeCredentialStore.WINDOWS_WRITE_SCRIPT,
            DesktopDistributedComputeCredentialStore.WINDOWS_READ_SCRIPT,
        )) {
            val builder = DesktopDistributedComputeCredentialStore.windowsPowerShell(script, tokenFile)
            assertEquals(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script), builder.command())
            assertFalse(builder.command().any { tokenFile.absolutePath in it })
            assertEquals(
                tokenFile.absolutePath,
                builder.environment()[DesktopDistributedComputeCredentialStore.WINDOWS_TOKEN_FILE_ENV],
            )
        }
    }

    @Test
    fun windowsScriptsUseDpapiRatherThanAMissingModule() {
        val write = DesktopDistributedComputeCredentialStore.WINDOWS_WRITE_SCRIPT
        val read = DesktopDistributedComputeCredentialStore.WINDOWS_READ_SCRIPT
        assertTrue("[Console]::In.ReadToEnd()" in write)
        assertTrue("ConvertFrom-SecureString -SecureString" in write)
        assertTrue("ConvertTo-SecureString -String \$sealed;" in read)
        assertFalse("Get-StoredCredential" in read)
        assertTrue("\$env:${DesktopDistributedComputeCredentialStore.WINDOWS_TOKEN_FILE_ENV}" in read)
    }
}
