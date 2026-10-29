package com.hereliesaz.geministrator

import java.io.File
import java.util.prefs.Preferences

internal class DesktopDistributedComputeCredentialStore {
    private val fallback = Preferences.userRoot().node(PREFERENCES_NODE)

    fun readToken(): String? =
        System.getenv("AIVE_RELAY_TOKEN")?.trim()?.takeIf(String::isNotEmpty)
            ?: System.getenv("HAIVE_RELAY_TOKEN")?.trim()?.takeIf(String::isNotEmpty)
            ?: readFromKeychain()
            ?: fallback.get(ACCOUNT, null)?.trim()?.takeIf(String::isNotEmpty)

    fun writeToken(token: String) {
        val clean = token.trim()
        require(clean.isNotEmpty()) { "Relay token is required" }
        // Only drop the fallback copy once the OS store demonstrably returns the token.
        if (!writeToKeychain(clean) || readFromKeychain() != clean) {
            fallback.put(ACCOUNT, clean)
            fallback.flush()
        } else {
            fallback.remove(ACCOUNT)
            fallback.flush()
        }
    }

    fun clear() {
        deleteFromKeychain()
        fallback.remove(ACCOUNT)
        fallback.flush()
    }

    internal companion object {
        const val PREFERENCES_NODE = "com/hereliesaz/haive/distributed-compute"
        const val KEYCHAIN_SERVICE = "com.hereliesaz.haive.distributed-compute"
        const val ACCOUNT = "relay-token"

        /** Environment variable through which the Windows scripts receive the token file path. */
        const val WINDOWS_TOKEN_FILE_ENV = "AIVE_RELAY_TOKEN_FILE"

        /**
         * Windows: the token is encrypted with DPAPI for the current user
         * (`ConvertFrom-SecureString` without a key) into a per-user file. The scripts are
         * constants; the token travels only through stdin and stdout, the path through
         * [WINDOWS_TOKEN_FILE_ENV], so neither appears in a command line.
         */
        const val WINDOWS_WRITE_SCRIPT =
            "\$ErrorActionPreference = 'Stop'; " +
                "\$token = [Console]::In.ReadToEnd().Trim(); " +
                "\$secure = ConvertTo-SecureString -String \$token -AsPlainText -Force; " +
                "\$path = \$env:$WINDOWS_TOKEN_FILE_ENV; " +
                "New-Item -ItemType Directory -Force -Path (Split-Path -Parent \$path) | Out-Null; " +
                "ConvertFrom-SecureString -SecureString \$secure | Set-Content -LiteralPath \$path -Encoding ASCII"
        const val WINDOWS_READ_SCRIPT =
            "\$ErrorActionPreference = 'Stop'; " +
                "\$sealed = (Get-Content -LiteralPath \$env:$WINDOWS_TOKEN_FILE_ENV -Raw).Trim(); " +
                "\$secure = ConvertTo-SecureString -String \$sealed; " +
                "[Console]::Out.Write([System.Net.NetworkCredential]::new('', \$secure).Password)"

        fun windowsTokenFile(): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                ?: File(System.getProperty("user.home"), "AppData/Local").path
            return File(File(base, "Aive"), "distributed-compute-relay-token.dpapi")
        }

        fun windowsPowerShell(script: String, tokenFile: File): ProcessBuilder =
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).apply {
                environment()[WINDOWS_TOKEN_FILE_ENV] = tokenFile.absolutePath
            }

        val os: String get() = System.getProperty("os.name", "").lowercase()

        fun readFromKeychain(): String? = runCatching {
            val result = when {
                os.contains("mac") -> CredentialTool.run(
                    listOf(
                        "security", "find-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", ACCOUNT,
                        "-w",
                    ),
                )
                os.contains("win") -> {
                    val tokenFile = windowsTokenFile()
                    if (!tokenFile.isFile) return@runCatching null
                    CredentialTool.run(windowsPowerShell(WINDOWS_READ_SCRIPT, tokenFile))
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> CredentialTool.run(
                    listOf(
                        "secret-tool", "lookup",
                        "service", KEYCHAIN_SERVICE,
                        "account", ACCOUNT,
                    ),
                )
                else -> null
            }
            result?.takeIf { it.ok }?.output?.trim()?.takeIf(String::isNotEmpty)
        }.getOrNull()

        fun writeToKeychain(token: String): Boolean = runCatching {
            when {
                os.contains("mac") -> {
                    CredentialTool.run(
                        listOf(
                            "security", "delete-generic-password",
                            "-s", KEYCHAIN_SERVICE,
                            "-a", ACCOUNT,
                        ),
                    )
                    MacKeychain.addGenericPassword(KEYCHAIN_SERVICE, ACCOUNT, token)
                }
                os.contains("win") ->
                    CredentialTool.run(windowsPowerShell(WINDOWS_WRITE_SCRIPT, windowsTokenFile()), stdin = token)?.ok == true
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> CredentialTool.run(
                    listOf(
                        "secret-tool", "store",
                        "--label", "Aive distributed compute relay",
                        "service", KEYCHAIN_SERVICE,
                        "account", ACCOUNT,
                    ),
                    stdin = token,
                )?.ok == true
                else -> false
            }
        }.getOrElse { false }

        fun deleteFromKeychain() = runCatching {
            when {
                os.contains("mac") -> CredentialTool.run(
                    listOf(
                        "security", "delete-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", ACCOUNT,
                    ),
                )
                os.contains("win") -> {
                    windowsTokenFile().delete()
                    // Earlier builds wrote a Credential Manager entry that could never be read back.
                    CredentialTool.run(CredentialTool.windowsCredentialDeleteCommand(KEYCHAIN_SERVICE, ACCOUNT))
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> CredentialTool.run(
                    listOf(
                        "secret-tool", "clear",
                        "service", KEYCHAIN_SERVICE,
                        "account", ACCOUNT,
                    ),
                )
                else -> null
            }
        }
    }
}
